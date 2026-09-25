// HN_CAM: XIAO ESP32-S3 Sense camera over Bluetooth LE, Wi-Fi (through a relay) and USB.
//
// Board settings (Arduino IDE Tools menu / arduino-cli FQBN options):
//   Board:            XIAO_ESP32S3              esp32:esp32:XIAO_ESP32S3
//   PSRAM:            OPI PSRAM                 PSRAM=opi
//   USB CDC On Boot:  Enabled                   CDCOnBoot=default
//   USB Mode:         Hardware CDC and JTAG     USBMode=hwcdc (the default)
//
// Every packet is: 4-byte magic + uint32 little-endian length + JPEG bytes.
//   "XCAM"  live stream frame
//   "XSNP"  photo taken with the button on D0
// Over BLE the packets are one byte stream split across notifications on DATA_UUID.
// Writing 'P' to CTRL_UUID takes a photo, same as the button.
// Over USB they go out as a plain byte stream while the host keeps sending
// something (any byte) at least every USB_HOST_TIMEOUT_MS.
// Over Wi-Fi the board keeps a WebSocket open to the HN_CAM relay (relay/ in this project)
// and streams only while a viewer is watching; see "Wi-Fi relay" below.
// Priority when several are connected: USB, then Bluetooth, then Wi-Fi.
//
// Wi-Fi setup comes from the app over Bluetooth: it writes
// "ssid \x1f password \x1f relay host \x1f key" to CONFIG_UUID, and the board keeps it in flash.
// STATUS_UUID (read/notify) says how the Wi-Fi and relay are doing.
//
// Button: D0 to GND (internal pull-up). With no host connected a press only
// blinks the LED twice, since photos are saved on the computer.

#include "esp_camera.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include "host/ble_hs.h"
#include "host/ble_gap.h"
#include <WiFi.h>
#include <Preferences.h>
#include <WebSocketsClient.h>

#if !ARDUINO_USB_MODE
#error "Set Tools > USB Mode to Hardware CDC and JTAG. TinyUSB CDC stalls after the host reopens the port."
#endif

#define BLE_NAME     "HN_CAM"
#define SERVICE_UUID "7a1e0001-3c4b-4d8e-9f60-4e8c1a2b3c4d"
#define DATA_UUID    "7a1e0002-3c4b-4d8e-9f60-4e8c1a2b3c4d"
#define CTRL_UUID    "7a1e0003-3c4b-4d8e-9f60-4e8c1a2b3c4d"
#define CONFIG_UUID  "7a1e0004-3c4b-4d8e-9f60-4e8c1a2b3c4d"
#define STATUS_UUID  "7a1e0005-3c4b-4d8e-9f60-4e8c1a2b3c4d"

#define BUTTON_PIN D0
#define LED_PIN    LED_BUILTIN  // active low

#define USB_HOST_TIMEOUT_MS 1500

// Build with -DXCAM_DEBUG to get "DBG:" text lines on USB (the page skips them).
// Reading them doesn't count as a USB host, so Bluetooth keeps the stream.
#ifdef XCAM_DEBUG
#define DBG(...) Serial.printf("DBG: " __VA_ARGS__)
#else
#define DBG(...)
#endif
static uint32_t bleFramesSent = 0, bleSendFailures = 0;

// Camera pins for the XIAO ESP32S3 Sense expansion board.
#define PWDN_GPIO_NUM  -1
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM  10
#define SIOD_GPIO_NUM  40
#define SIOC_GPIO_NUM  39
#define Y9_GPIO_NUM    48
#define Y8_GPIO_NUM    11
#define Y7_GPIO_NUM    12
#define Y6_GPIO_NUM    14
#define Y5_GPIO_NUM    16
#define Y4_GPIO_NUM    18
#define Y3_GPIO_NUM    17
#define Y2_GPIO_NUM    15
#define VSYNC_GPIO_NUM 38
#define HREF_GPIO_NUM  47
#define PCLK_GPIO_NUM  13

// Where frames go.
enum Link { NONE, USB_LINK, BLE_LINK, WIFI_LINK };

// Fixed settings per link. BLE moves tens of KB/s, so it gets small, lighter frames.
struct LinkSettings {
  framesize_t stream;
  int streamQuality;
  framesize_t photo;
  int photoQuality;
};
static const LinkSettings USB_SETTINGS = {FRAMESIZE_VGA, 12, FRAMESIZE_UXGA, 10};
static const LinkSettings BLE_SETTINGS = {FRAMESIZE_QVGA, 20, FRAMESIZE_UXGA, 12};
static const LinkSettings WIFI_SETTINGS = {FRAMESIZE_VGA, 14, FRAMESIZE_UXGA, 10};

static uint32_t lastUsbRxMs = 0;
static bool usbSeen = false;

static BLECharacteristic *dataChar = nullptr;
static volatile uint16_t bleConn = BLE_HS_CONN_HANDLE_NONE;
static volatile bool bleSubscribed = false;

// Wi-Fi relay state.
static Preferences prefs;
static String wifiSsid, wifiPass, relayHost, relayKey;
static volatile bool configChanged = false;
static WebSocketsClient ws;
static bool wsStarted = false;
static volatile bool relayUp = false;
static volatile int viewers = 0;
static volatile int inFlight = 0;        // frames sent to the relay and not yet acknowledged
static volatile uint32_t lastAckMs = 0;
static BLECharacteristic *statusChar = nullptr;

static volatile bool buttonFlag = false;
static volatile uint32_t lastPressMs = 0;

void IRAM_ATTR onButton() {
  uint32_t now = millis();
  if (now - lastPressMs > 300) {
    lastPressMs = now;
    buttonFlag = true;
  }
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server, ble_gap_conn_desc *desc) override {
    bleConn = desc->conn_handle;
    DBG("ble connect handle=%d\n", desc->conn_handle);
    // Ask for the fastest link the central will agree to: 2M PHY, 251-byte
    // radio packets and a 7.5-15 ms connection interval.
    ble_gap_set_prefered_le_phy(desc->conn_handle, BLE_GAP_LE_PHY_2M_MASK, BLE_GAP_LE_PHY_2M_MASK, 0);
    ble_gap_set_data_len(desc->conn_handle, 251, 2120);
    server->updateConnParams(desc->conn_handle, 6, 12, 0, 400);
  }
  void onDisconnect(BLEServer *server, ble_gap_conn_desc *desc) override {
    DBG("ble disconnect handle=%d\n", desc->conn_handle);
    bleConn = BLE_HS_CONN_HANDLE_NONE;
    bleSubscribed = false;
    BLEDevice::startAdvertising();
  }
};

class DataCallbacks : public BLECharacteristicCallbacks {
  void onSubscribe(BLECharacteristic *c, ble_gap_conn_desc *desc, uint16_t subValue) override {
    bleSubscribed = subValue & 1;
    DBG("ble subscribe handle=%d value=%d\n", desc->conn_handle, subValue);
  }
};

class CtrlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    if (v.length() && v[0] == 'P') buttonFlag = true;
  }
};

// "ssid \x1f password \x1f relay host \x1f key" from the app. Saved here; applied in loop().
class ConfigCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    String parts[4];
    int n = 0, start = 0;
    for (int i = 0; i <= (int)v.length() && n < 4; i++) {
      if (i == (int)v.length() || v[i] == '\x1f') {
        parts[n++] = v.substring(start, i);
        start = i + 1;
      }
    }
    if (n != 4) return;
    prefs.putString("ssid", parts[0]);
    prefs.putString("pass", parts[1]);
    prefs.putString("host", parts[2]);
    prefs.putString("key", parts[3]);
    configChanged = true;
  }
};

void setupBle() {
  BLEDevice::init(BLE_NAME);
  // No bonding: the board forgets keys on every reboot, and a phone holding an old bond
  // then fails to encrypt and times out on every connection.
  ble_hs_cfg.sm_bonding = 0;
  BLEDevice::setMTU(517);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService *service = server->createService(SERVICE_UUID);
  dataChar = service->createCharacteristic(DATA_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  dataChar->addDescriptor(new BLE2902());
  dataChar->setCallbacks(new DataCallbacks());
  BLECharacteristic *ctrl = service->createCharacteristic(
    CTRL_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  ctrl->setCallbacks(new CtrlCallbacks());
  BLECharacteristic *config = service->createCharacteristic(CONFIG_UUID, BLECharacteristic::PROPERTY_WRITE);
  config->setCallbacks(new ConfigCallbacks());
  statusChar = service->createCharacteristic(
    STATUS_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  statusChar->setValue("WIFI NOT SET UP");
  service->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

bool setupCamera() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = FRAMESIZE_UXGA;  // allocate for the largest, then shrink
  config.jpeg_quality = 12;
  config.fb_count = 2;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.grab_mode = CAMERA_GRAB_LATEST;
  return esp_camera_init(&config) == ESP_OK;
}

void setup() {
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), onButton, FALLING);

  Serial.setTxBufferSize(16 * 1024);
  Serial.begin(921600);  // baud is ignored over native USB, but harmless

  if (!setupCamera()) {
    // Nothing to stream; blink fast forever and say why on USB.
    while (true) {
      Serial.println("Camera init failed. Is the Sense board attached and PSRAM set to OPI?");
      for (int i = 0; i < 10; i++) {
        digitalWrite(LED_PIN, i & 1);
        delay(100);
      }
    }
  }
  prefs.begin("hn_cam");
  setupBle();
  startWifi();
}

// ---- Wi-Fi relay ----
//
// The board dials out to wss://<relay host>/board?key=<key> and stays connected. The relay
// sends "V<n>" (how many viewers), and forwards the viewers' 'P' (take a photo) and
// 'A' (frame received). The board streams only while n > 0, and keeps at most two frames
// unacknowledged, so the stream runs at whatever speed the viewer's connection manages
// instead of piling up in the relay.

// The ESP-IDF root certificate bundle, linked into the core: lets TLS check the relay's
// certificate, so nobody in between can read the key or the video.
extern const uint8_t x509_crt_bundle_start[] asm("_binary_x509_crt_bundle_start");
extern const uint8_t x509_crt_bundle_end[] asm("_binary_x509_crt_bundle_end");

void onRelayEvent(WStype_t type, uint8_t *payload, size_t length) {
  switch (type) {
    case WStype_CONNECTED:
      relayUp = true;
      viewers = 0;
      inFlight = 0;
      break;
    case WStype_DISCONNECTED:
      relayUp = false;
      viewers = 0;
      inFlight = 0;
      break;
    case WStype_TEXT:
      if (length >= 1 && payload[0] == 'V') {
        viewers = atoi((const char *)payload + 1);
        inFlight = 0;
      } else if (length == 1 && payload[0] == 'A') {
        if (inFlight > 0) inFlight--;
        lastAckMs = millis();
      } else if (length == 1 && payload[0] == 'P') {
        buttonFlag = true;
      }
      break;
    default:
      break;
  }
}

void startWifi() {
  wifiSsid = prefs.getString("ssid", "");
  wifiPass = prefs.getString("pass", "");
  relayHost = prefs.getString("host", "");
  relayKey = prefs.getString("key", "");
  if (wsStarted) {
    ws.disconnect();
    wsStarted = false;
  }
  WiFi.disconnect(true);
  relayUp = false;
  viewers = 0;
  if (wifiSsid.isEmpty()) return;
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
}

// Called every loop: open the relay connection once Wi-Fi is up, and keep it serviced.
void serviceRelay() {
  if (configChanged) {
    configChanged = false;
    startWifi();
  }
  if (wifiSsid.isEmpty() || relayHost.isEmpty()) return;
  if (!wsStarted && WiFi.status() == WL_CONNECTED) {
    String path = "/board?key=" + relayKey;
    ws.beginSslWithBundle(relayHost.c_str(), 443, path.c_str(), x509_crt_bundle_start,
                          x509_crt_bundle_end - x509_crt_bundle_start, "");
    ws.onEvent(onRelayEvent);
    ws.setReconnectInterval(5000);
    ws.enableHeartbeat(15000, 5000, 2);
    wsStarted = true;
  }
  if (wsStarted) ws.loop();
}

bool relayStreaming() {
  return relayUp && viewers > 0;
}

// Ready for another live frame? At most two unacknowledged; if acks stop (viewer gone
// without a goodbye), start again after a couple of seconds.
bool relayReady() {
  if (inFlight >= 2 && millis() - lastAckMs > 2000) inFlight = 0;
  return inFlight < 2;
}

// Sends one packet to the relay. Live frames go as one message; photos go in 4 KB pieces,
// so the viewer sees them arrive (and can draw the mosaic) just like over Bluetooth.
static uint8_t *relayBuf = nullptr;
static const size_t RELAY_BUF = 128 * 1024;

void relaySend(const char *magic, const uint8_t *buf, uint32_t len, bool photo) {
  if (!relayBuf) relayBuf = (uint8_t *)ps_malloc(RELAY_BUF);
  if (!relayBuf) return;
  uint8_t header[8] = {(uint8_t)magic[0], (uint8_t)magic[1], (uint8_t)magic[2], (uint8_t)magic[3],
                       (uint8_t)(len), (uint8_t)(len >> 8), (uint8_t)(len >> 16), (uint8_t)(len >> 24)};
  size_t piece = photo ? 4096 : RELAY_BUF;
  size_t sent = 0;
  bool first = true;
  while (first || sent < len) {
    size_t off = 0;
    if (first) {
      memcpy(relayBuf, header, sizeof(header));
      off = sizeof(header);
    }
    size_t n = min((size_t)(len - sent), piece - off);
    memcpy(relayBuf + off, buf + sent, n);
    if (!ws.sendBIN(relayBuf, off + n)) return;
    sent += n;
    first = false;
    if (photo) ws.loop();  // keep the connection serviced during a long photo
  }
  if (!photo) {
    inFlight++;
    if (inFlight == 1) lastAckMs = millis();
  }
}

// What the app shows on its Wi-Fi setup screen.
String wifiStatusText() {
  if (wifiSsid.isEmpty()) return "WIFI NOT SET UP";
  if (WiFi.status() != WL_CONNECTED) return "CONNECTING TO " + wifiSsid;
  if (!relayUp) return "ON " + wifiSsid + " \xc2\xb7 REACHING RELAY";
  return "ONLINE ON " + wifiSsid + " \xc2\xb7 " + String((int)viewers) + (viewers == 1 ? " VIEWER" : " VIEWERS");
}

void updateStatus() {
  static uint32_t last = 0;
  static String shown;
  if (millis() - last < 500) return;
  last = millis();
  String now = wifiStatusText();
  if (now == shown) return;
  shown = now;
  statusChar->setValue(now.c_str());
  if (bleConnected()) statusChar->notify();
}

// ---- BLE byte stream: pack bytes into MTU-sized notifications ----

#define BLE_MAX_NOTIFY 244
static uint8_t bleChunk[BLE_MAX_NOTIFY];
static size_t bleChunkLen = 0;

bool bleConnected() {
  return bleConn != BLE_HS_CONN_HANDLE_NONE && bleSubscribed;
}

bool bleFlush() {
  if (bleChunkLen == 0) return true;
  uint32_t start = millis();
  // Give up on a stalled link rather than blocking USB and the button forever.
  while (bleConnected() && millis() - start < 2000) {
    // NimBLE runs out of buffers when we outpace the radio; wait and retry instead of dropping.
    os_mbuf *om = ble_hs_mbuf_from_flat(bleChunk, bleChunkLen);
    if (om) {
      int rc = ble_gatts_notify_custom(bleConn, dataChar->getHandle(), om);  // consumes om
      if (rc == 0) {
        bleChunkLen = 0;
        return true;
      }
      if (rc != BLE_HS_ENOMEM && rc != BLE_HS_EBUSY) {
        DBG("ble notify rc=%d\n", rc);
        break;
      }
    }
    delay(2);
  }
  bleChunkLen = 0;
  bleSendFailures++;
  return false;
}

bool bleWrite(const uint8_t *data, size_t len) {
  // Fill one link-layer packet at most: 251 bytes minus L2CAP (4) and ATT (3) headers.
  // Bigger notifications need splitting across radio packets, and some phones (seen on a
  // Nothing Phone (4a) Pro) silently drop those.
  uint16_t mtu = BLEDevice::getServer()->getPeerMTU(bleConn);
  size_t room = (mtu > 3 ? mtu - 3 : 20);
  if (room > BLE_MAX_NOTIFY) room = BLE_MAX_NOTIFY;
  while (len) {
    size_t n = min(len, room - bleChunkLen);
    memcpy(bleChunk + bleChunkLen, data, n);
    bleChunkLen += n;
    data += n;
    len -= n;
    if (bleChunkLen == room && !bleFlush()) return false;
  }
  return true;
}

// ---- Sending packets to whichever host is connected ----

Link activeLink() {
  if (usbSeen && millis() - lastUsbRxMs < USB_HOST_TIMEOUT_MS) return USB_LINK;
  if (bleConnected()) return BLE_LINK;
  if (relayStreaming()) return WIFI_LINK;
  return NONE;
}

const LinkSettings &settingsFor(Link link) {
  if (link == BLE_LINK) return BLE_SETTINGS;
  if (link == WIFI_LINK) return WIFI_SETTINGS;
  return USB_SETTINGS;
}

void sendPacket(Link link, const char *magic, const uint8_t *buf, uint32_t len) {
  uint8_t header[8] = {(uint8_t)magic[0], (uint8_t)magic[1], (uint8_t)magic[2], (uint8_t)magic[3],
                       (uint8_t)(len), (uint8_t)(len >> 8), (uint8_t)(len >> 16), (uint8_t)(len >> 24)};
  if (link == USB_LINK) {
    Serial.write(header, sizeof(header));
    Serial.write(buf, len);
  } else if (link == BLE_LINK) {
    if (bleWrite(header, sizeof(header)) && bleWrite(buf, len) && bleFlush()) bleFramesSent++;
  } else if (link == WIFI_LINK) {
    relaySend(magic, buf, len, magic[1] == 'S');
  }
}

// Change sensor settings and drop the frames captured before they took effect.
void applySettings(framesize_t size, int quality) {
  sensor_t *s = esp_camera_sensor_get();
  s->set_framesize(s, size);
  s->set_quality(s, quality);
  for (int i = 0; i < 3; i++) {
    camera_fb_t *old = esp_camera_fb_get();
    if (old) esp_camera_fb_return(old);
  }
}

void blink(int times) {
  for (int i = 0; i < times; i++) {
    digitalWrite(LED_PIN, LOW);
    delay(80);
    digitalWrite(LED_PIN, HIGH);
    delay(120);
  }
}

void takePhoto(Link link) {
  if (link == NONE) {
    blink(2);  // nowhere to save it
    return;
  }
  const LinkSettings &cfg = settingsFor(link);
  digitalWrite(LED_PIN, LOW);
  applySettings(cfg.photo, cfg.photoQuality);
  camera_fb_t *fb = esp_camera_fb_get();
  if (fb) {
    sendPacket(link, "XSNP", fb->buf, fb->len);
    esp_camera_fb_return(fb);
  }
  applySettings(cfg.stream, cfg.streamQuality);
  digitalWrite(LED_PIN, HIGH);
}

void loop() {
  serviceRelay();
  updateStatus();

  // Any bytes from a USB host (the page sends a keepalive) mean it's listening.
  if (Serial.available()) {
    while (Serial.available()) Serial.read();
    lastUsbRxMs = millis();
    usbSeen = true;
  }

  static Link lastLink = NONE;
  Link link = activeLink();
  if (link != lastLink && link != NONE) {
    applySettings(settingsFor(link).stream, settingsFor(link).streamQuality);
  }
  lastLink = link;

  if (buttonFlag) {
    buttonFlag = false;
    takePhoto(link);
  }

#ifdef XCAM_DEBUG
  static uint32_t lastDbg = 0;
  if (millis() - lastDbg > 1000) {
    lastDbg = millis();
    DBG("link=%d conn=%d sub=%d mtu=%d ble_frames=%u ble_fail=%u | %s inflight=%d\n", link, bleConn,
        bleSubscribed, bleConn == BLE_HS_CONN_HANDLE_NONE ? 0 : BLEDevice::getServer()->getPeerMTU(bleConn),
        bleFramesSent, bleSendFailures, wifiStatusText().c_str(), (int)inFlight);
  }
#endif
  if (link == NONE) {
    delay(20);
    return;
  }
  if (link == WIFI_LINK && !relayReady()) {
    delay(2);  // wait for the viewer to catch up
    return;
  }

  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) {
    DBG("no frame\n");
    return;
  }
  sendPacket(link, "XCAM", fb->buf, fb->len);
  esp_camera_fb_return(fb);
}
