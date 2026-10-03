# Connection and troubleshooting

Current **transport-r11** source uses Wi-Fi and manual IP only. Bluetooth setup,
radar screens and UDP discovery are removed from firmware, Android and web.

## Connect in three steps

1. Put the Glyph and your phone/computer on the same trusted local network. The
   Glyph can join your phone's 2.4 GHz hotspot or a shared 2.4 GHz router.
2. Open the board's USB Serial Monitor at **115200 baud**. Copy its `[ready]`
   recording address, for example `192.168.0.126:8080`. Check the board's GLYPH
   suffix so you do not select another workshop participant's device.
3. In Android or web, tap **Connect Glyph**, enter that address and connect.
   Prepare the local model if prompted. Wait for **Connected**, then click BOOT
   to start; click again or use **Stop & summarize** to finish.

Both apps remember the last entered IP, not a discoverable device identity. DHCP
can change the address after restarting the hotspot/router: copy the latest serial
address if reconnecting fails. Port defaults to 8080; `/audio` is added by the app.
There is one audio client per board. Disconnect Android before using web, and close
other connections to the same Glyph. No Bluetooth or location permission is needed.

## Configure Wi-Fi

Reset with BOOT released and tap BOOT during the **three-second countdown**. A
board with no saved network opens setup automatically. In a write-capable serial
monitor at 115200 with local echo off, answer the board-label and hotspot-password
prompts (including confirmation). Join its printed **GLYPH-name-suffix** network
using **your chosen password**, then open **http://192.168.4.1/**.
Save the target network's credentials. Only a successful join replaces saved Wi-Fi;
the board then restarts. Return your computer/phone to that network and read the
new recording IP. Leave BOOT released on this restart to use saved settings.
Only supported 2.4 GHz personal/open networks are accepted, not enterprise or
web-login Wi-Fi. WPA2 Personal is recommended; passwordless APs require selecting
**Open network** on the form.

Missed the boot logs? Open the USB monitor and send **STATUS** + Enter for the
current address/instructions. **HELP** lists commands; **WIFI SETUP** opens the
hotspot once all apps are disconnected. A browser monitor/flasher can replace
Arduino IDE: see [browser setup](FIRMWARE.md#browser-only-flash-and-serial-monitor-no-arduino-ide).

GPIO14 blinks without a Wi-Fi IP, then stays on once connected. This is Wi-Fi
status, not app/model readiness. The setup IP is not the normal recording IP.
Setup uses HTTP and your chosen private AP password; use a trusted environment.
The recording socket is unauthenticated
LAN traffic, so an IP address is not a security boundary.

## If audio stops

- `queue full` / `socket write failed`: the link or receiver is not draining audio
  fast enough. Bring board and receiver closer to the hotspot/router, avoid guest
  isolation, check signal/queue logs and use one client. More buffering cannot fix
  an indefinitely slower link. No samples are silently dropped to claim success.
  If Android works but web fails, compare the browser/computer receiver path:
  restart the updated Python server, keep the tab visible and the host awake,
  and capture the `[network] Slow send` lines. High board RSSI alone does not
  exclude receiver scheduling, TCP retransmissions or congestion. A `4104 ms`
  stalled send exceeds the old 64-slot queue; r10 provides 128 slots (about five
  seconds) but cannot guarantee recovery from longer or repeated stalls.
- Fresh BOOT countdown: the board restarted; inspect reset/power logs separately.
- `[stream] still sending` totals increase but text stops: inspect model/server
  progress and app errors. Silence still produces PCM; it must not end capture.
- Android uses its foreground service and background permissions. Web requires a
  visible page and awake computer/phone; locking/hiding the page interrupts it.

For workshops and the one-command web launcher, see [Workshop](../docs/WORKSHOP.md).
Local STT requires no internet once its model is installed. Cloud summaries require
internet and an explicitly configured API key. No phone microphone is used.
