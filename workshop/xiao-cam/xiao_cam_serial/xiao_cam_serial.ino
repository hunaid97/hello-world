// XIAO ESP32S3 Sense -> USB serial JPEG streamer.
//
// Arduino IDE settings (Tools menu):
//   Board:            XIAO_ESP32S3
//   PSRAM:            OPI PSRAM
//   USB CDC On Boot:  Enabled
//
// Each frame goes out as: "XCAM" + uint32 little-endian length + JPEG bytes.
// The host can send two-byte commands:
//   'S' <n>   set resolution (index into FRAME_SIZES below)
//   'Q' <n>   set JPEG quality (4 = best .. 63 = worst)

#include "esp_camera.h"

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

// Must match the order of the resolution menu in index.html.
static const framesize_t FRAME_SIZES[] = {
  FRAMESIZE_QQVGA,  // 160x120
  FRAMESIZE_QVGA,   // 320x240
  FRAMESIZE_HVGA,   // 480x320
  FRAMESIZE_VGA,    // 640x480
  FRAMESIZE_SVGA,   // 800x600
  FRAMESIZE_XGA,    // 1024x768
  FRAMESIZE_HD,     // 1280x720
  FRAMESIZE_UXGA,   // 1600x1200
};
static const int NUM_FRAME_SIZES = sizeof(FRAME_SIZES) / sizeof(FRAME_SIZES[0]);

void setup() {
  Serial.setTxBufferSize(16 * 1024);
  Serial.begin(921600);  // baud is ignored over native USB, but harmless

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

  if (esp_camera_init(&config) != ESP_OK) {
    // Nothing to stream; keep complaining so it shows up in Serial Monitor.
    while (true) {
      Serial.println("Camera init failed. Is the Sense board attached and PSRAM set to OPI?");
      delay(2000);
    }
  }

  sensor_t *s = esp_camera_sensor_get();
  s->set_framesize(s, FRAMESIZE_VGA);
}

void handleCommands() {
  while (Serial.available() >= 2) {
    int cmd = Serial.read();
    int arg = Serial.read();
    sensor_t *s = esp_camera_sensor_get();
    if (cmd == 'S' && arg >= 0 && arg < NUM_FRAME_SIZES) {
      s->set_framesize(s, FRAME_SIZES[arg]);
    } else if (cmd == 'Q' && arg >= 4 && arg <= 63) {
      s->set_quality(s, arg);
    }
  }
}

void loop() {
  handleCommands();

  // Only stream while the browser has the port open.
  if (!Serial) {
    delay(50);
    return;
  }

  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) return;

  uint32_t len = fb->len;
  uint8_t header[8] = {'X', 'C', 'A', 'M',
                       (uint8_t)(len), (uint8_t)(len >> 8),
                       (uint8_t)(len >> 16), (uint8_t)(len >> 24)};
  Serial.write(header, sizeof(header));
  Serial.write(fb->buf, fb->len);

  esp_camera_fb_return(fb);
}
