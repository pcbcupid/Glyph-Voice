# Hardware

## Parts

- PCB Cupid **GLYPH C6** (ESP32-C6, 4 MB flash).
- An I2S microphone module matching the supplied kit wiring and signal format.
  This project's original hardware specification calls it **T5858**. Its exact
  module datasheet has not been provided; it is not assumed to be the T5848 or
  ICS-43434 described by other product pages.
- Short jumper wires and USB power/data cable.
- A 64-bit Android phone with a 2.4 GHz hotspot.

## Circuit / wiring

[Open or download the editable SVG diagram](../hardware/glyph-voice-wiring.svg).
It is a module connection diagram, not a board fabrication schematic or physical
pin-location drawing. Locate pads using the [official GLYPH C6 pinout](https://github.com/pcbcupid/pcbcupid-docs/blob/main/documentation/modules/glyph/glyph-esp32c6/glyphc6-pinouts.mdx).

| GLYPH C6 | Microphone | Function |
| --- | --- | --- |
| 3V3 | VCC, **only if rated for 3.3 V** | Microphone power; confirm module rating |
| GND | GND | Common ground |
| GPIO16 | L/R | Driven LOW to select the left I2S slot |
| GPIO17 | WS / LRCLK | Word-select clock |
| GPIO22 | SCK / BCLK | Bit clock |
| GPIO23 | SD / DOUT | Microphone data into the C6 |
| Onboard BOOT / GPIO9 | No external wire | Click to start/stop recording |

Disconnect power while wiring. ESP32 GPIO uses 3.3 V logic; do not apply 5 V to
signal pins. Use USB CDC for logging: GPIO16/17 are shared with UART0 and cannot
also be used for UART logging in this wiring.

## How it works

The C6 generates a 48 kHz word-select clock and 3.072 MHz bit clock, reading the
left channel of a Philips I2S stream: signed 24-bit samples in 32-bit stereo slots.
A stateful 97-tap low-pass filter and 3:1 decimation produce **16 kHz mono PCM16**.
The configured digital gain is 2×, with saturation instead of wraparound.

After BOOT is clicked, the capture task packages 40 ms of sound per WebSocket
message. A bounded queue separates microphone sampling from Wi-Fi transmission.
The phone recognizes speech and keeps text in its private history. BOOT clicked
again, or the app's stop command, flushes the final short packet and ends the
recording. No complete audio recording is stored on the board or as a phone file.

The microphone clocks run while idle, but idle samples are discarded and never
sent. The board transmits audio only during a button-started recording with an
app connected. A lost connection or capture overrun interrupts the recording;
partial text already saved on the phone remains available.

The diagram follows the supplied GPIO mapping. Electrical compatibility, sound
quality and timing on the physical T5858 kit still require the [hardware tests](../docs/TESTING.md).
