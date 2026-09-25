// HN_FRAME: a tiny camera picture frame.
// XIAO ESP32-S3 Sense + 0.96" 80x160 ST7735S IPS display + rotary encoder with push switch.
//
//   The screen shows the camera live (LIVE), and always comes back to it.
//   Press the knob:  take a photo, save it, show it for SHOW_PHOTO_MS, then back to LIVE.
//   Turn the knob:   scroll back through saved photos (the last MAX_PHOTOS are kept in
//                    flash); SHOW_PHOTO_MS without touching the knob goes back to LIVE.
//   Hold and turn:   rotate the picture a quarter turn per click, until it's upright.
//                    Remembered across restarts.
//
// The screen is portrait (80x160) with the knob at the bottom. The camera only captures
// landscape 640x480, so every picture is turned 90 degrees and cropped to fill the screen.
// Photos are stored as the camera took them (landscape).
//
// Board settings (Arduino IDE Tools menu / arduino-cli FQBN options):
//   Board:            XIAO_ESP32S3              esp32:esp32:XIAO_ESP32S3
//   PSRAM:            OPI PSRAM                 PSRAM=opi
//   USB CDC On Boot:  Enabled                   CDCOnBoot=default
//   Partition Scheme: Default with spiffs       (photos live in the 1.5 MB spiffs partition, as LittleFS)
//
// Libraries: LovyanGFX (display + JPEG decoding).
//
// Wiring (everything on 3V3):
//   Display  GND-GND  VCC-3V3  SCL-D8  SDA-D10  RES-D3  DC-D2  CS-D1  BLK-D6
//   Encoder (bare EC11, uses the ESP32's pull-ups)
//            A-D4  C (middle pin)-GND  B-D5    switch: one pin-D0, the other-GND
//   Buzzer   +-D7  --GND   (3-pin buzzer module: VCC-3V3, GND-GND, I/O-D7)

#include "esp_camera.h"
#include <LittleFS.h>
#include <Preferences.h>
#include "driver/ledc.h"
#include <vector>
#include <algorithm>
#define LGFX_USE_V1
#include <LovyanGFX.hpp>

// ---- Pins (GPIO numbers; XIAO labels in comments) ----
#define PIN_TFT_SCLK 7   // D8
#define PIN_TFT_MOSI 9   // D10
#define PIN_TFT_RST  4   // D3
#define PIN_TFT_DC   3   // D2
#define PIN_TFT_CS   2   // D1
#define PIN_TFT_BL   43  // D6
#define PIN_ENC_A    5   // D4
#define PIN_ENC_B    6   // D5
#define PIN_ENC_SW   1   // D0
#define PIN_LED      21  // the XIAO's own orange user LED, active low
#define PIN_BUZZER   44  // D7

// ---- Things to adjust once it's on the desk ----
#define SCREEN_ROTATION 2     // 0 or 2: portrait, one way up or the other (for the text)
#define CAMERA_VFLIP    false // flip the camera image if it's upside down on screen
#define CAMERA_HMIRROR  false // mirror it if it's back to front
#define MAX_PHOTOS      20
#define BRIGHTNESS      255   // backlight, 0-255 (full)
#define SHOW_PHOTO_MS   5000  // how long a photo stays up before going back to LIVE
#define BUZZER_PASSIVE  true  // true: passive/piezo buzzer (plays tones); false: active buzzer (fixed beep)

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

// ---- Display ----

class Display : public lgfx::LGFX_Device {
  lgfx::Panel_ST7735S panel;
  lgfx::Bus_SPI bus;

 public:
  Display() {
    auto b = bus.config();
    b.spi_host = SPI2_HOST;
    b.spi_mode = 0;
    b.freq_write = 27000000;
    b.pin_sclk = PIN_TFT_SCLK;
    b.pin_mosi = PIN_TFT_MOSI;
    b.pin_miso = -1;
    b.pin_dc = PIN_TFT_DC;
    b.dma_channel = SPI_DMA_CH_AUTO;
    bus.config(b);
    panel.setBus(&bus);

    // The usual 0.96" 80x160 module: the 80x160 glass sits in the middle of the
    // controller's 132x162 memory, and the IPS panel needs inverted colours.
    auto p = panel.config();
    p.pin_cs = PIN_TFT_CS;
    p.pin_rst = PIN_TFT_RST;
    p.pin_busy = -1;
    p.panel_width = 80;
    p.panel_height = 160;
    p.memory_width = 132;
    p.memory_height = 162;
    p.offset_x = 26;
    p.offset_y = 1;
    p.invert = true;
    p.rgb_order = false;
    p.readable = false;
    p.bus_shared = false;
    panel.config(p);

    setPanel(&panel);
  }
};

static Display tft;

// ---- PWM for the backlight and the buzzer ----
//
// Set up straight on the ESP-IDF LEDC driver, each on its own timer. The camera's clock is on
// LEDC timer 0 but the Arduino core doesn't know that, so letting the core pick timers can put
// something else on timer 0 and have the camera reprogram it (that dimmed the backlight).

static const ledc_timer_t BL_TIMER = LEDC_TIMER_1, BUZZ_TIMER = LEDC_TIMER_2;
static const ledc_channel_t BL_CHANNEL = LEDC_CHANNEL_7, BUZZ_CHANNEL = LEDC_CHANNEL_5;

void pwmInit(int pin, ledc_timer_t timer, ledc_channel_t channel, uint32_t freq) {
  ledc_timer_config_t t = {};
  t.speed_mode = LEDC_LOW_SPEED_MODE;
  t.duty_resolution = LEDC_TIMER_10_BIT;
  t.timer_num = timer;
  t.freq_hz = freq;
  t.clk_cfg = LEDC_AUTO_CLK;
  ledc_timer_config(&t);
  ledc_channel_config_t c = {};
  c.gpio_num = pin;
  c.speed_mode = LEDC_LOW_SPEED_MODE;
  c.channel = channel;
  c.timer_sel = timer;
  c.duty = 0;
  c.hpoint = 0;
  ledc_channel_config(&c);
}

void pwmDuty(ledc_channel_t channel, uint32_t duty) {  // 0-1023
  ledc_set_duty(LEDC_LOW_SPEED_MODE, channel, duty);
  ledc_update_duty(LEDC_LOW_SPEED_MODE, channel);
}

void setBacklight(uint8_t level) {
  pwmDuty(BL_CHANNEL, (uint32_t)level * 1023 / 255);
}

void buzz(uint32_t freq) {  // 0 = silent
  if (!BUZZER_PASSIVE) {
    digitalWrite(PIN_BUZZER, freq ? HIGH : LOW);
    return;
  }
  if (freq) ledc_set_freq(LEDC_LOW_SPEED_MODE, BUZZ_TIMER, freq);
  pwmDuty(BUZZ_CHANNEL, freq ? 512 : 0);  // 50% square wave
}
static LGFX_Sprite frame(&tft);  // draw off-screen, then push in one go (no flicker)
static LGFX_Sprite raw(&tft);    // the camera's landscape picture at 1/4 size, before turning
static const int W = 80, H = 160;
static const int RAW_W = 160, RAW_H = 120;

// How far to turn the camera's picture to stand it up on the portrait screen.
// Hold the knob and turn to change it; saved in flash.
static Preferences prefs;
static int quarterTurns = 0;

// ---- Encoder (interrupt driven, one step per detent) ----
//
// Full-step state machine for a quadrature encoder resting with both lines high (pull-ups).
// Only a complete, valid sequence of transitions counts, so contact bounce is ignored.

static const uint8_t DIR_CW = 0x10, DIR_CCW = 0x20;
static const uint8_t ENC_TABLE[7][4] = {
  {0x0, 0x2, 0x4, 0x0},           // start
  {0x3, 0x0, 0x1, 0x0 | DIR_CW},  // cw final
  {0x3, 0x2, 0x0, 0x0},           // cw begin
  {0x3, 0x2, 0x1, 0x0},           // cw next
  {0x6, 0x0, 0x4, 0x0},           // ccw begin
  {0x6, 0x5, 0x0, 0x0 | DIR_CCW}, // ccw final
  {0x6, 0x5, 0x4, 0x0},           // ccw next
};
static volatile uint8_t encState = 0;
static volatile int encSteps = 0;
static volatile bool pressed = false;
static volatile uint32_t lastPressMs = 0;

void IRAM_ATTR onEncoder() {
  uint8_t pins = (digitalRead(PIN_ENC_B) << 1) | digitalRead(PIN_ENC_A);
  encState = ENC_TABLE[encState & 0x0f][pins];
  if (encState & DIR_CW) encSteps++;
  if (encState & DIR_CCW) encSteps--;
}

void IRAM_ATTR onPress() {
  uint32_t now = millis();
  if (now - lastPressMs > 150) {
    lastPressMs = now;
    pressed = true;
  }
}

bool knobDown() {
  return digitalRead(PIN_ENC_SW) == LOW;
}

// ---- LED feedback: the knob's clicks and presses, as blinks on the onboard LED ----
//
// One colour only, so events differ by pattern: clockwise = one short blip,
// counter-clockwise = two blips, press = one long flash. A small task plays them, so they
// keep their timing even while the loop is busy drawing.

enum Blink : uint32_t { BLINK_CW = 1, BLINK_CCW = 2, BLINK_PRESS = 3 };
static TaskHandle_t ledTask = nullptr;

void led(bool on) {
  digitalWrite(PIN_LED, on ? LOW : HIGH);
}

// A detent: 3 ms at 4.5 kHz is too short to hear as a pitch, so it reads as a click.
void tick() {
  buzz(4500);
  vTaskDelay(pdMS_TO_TICKS(3));
  buzz(0);
}

// A mechanical shutter, "ka-chk": a sharp click as it opens, then a lower double clack as
// it closes.
void shutter() {
  buzz(4200); vTaskDelay(pdMS_TO_TICKS(6));
  buzz(0);    vTaskDelay(pdMS_TO_TICKS(70));
  buzz(2200); vTaskDelay(pdMS_TO_TICKS(10));
  buzz(1500); vTaskDelay(pdMS_TO_TICKS(14));
  buzz(0);
}

void ledLoop(void *) {
  uint32_t pattern;
  while (true) {
    xTaskNotifyWait(0, 0xffffffff, &pattern, portMAX_DELAY);
    if (pattern == BLINK_CW) {
      led(true); tick(); vTaskDelay(pdMS_TO_TICKS(27)); led(false);
    } else if (pattern == BLINK_CCW) {
      led(true); tick(); vTaskDelay(pdMS_TO_TICKS(27)); led(false); vTaskDelay(pdMS_TO_TICKS(70));
      led(true); vTaskDelay(pdMS_TO_TICKS(30)); led(false);
    } else if (pattern == BLINK_PRESS) {
      led(true);
      shutter();
      vTaskDelay(pdMS_TO_TICKS(200));
      led(false);
    }
  }
}

void blink(uint32_t b) {  // a Blink value; uint32_t because Arduino hoists prototypes above the enum
  if (ledTask) xTaskNotify(ledTask, b, eSetValueWithOverwrite);
}

int takeSteps() {
  noInterrupts();
  int s = encSteps;
  encSteps = 0;
  interrupts();
  return s;
}

// ---- Photo store: /p/00001.jpg, /p/00002.jpg, ... in LittleFS ----

static std::vector<uint32_t> photos;  // ids, oldest first
static uint32_t nextId = 1;

String photoPath(uint32_t id) {
  char name[24];
  snprintf(name, sizeof(name), "/p/%05u.jpg", (unsigned)id);
  return String(name);
}

void loadPhotoList() {
  photos.clear();
  if (!LittleFS.exists("/p")) LittleFS.mkdir("/p");
  File dir = LittleFS.open("/p");
  for (File f = dir.openNextFile(); f; f = dir.openNextFile()) {
    uint32_t id = strtoul(f.name(), nullptr, 10);
    if (id) photos.push_back(id);
  }
  std::sort(photos.begin(), photos.end());
  nextId = photos.empty() ? 1 : photos.back() + 1;
}

void deleteOldest() {
  LittleFS.remove(photoPath(photos.front()));
  photos.erase(photos.begin());
}

bool savePhoto(const uint8_t *jpg, size_t len) {
  // Keep at most MAX_PHOTOS, and leave room for this one plus some slack.
  while (!photos.empty() &&
         (photos.size() >= MAX_PHOTOS || LittleFS.totalBytes() - LittleFS.usedBytes() < len + 32 * 1024)) {
    deleteOldest();
  }
  File f = LittleFS.open(photoPath(nextId), FILE_WRITE);
  if (!f) return false;
  size_t written = f.write(jpg, len);
  f.close();
  if (written != len) {
    LittleFS.remove(photoPath(nextId));
    return false;
  }
  photos.push_back(nextId++);
  return true;
}

static uint8_t *readBuf = nullptr;
static const size_t READ_BUF = 256 * 1024;

// Reads photo i into readBuf; returns its length, or 0.
size_t readPhoto(size_t i) {
  File f = LittleFS.open(photoPath(photos[i]), FILE_READ);
  if (!f) return 0;
  size_t len = f.read(readBuf, min((size_t)f.size(), READ_BUF));
  f.close();
  return len;
}

// ---- Camera ----

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
  // 640x480 for both the live view and the photos: big enough to be worth sending to a
  // phone later, small enough to decode quickly at 1/4 scale for the screen.
  config.frame_size = FRAMESIZE_VGA;
  config.jpeg_quality = 12;
  config.fb_count = 2;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.grab_mode = CAMERA_GRAB_LATEST;
  if (esp_camera_init(&config) != ESP_OK) return false;
  sensor_t *s = esp_camera_sensor_get();
  s->set_vflip(s, CAMERA_VFLIP);
  s->set_hmirror(s, CAMERA_HMIRROR);
  return true;
}

// ---- Drawing ----

// A 640x480 JPEG at 1/4 is 160x120; drawing it 20 px up fills the 160x80 screen with the
// middle of the picture.
void drawJpegFill(const uint8_t *jpg, size_t len) {
  // 640x480 at 1/4 is 160x120, then turned and scaled to fill the 80x160 screen:
  // - turned a quarter (odd turns) it's 120x160: fills the height, loses 20 px each side;
  // - upright (even turns) it's zoomed to 213x160: fills the height, keeps the middle 80 px.
  raw.fillScreen(TFT_BLACK);
  raw.drawJpg(jpg, len, 0, 0, RAW_W, RAW_H, 0, 0, 0.25f);
  frame.fillScreen(TFT_BLACK);
  float zoom = (quarterTurns % 2) ? 1.0f : (float)H / RAW_H;
  // SCREEN_ROTATION turns the text and the picture together; undo it for the picture so the
  // two can be set separately (hold-and-turn for the picture, SCREEN_ROTATION for the text).
  float angle = quarterTurns * 90.0f + (SCREEN_ROTATION == 2 ? 180.0f : 0.0f);
  raw.pushRotateZoom(&frame, W / 2.0f, H / 2.0f, angle, zoom, zoom);
}

// White text on a black box, in a corner.
void label(const char *text, bool right) {
  frame.setFont(&fonts::Font0);
  frame.setTextSize(1);
  int tw = frame.textWidth(text) + 6;
  int x = right ? W - tw : 0;
  frame.fillRect(x, H - 11, tw, 11, TFT_BLACK);
  frame.setTextColor(TFT_WHITE);
  frame.drawString(text, x + 3, H - 9);
}

void message(const char *text) {
  frame.fillScreen(TFT_BLACK);
  frame.setFont(&fonts::Font0);
  frame.setTextColor(TFT_WHITE);
  frame.setTextDatum(middle_center);
  frame.drawString(text, W / 2, H / 2);
  frame.setTextDatum(top_left);
  frame.pushSprite(0, 0);
}

// Where the knob is: photos[0 .. n-1], or n = LIVE.
static size_t position = 0;
static uint32_t labelUntil = 0;
static const char *labelText = nullptr;  // overrides the "3/20" label (e.g. "SAVED")
static uint32_t lastInputMs = 0;          // last press or turn, for the return to LIVE

bool isLive() {
  return position >= photos.size();
}

void drawPhoto() {
  size_t len = readPhoto(position);
  if (!len) {
    message("CAN'T READ PHOTO");
    return;
  }
  drawJpegFill(readBuf, len);
  if (millis() < labelUntil) {
    char pos[16];
    snprintf(pos, sizeof(pos), "%u/%u", (unsigned)(position + 1), (unsigned)photos.size());
    label(labelText ? labelText : pos, true);
  }
  frame.pushSprite(0, 0);
}

void drawLive() {
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) {
    static uint32_t misses = 0;
    if (++misses % 20 == 1) Serial.printf("camera: no frame (%u so far)\n", (unsigned)misses);
    return;
  }
  drawJpegFill(fb->buf, fb->len);
  esp_camera_fb_return(fb);
  if (millis() < labelUntil && labelText) label(labelText, true);
  label("LIVE", false);
  frame.pushSprite(0, 0);
}

void capture() {
  // Drop the frame that was already waiting, so the photo is from the moment of the press.
  camera_fb_t *fb = esp_camera_fb_get();
  if (fb) esp_camera_fb_return(fb);
  fb = esp_camera_fb_get();
  if (!fb) return;

  // A quick white flash, like a shutter.
  tft.fillScreen(TFT_WHITE);
  bool ok = savePhoto(fb->buf, fb->len);
  Serial.printf("capture: %u bytes, %s, %u photos\n", (unsigned)fb->len, ok ? "saved" : "SAVE FAILED",
                (unsigned)photos.size());
  esp_camera_fb_return(fb);
  if (!ok) {
    message("COULDN'T SAVE");
    delay(1000);
    return;
  }
  position = photos.size() - 1;
  lastInputMs = millis();
  labelText = "SAVED";
  labelUntil = millis() + 1500;
  drawPhoto();
}

// ---- Setup and loop ----

void setup() {
  Serial.begin(115200);

  tft.init();
  tft.setRotation(SCREEN_ROTATION);
  pwmInit(PIN_TFT_BL, BL_TIMER, BL_CHANNEL, 20000);
  setBacklight(BRIGHTNESS);
  if (BUZZER_PASSIVE) {
    pwmInit(PIN_BUZZER, BUZZ_TIMER, BUZZ_CHANNEL, 2000);
  } else {
    pinMode(PIN_BUZZER, OUTPUT);
    digitalWrite(PIN_BUZZER, LOW);
  }
  frame.setColorDepth(16);
  frame.createSprite(W, H);
  raw.setColorDepth(16);
  raw.createSprite(RAW_W, RAW_H);
  prefs.begin("hn_frame");
  quarterTurns = prefs.getInt("turns", 0);

  // Colour check on every boot: red, green, blue, white bars. If they come out in a
  // different order, rgb_order in Display() needs flipping.
  const uint16_t bars[] = {TFT_RED, TFT_GREEN, TFT_BLUE, TFT_WHITE};
  for (int i = 0; i < 4; i++) frame.fillRect(0, i * H / 4, W, H / 4, bars[i]);  // top to bottom
  frame.setTextColor(TFT_BLACK, TFT_WHITE);
  frame.setFont(&fonts::Font0);
  frame.drawString("HN_FRAME", 3, 3);
  frame.pushSprite(0, 0);
  delay(1200);

  pinMode(PIN_LED, OUTPUT);
  led(false);
  xTaskCreate(ledLoop, "led", 2048, nullptr, 2, &ledTask);

  pinMode(PIN_ENC_A, INPUT_PULLUP);
  pinMode(PIN_ENC_B, INPUT_PULLUP);
  pinMode(PIN_ENC_SW, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PIN_ENC_A), onEncoder, CHANGE);
  attachInterrupt(digitalPinToInterrupt(PIN_ENC_B), onEncoder, CHANGE);
  attachInterrupt(digitalPinToInterrupt(PIN_ENC_SW), onPress, FALLING);

  readBuf = (uint8_t *)ps_malloc(READ_BUF);
  if (!LittleFS.begin(true)) {
    message("STORAGE FAILED");
    while (true) delay(1000);
  }
  loadPhotoList();

  // The camera driver logs a warning from its own task for every bad frame, and printing
  // it overflows that task's small stack and resets the board. Keep it quiet; bad frames
  // are just skipped.
  esp_log_level_set("cam_hal", ESP_LOG_NONE);
  esp_log_level_set("camera", ESP_LOG_NONE);
  if (!setupCamera()) {
    message("CAMERA FAILED");
    Serial.println("Camera init failed. Is the Sense board attached and PSRAM set to OPI?");
    while (true) delay(1000);
  }

  Serial.printf("HN_FRAME ready: %u photos, %u KB free\n", (unsigned)photos.size(),
                (unsigned)((LittleFS.totalBytes() - LittleFS.usedBytes()) / 1024));
  position = photos.size();  // start on LIVE
}

void loop() {
  // A press takes a photo when the knob comes back up, unless it was turned while held
  // (that rotates the picture instead).
  static bool held = false;
  static bool turnedWhileHeld = false;
  if (pressed) {
    pressed = false;
    held = true;
    turnedWhileHeld = false;
  }

  int steps = takeSteps();
  if (held && steps) {
    turnedWhileHeld = true;
    quarterTurns = ((quarterTurns + steps) % 4 + 4) % 4;
    prefs.putInt("turns", quarterTurns);
    Serial.printf("rotate: %d quarter turns\n", quarterTurns);
    blink(steps > 0 ? BLINK_CW : BLINK_CCW);
    labelText = "ROTATE";
    labelUntil = millis() + 1500;
    if (!isLive()) drawPhoto();
    steps = 0;
  }
  if (held && !knobDown()) {
    delay(20);  // let the switch settle before trusting the release
    if (!knobDown()) {
      held = false;
      if (!turnedWhileHeld) {
        Serial.println("press");
        blink(BLINK_PRESS);
        capture();
      }
    }
  }

  if (steps) {
    lastInputMs = millis();
    Serial.printf("turn %+d\n", steps);
    blink(steps > 0 ? BLINK_CW : BLINK_CCW);
    // Clockwise goes newer, towards LIVE; counter-clockwise goes back in time.
    long p = (long)position + steps;
    position = (size_t)constrain(p, 0L, (long)photos.size());
    labelText = nullptr;
    labelUntil = millis() + 1500;
    if (!isLive()) drawPhoto();
  }

  // Back to the camera once a photo has been up for a while with nothing touched.
  if (!isLive() && !held && millis() - lastInputMs > SHOW_PHOTO_MS) {
    position = photos.size();
    labelText = nullptr;
    labelUntil = 0;
  }

  static bool labelShown = false;
  if (isLive()) {
    drawLive();
  } else if (labelShown && millis() >= labelUntil) {
    drawPhoto();  // clear the "3/20" label once it has had its moment
  }
  labelShown = !isLive() && millis() < labelUntil;
  if (!isLive()) delay(10);
}
