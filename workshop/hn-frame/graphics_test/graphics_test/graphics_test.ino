// HN_FRAME graphics test: scroll through the Figma watch-face frames on the display.
//
//   Turn the knob:  next / previous frame (wraps around), with an LED blip.
//   Press the knob: turn the screen 180 degrees, in case it's upside down.
//
// The frames are in graphics.h (generated; see ../README.md). Same wiring as hn_frame.ino:
//   Display  GND-GND  VCC-3V3  SCL-D8  SDA-D10  RES-D3  DC-D2  CS-D1  BLK-D6
//   Encoder  A-D4  C (middle)-GND  B-D5    switch: D0 and GND
//   Buzzer   +-D7  --GND (kept silent in this test)

#define LGFX_USE_V1
#include <LovyanGFX.hpp>
#include "graphics.h"

#define PIN_TFT_SCLK 7   // D8
#define PIN_TFT_MOSI 9   // D10
#define PIN_TFT_RST  4   // D3
#define PIN_TFT_DC   3   // D2
#define PIN_TFT_CS   2   // D1
#define PIN_TFT_BL   43  // D6
#define PIN_ENC_A    5   // D4
#define PIN_ENC_B    6   // D5
#define PIN_ENC_SW   1   // D0
#define PIN_BUZZER   44  // D7
#define PIN_LED      21  // onboard orange LED, active low

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
static int rotation = 1;  // landscape; a press flips between 1 and 3

// ---- Encoder: full-step quadrature state machine (resting with both lines high) ----

static const uint8_t DIR_CW = 0x10, DIR_CCW = 0x20;
static const uint8_t ENC_TABLE[7][4] = {
  {0x0, 0x2, 0x4, 0x0},
  {0x3, 0x0, 0x1, 0x0 | DIR_CW},
  {0x3, 0x2, 0x0, 0x0},
  {0x3, 0x2, 0x1, 0x0},
  {0x6, 0x0, 0x4, 0x0},
  {0x6, 0x5, 0x0, 0x0 | DIR_CCW},
  {0x6, 0x5, 0x4, 0x0},
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
  if (now - lastPressMs > 250) {
    lastPressMs = now;
    pressed = true;
  }
}

// ---- Blip the LED on every step (the buzzer stays silent in this test) ----

void tick() {
  digitalWrite(PIN_LED, LOW);
  delay(25);
  digitalWrite(PIN_LED, HIGH);
}

// ---- Drawing ----

static int current = 0;

void show(int i) {
  tft.pushImage(0, 0, GRAPHIC_W, GRAPHIC_H, GRAPHICS[i]);
  Serial.printf("graphic %d/%d\n", i + 1, GRAPHIC_COUNT);
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, HIGH);
  pinMode(PIN_TFT_BL, OUTPUT);
  digitalWrite(PIN_TFT_BL, HIGH);  // backlight full on
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_BUZZER, LOW);  // silent

  tft.init();
  tft.setSwapBytes(true);  // graphics.h holds plain RGB565 values, not byte-swapped ones
  tft.setRotation(rotation);
  tft.fillScreen(TFT_BLACK);
  show(current);

  pinMode(PIN_ENC_A, INPUT_PULLUP);
  pinMode(PIN_ENC_B, INPUT_PULLUP);
  pinMode(PIN_ENC_SW, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PIN_ENC_A), onEncoder, CHANGE);
  attachInterrupt(digitalPinToInterrupt(PIN_ENC_B), onEncoder, CHANGE);
  attachInterrupt(digitalPinToInterrupt(PIN_ENC_SW), onPress, FALLING);
}

void loop() {
  noInterrupts();
  int steps = encSteps;
  encSteps = 0;
  interrupts();

  if (steps) {
    current = ((current + steps) % GRAPHIC_COUNT + GRAPHIC_COUNT) % GRAPHIC_COUNT;
    show(current);
    tick();
  }

  if (pressed) {
    pressed = false;
    rotation = (rotation == 1) ? 3 : 1;
    tft.setRotation(rotation);
    show(current);
    tick();
    Serial.printf("rotation %d\n", rotation);
  }
  delay(5);
}
