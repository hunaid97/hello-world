# HN_FRAME

A tiny camera picture frame: XIAO ESP32-S3 Sense, a 0.96" 80x160 ST7735S IPS display and a
rotary encoder with a push switch.

- Press the knob: take a photo, save it, show it.
- Turn the knob: scroll through the saved photos (the last 20 stay in flash).
  One step past the newest photo is LIVE, what the camera sees now.

## Wiring

Everything runs on 3V3. Don't put the encoder on 5V: its pull-ups would drive the ESP32 pins at 5V.
XIAO pins as seen from the top, USB-C at the top: left column D0-D6, right column 5V, GND, 3V3, D10, D9, D8, D7.

| Part | Pin | XIAO |
|---|---|---|
| Display | GND | GND |
| Display | VCC | 3V3 |
| Display | SCL | D8 (SPI clock) |
| Display | SDA | D10 (SPI data) |
| Display | RES | D3 |
| Display | DC | D2 |
| Display | CS | D1 |
| Display | BLK | D6 (backlight) |
| Encoder | VCC | 3V3 |
| Encoder | GND | GND |
| Encoder | A | D4 |
| Encoder | B | D5 |
| Encoder | C (switch) | D0 |

## Build

```
arduino-cli lib install LovyanGFX
arduino-cli compile --upload -b esp32:esp32:XIAO_ESP32S3:PSRAM=opi,CDCOnBoot=default -p <port> hn_frame
```

If the picture is upside down or mirrored, or the colour bars at boot aren't red, green,
blue, white, see the settings at the top of `hn_frame/hn_frame.ino`.
