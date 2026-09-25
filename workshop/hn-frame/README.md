# HN_FRAME

A tiny camera picture frame: XIAO ESP32-S3 Sense, a 0.96" 80x160 ST7735S IPS display and a
bare EC11 rotary encoder with a push switch. Portrait, with the knob at the bottom.

- The screen shows the camera live, and always comes back to it.
- Press the knob: take a photo, save it, show it for 5 seconds, then back to live.
- Turn the knob: scroll back through the saved photos (the last 20 stay in flash);
  5 seconds without touching the knob goes back to live.
- Hold the knob and turn: rotate the picture a quarter turn per click until it's upright.
- The XIAO's orange LED blinks on every click (one blip clockwise, two counter-clockwise)
  and flashes long when a photo is taken, and the buzzer plays a shutter sound.

## Wiring

The display runs on 3V3. The encoder needs no power: it only connects its pins to GND, and
the ESP32's internal pull-ups do the rest.
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
| Encoder (3-pin side) | A (outer) | D4 |
| Encoder (3-pin side) | C (middle) | GND |
| Encoder (3-pin side) | B (outer) | D5 |
| Encoder (2-pin side) | switch pin 1 | D0 |
| Encoder (2-pin side) | switch pin 2 | GND |

| Buzzer | + | D7 |
| Buzzer | - | GND |

If turning scrolls the wrong way, swap the A and B wires. A 3-pin buzzer module goes
VCC-3V3, GND-GND, I/O-D7. The firmware expects a passive (piezo) buzzer and plays a shutter
sound; for an active buzzer set `BUZZER_PASSIVE` to false.

## Power

A single-cell 3.7V LiPo (with a protection circuit) on the XIAO's BAT+ / BAT- pads, on the
underside. The XIAO charges it from USB-C.

| From | To |
|---|---|
| Battery + (red) | SPDT switch, middle pin |
| SPDT switch, one outer pin | XIAO BAT+ |
| SPDT switch, other outer pin | nothing |
| Battery - (black) | XIAO BAT- |

The switch disconnects the battery. With USB-C plugged in the board runs whatever the switch
says, and the battery only charges while the switch is on. Keep the display on 3V3: the 5V pin
is only powered from USB.

## Build

```
arduino-cli lib install LovyanGFX
arduino-cli compile --upload -b esp32:esp32:XIAO_ESP32S3:PSRAM=opi,CDCOnBoot=default -p <port> hn_frame
```

If the picture is upside down or mirrored, or the colour bars at boot aren't red, green,
blue, white, see the settings at the top of `hn_frame/hn_frame.ino`.
