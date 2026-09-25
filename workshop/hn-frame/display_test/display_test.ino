// HN_FRAME display and buzzer test: are the ST7735S and the buzzer wired right?
//
// Same pins and panel settings as hn_frame.ino. Shows, in portrait:
//   - colour bars top to bottom: red, green, blue, white (checks colour order and inversion)
//   - a 1 px white border on the very edge (checks the panel offset: every side should show)
//   - a counter that ticks once a second (checks the screen keeps updating)
//   - the buzzer's shutter sound at boot and every 5 seconds, with "BEEP" on screen
// and prints each step on USB serial.
//
// Wiring: display GND-GND  VCC-3V3  SCL-D8  SDA-D10  RES-D3  DC-D2  CS-D1  BLK-D6
//         buzzer  +-D7  --GND

#define LGFX_USE_V1
#include <LovyanGFX.hpp>
#include "driver/ledc.h"

#define PIN_TFT_SCLK 7   // D8
#define PIN_TFT_MOSI 9   // D10
#define PIN_TFT_RST  4   // D3
#define PIN_TFT_DC   3   // D2
#define PIN_TFT_CS   2   // D1
#define PIN_TFT_BL   43  // D6
#define PIN_BUZZER   44  // D7

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
static const int W = 80, H = 160;

// Passive buzzer: a square wave on LEDC timer 2 (same setup as hn_frame.ino).
void buzzerInit() {
  ledc_timer_config_t t = {};
  t.speed_mode = LEDC_LOW_SPEED_MODE;
  t.duty_resolution = LEDC_TIMER_10_BIT;
  t.timer_num = LEDC_TIMER_2;
  t.freq_hz = 2000;
  t.clk_cfg = LEDC_AUTO_CLK;
  ledc_timer_config(&t);
  ledc_channel_config_t c = {};
  c.gpio_num = PIN_BUZZER;
  c.speed_mode = LEDC_LOW_SPEED_MODE;
  c.channel = LEDC_CHANNEL_5;
  c.timer_sel = LEDC_TIMER_2;
  ledc_channel_config(&c);
}

void buzz(uint32_t freq) {  // 0 = silent
  if (freq) ledc_set_freq(LEDC_LOW_SPEED_MODE, LEDC_TIMER_2, freq);
  ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_5, freq ? 512 : 0);
  ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_5);
}

void shutterSound() {
  tft.setTextColor(TFT_BLACK, TFT_WHITE);
  tft.drawString(" BEEP ", 40, 142);
  Serial.println("display test: beep");
  buzz(2600); delay(25);
  buzz(0);    delay(15);
  buzz(1700); delay(45);
  buzz(0);
  delay(300);
  tft.fillRect(40, 142, 36, 10, TFT_BLACK);
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_TFT_BL, OUTPUT);
  digitalWrite(PIN_TFT_BL, HIGH);  // backlight fully on, no PWM
  tft.init();
  tft.setRotation(0);
  Serial.println("display test: init done, backlight on");

  const uint16_t bars[] = {TFT_RED, TFT_GREEN, TFT_BLUE, TFT_WHITE};
  const char *names[] = {"RED", "GREEN", "BLUE", "WHITE"};
  for (int i = 0; i < 4; i++) {
    tft.fillRect(0, i * 30, W, 30, bars[i]);
    tft.setTextColor(i == 3 ? TFT_BLACK : TFT_WHITE, bars[i]);
    tft.drawString(names[i], 4, i * 30 + 11);
  }
  tft.fillRect(0, 120, W, H - 120, TFT_BLACK);
  tft.drawRect(0, 0, W, H, TFT_WHITE);  // the edge of the glass
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.drawString("HN_FRAME", 4, 126);
  Serial.println("display test: bars, border and label drawn");

  buzzerInit();
  shutterSound();
}

void loop() {
  static uint32_t n = 0;
  char text[16];
  snprintf(text, sizeof(text), "%lu   ", (unsigned long)n++);
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.drawString(text, 4, 142);
  Serial.printf("display test: tick %s\n", text);
  if (n % 5 == 0) shutterSound();
  delay(1000);
}
