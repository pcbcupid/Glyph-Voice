#pragma once
#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <Preferences.h>
#include <esp_random.h>
#include "config.h"
#include "WifiCredentials.h"
#include "HotspotProfile.h"

// Wi-Fi only: startup BOOT selection opens the local configuration portal.
class WifiSetup {
 public:
  bool active = false;
  String boardId;
  bool configuring() const { return active || naming; }
  bool needsSerial() const { return naming; }
  void serialDisconnected() { setupLine.reset(); }
  void serialByte(char value) {
    const auto read = setupLine.feed(value);
    if (read == SetupLineReader::Waiting) return;
    if (read == SetupLineReader::Invalid) {
      Serial.println("[setup] Input too long or unsupported characters. Nothing was saved; retry this prompt");
    } else {
      const auto result = wizard.submit(setupLine.line());
      if (result == HotspotWizard::BadName) Serial.println("[setup] Use 1-19 letters/numbers/hyphens/underscores for the hotspot label");
      if (result == HotspotWizard::BadPassword) Serial.println("[setup] Use 8-63 printable ASCII characters for a private hotspot password");
      if (result == HotspotWizard::Mismatch) Serial.println("[setup] Passwords did not match. Enter a new hotspot password, then confirm it");
    }
    setupLine.consumed();
    if (wizard.stage == HotspotWizard::Ready) {
      if (prefs.putBytes("ap-v1", &wizard.value, sizeof(wizard.value)) != sizeof(wizard.value)) {
        Serial.println("[setup] Could not save hotspot profile. Previous settings unchanged; please retry");
        wizard.begin(profile);
      } else {
        profile = wizard.value; wizard.clear(); naming = false;
        Serial.println("[setup] Hotspot name/password saved. Creating your personal setup hotspot...");
        startPortal();
        return;
      }
    }
    printNamePrompt();
  }
  void begin(bool bootRequested = false) {
    WiFi.persistent(false); // Our validated, atomic NVS record is the only credential store.
    WiFi.mode(WIFI_STA); WiFi.setSleep(false); WiFi.setAutoReconnect(true);
    boardId = WiFi.macAddress(); boardId.replace(":", "");
    if (!prefs.begin("glyph-wifi", false)) Serial.println("[setup] NVS unavailable; configuration cannot be saved until storage is repaired");
    if (prefs.getBytesLength("ap-v1") == sizeof(profile)) prefs.getBytes("ap-v1", &profile, sizeof(profile));
    if (!validHotspotProfile(profile)) memset(&profile, 0, sizeof(profile));
    WifiCredentials saved = {};
    if (prefs.getBytesLength("wifi-v2") == sizeof(saved)) prefs.getBytes("wifi-v2", &saved, sizeof(saved));
    else {
      prefs.getString("ssid", "").toCharArray(saved.ssid, sizeof(saved.ssid));
      prefs.getString("password", "").toCharArray(saved.password, sizeof(saved.password));
    }
    saved.ssid[32] = 0; saved.password[63] = 0;
    const bool forcePortal = prefs.getBool("setup", false); prefs.remove("setup");
    Serial.printf("[board] GLYPH-%s | ID=%s | Wi-Fi only; Bluetooth is disabled\n", boardId.substring(6).c_str(), boardId.c_str());
    if (!saved.ssid[0] || forcePortal || bootRequested) {
      Serial.println(bootRequested || forcePortal
          ? "[setup] Wi-Fi configuration requested; existing saved Wi-Fi is kept until a new connection succeeds"
          : "[setup] No saved Wi-Fi yet; choose your board hotspot name/password in USB Serial Monitor");
      startNaming();
    } else {
      // Arduino defaults to WPA2 minimum; explicitly allow open APs only for a
      // previously validated open-network configuration.
      WiFi.setMinSecurity(saved.password[0] ? WIFI_AUTH_WPA2_PSK : WIFI_AUTH_OPEN);
      WiFi.begin(saved.ssid, saved.password);
      Serial.println("[network] Joining your saved 2.4 GHz Wi-Fi; waiting for an IP address...");
      Serial.println("[network] To change Wi-Fi: reset and TAP BOOT during 3...2...1, or send WIFI SETUP + Enter while no app is connected");
    }
    memset(&saved, 0, sizeof(saved));
  }
  void poll(bool noAudioClient) {
    if (restartPending) {
      if (active) { dns.processNextRequest(); server.handleClient(); }
      if (millis() - restartStarted >= RESTART_DELAY_MS) {
        Serial.println("[setup] Restarting into normal mode. Leave BOOT released to use saved Wi-Fi");
        ESP.restart();
      }
      return;
    }
    // Let the HTTP redirect reach the browser before changing the radio channel.
    if (portalQueued && millis() - portalQueuedAt >= RESPONSE_DELAY_MS) {
      portalQueued = false; startJoin(candidate);
    }
    if (joining) {
      if (millis() - joinStarted >= 1000 && WiFi.status() == WL_CONNECTED && WiFi.SSID() == candidate.ssid
          && WiFi.localIP() != IPAddress(0, 0, 0, 0)) {
        const bool saved = prefs.putBytes("wifi-v2", &candidate, sizeof(candidate)) == sizeof(candidate);
        memset(&candidate, 0, sizeof(candidate)); joining = false;
        if (saved) {
          prefs.remove("ssid"); prefs.remove("password");
          Serial.println("[setup] SUCCESS: Wi-Fi connected; credentials saved on this board (password never logged)");
          Serial.printf("[setup] Normal recording address: %s:%u\n", WiFi.localIP().toString().c_str(), Config::PORT);
          portalState = "restarting";
          portalMessage = "Wi-Fi connected and saved. Restarting into normal mode; rejoin your usual Wi-Fi.";
          restartPending = true; restartStarted = millis();
          Serial.println("[setup] Restarting in 3 seconds. LEAVE BOOT RELEASED during restart so saved Wi-Fi is selected");
          Serial.println("[setup] Rejoin the target Wi-Fi on your computer/phone. After restart, copy the NEW [ready] address into Connect Glyph");
        } else {
          portalState = "failed"; portalMessage = "Could not save Wi-Fi settings. Previous saved network is unchanged. Retry or check USB logs.";
          WiFi.disconnect();
          Serial.println("[setup] Storage write failed; NOT restarting or reporting saved settings");
        }
      } else if (millis() - joinStarted >= 30000) {
        Serial.printf("[setup] Join failed after 30 seconds: %s\n", connectionState());
        joining = false; WiFi.disconnect(); memset(&candidate, 0, sizeof(candidate));
        portalState = "failed"; portalMessage = "Could not join Wi-Fi. Check the password and 2.4 GHz network, then retry below. Previous saved settings are unchanged.";
        Serial.println("[setup] Could not join Wi-Fi; previous saved network unchanged. Rejoin this board's setup hotspot and retry http://192.168.4.1/");
        Serial.println("[setup] Check exact Wi-Fi name/password, 2.4 GHz, WPA2 Personal, router range and DHCP. Enterprise/captive-login networks are not supported");
      }
    }
    if (active) { dns.processNextRequest(); server.handleClient(); }
    if (noAudioClient && !active && !busy() && digitalRead(Config::RECORD_BUTTON) == LOW) {
      if (!heldSince) heldSince = millis();
      if (millis() - heldSince >= 5000) {
        if (prefs.putBool("setup", true)) {
          Serial.println("[setup] Rebooting into Wi-Fi setup"); ESP.restart();
        } else { Serial.println("[setup] Could not save setup request; reset and tap BOOT during countdown"); heldSince = millis(); }
      }
    } else heldSince = 0;
  }
  bool busy() const { return naming || joining || portalQueued || restartPending; }
  // Caller must disconnect the audio server first; never change radio modes while
  // an app is connected. This is useful when a web serial monitor missed boot.
  void openFromSerial(char commandEnding) {
    if (!busy()) { startNaming(); setupLine.commandEnded(commandEnding); }
    else printStatus();
  }
  static const char* connectionState() {
    switch (WiFi.status()) {
      case WL_CONNECTED: return "connected (check IP below)";
      case WL_NO_SSID_AVAIL: return "network not found";
      case WL_CONNECT_FAILED: return "connection failed (password/security/signal may be wrong)";
      case WL_CONNECTION_LOST: return "connection lost";
      case WL_DISCONNECTED: return "disconnected / still attempting to join";
      case WL_IDLE_STATUS: return "starting connection / waiting";
      default: return "Wi-Fi not ready";
    }
  }
  void printStatus() const {
    Serial.printf("[board] GLYPH-%s | ID=%s\n", boardId.substring(6).c_str(), boardId.c_str());
    if (naming) {
      printNamePrompt();
    } else if (active) {
      Serial.printf("[setup] State: %s. %s\n", portalState, portalMessage);
      printPortalInstructions();
    } else {
      Serial.printf("[network] %s\n", connectionState());
      if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("[network] IP=%s | RSSI=%d dBm | channel=%d\n", WiFi.localIP().toString().c_str(), WiFi.RSSI(), WiFi.channel());
        if (WiFi.localIP() != IPAddress(0, 0, 0, 0)) {
          Serial.printf("[network] Connect Glyph address: %s:%u | audio endpoint: ws://%s:%u%s\n",
                        WiFi.localIP().toString().c_str(), Config::PORT,
                        WiFi.localIP().toString().c_str(), Config::PORT, Config::AUDIO_PATH);
        }
      }
      Serial.println("[setup] Different Wi-Fi? Disconnect the app, then send WIFI SETUP + Enter, or reset and tap BOOT during the 3-second countdown");
    }
  }
 private:
  Preferences prefs;
  WebServer server{80};
  DNSServer dns;
  static constexpr uint32_t RESPONSE_DELAY_MS = 250, RESTART_DELAY_MS = 3000;
  uint32_t heldSince = 0, joinStarted = 0, portalQueuedAt = 0, restartStarted = 0;
  bool joining = false, portalQueued = false, restartPending = false;
  bool naming = false, routesInstalled = false;
  HotspotProfile profile = {};
  HotspotWizard wizard;
  SetupLineReader setupLine;
  const char* portalState = "ready";
  const char* portalMessage = "Enter your 2.4 GHz Wi-Fi or hotspot details. Nothing is saved until the connection succeeds.";
  WifiCredentials candidate = {};
  String token;
  String setupName() const {
    char name[33]; hotspotName(name, profile, boardId.substring(6).c_str()); return String(name);
  }
  void printNamePrompt() const {
    if (wizard.stage == HotspotWizard::Name) {
      Serial.println("[setup] STEP 1/2: Choose YOUR BOARD'S hotspot label (not your router's Wi-Fi name)");
      Serial.println("[setup] Type e.g. Alice or Kit12, then Enter/Newline. Use 1-19 letters/numbers/-/_; a hardware suffix is added for uniqueness");
      if (wizard.value.label[0]) Serial.printf("[setup] Or press Enter to keep: %s\n", wizard.value.label);
    } else if (wizard.stage == HotspotWizard::Password) {
      Serial.println("[setup] STEP 2/2: Enter a private password for this BOARD'S setup hotspot, then Enter (8-63 printable ASCII characters)");
      if (wizard.value.password[0]) Serial.println("[setup] Or press Enter to keep your previous hotspot password");
      Serial.println("[setup] Firmware does NOT echo passwords. Turn OFF local echo in your web serial monitor; do not reuse your router/account password");
    } else if (wizard.stage == HotspotWizard::Confirm) {
      Serial.println("[setup] Retype the new hotspot password, then Enter. It will not be printed");
    }
  }
  void startNaming() {
    if (active) { server.stop(); dns.stop(); WiFi.softAPdisconnect(true); active = false; }
    WiFi.setAutoReconnect(false); WiFi.disconnect(); WiFi.mode(WIFI_STA);
    naming = true; setupLine.reset(); wizard.begin(profile);
    Serial.println("[setup] PERSONAL HOTSPOT SETUP: keep USB connected; enter the following in Serial Monitor at 115200 with Newline/Enter");
    Serial.println("[setup] After naming the board, join its hotspot and enter your ROUTER'S Wi-Fi details on http://192.168.4.1/");
    printNamePrompt();
  }
  void printPortalInstructions() const {
    Serial.printf("[setup] 1. Join Wi-Fi: %s | use the hotspot password you chose in USB setup (not printed)\n", setupName().c_str());
    Serial.println("[setup] 2. Stay connected when warned 'No internet'. Open http://192.168.4.1/ manually if no page appears (HTTP, not HTTPS)");
    Serial.println("[setup] 3. Match the board ID. Enter YOUR 2.4 GHz Wi-Fi name and password in that page, then click Save and restart");
    Serial.println("[setup]    Use WPA2 Personal, or explicitly select Open network if there is no password. No 5 GHz-only or enterprise/captive-login Wi-Fi");
    Serial.println("[setup] 4. Wait up to 30 seconds. Saved ONLY on success. Wrong details? Rejoin the same setup hotspot and try again");
    Serial.println("[setup] 5. On success the board restarts. Leave BOOT released; rejoin your usual Wi-Fi, then use the [ready] recording IP in Android/web");
    Serial.println("[setup] If configuring this phone's own hotspot: save first, then turn its 2.4 GHz/WPA2 hotspot ON immediately");
    Serial.println("[setup] This page is local to the board. Do NOT enter your router password in Serial Monitor or share it in screenshots");
  }
  void startJoin(const WifiCredentials& value) {
    candidate = value; joining = true; joinStarted = millis();
    portalState = "joining"; portalMessage = "Checking Wi-Fi (up to 30 seconds). If configuring this phone's hotspot, enable it now. Reconnect to the setup hotspot to check a failure.";
    // Keep the web portal alive during HTTP setup so a bad password can be retried.
    WiFi.mode(active ? WIFI_AP_STA : WIFI_STA); WiFi.setAutoReconnect(false);
    WiFi.disconnect(); WiFi.setSleep(false);
    WiFi.setMinSecurity(candidate.password[0] ? WIFI_AUTH_WPA2_PSK : WIFI_AUTH_OPEN);
    WiFi.begin(candidate.ssid, candidate.password);
    Serial.println("[setup] Checking submitted Wi-Fi for up to 30 seconds; no credentials are printed or saved yet");
    Serial.println("[setup] The setup hotspot may briefly move channel while joining. If its page disconnects, reconnect to your named GLYPH hotspot");
  }
  void startPortal() {
    WiFi.setAutoReconnect(false); WiFi.disconnect(); WiFi.mode(WIFI_AP_STA);
    const String name = setupName();
    const IPAddress setupIp(192, 168, 4, 1);
    if (!WiFi.softAPConfig(setupIp, setupIp, IPAddress(255, 255, 255, 0)) || !WiFi.softAP(name.c_str(), profile.password)) {
      Serial.println("[setup] Could not start configuration hotspot. Reset and retry; no settings changed");
      return;
    }
    active = true;
    portalState = "ready";
    portalMessage = "Enter your ROUTER or PHONE HOTSPOT Wi-Fi details below, not the board hotspot you just named.";
    token = String(esp_random(), HEX) + String(esp_random(), HEX);
    dns.start(53, "*", WiFi.softAPIP());
    if (!routesInstalled) {
    routesInstalled = true;
    server.on("/", HTTP_GET, [this]() {
      server.sendHeader("Cache-Control", "no-store");
      // Interpolated labels are restricted to ASCII letters/digits/-/_. Other
      // values are fixed messages, hex board IDs or tokens;
      // never reflect an SSID/password into HTML or the status response.
      server.send(200, "text/html", String(F(R"HTML(<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>GLYPH VOICE Wi-Fi setup</title>
<style>:root{color-scheme:light dark}body{font:17px system-ui;max-width:32em;margin:2em auto;padding:1em;line-height:1.5}h1{line-height:1.1}input,button{font:inherit;box-sizing:border-box}input:not([type=checkbox]):not([type=hidden]),button{display:block;width:100%;padding:.8em;margin:.4em 0 1em;border:1px solid #888;border-radius:.6em}button{background:#c3ef9a;color:#152b24;font-weight:bold;cursor:pointer}label{display:block;margin-top:1em}input[type=checkbox]{width:1.2em;height:1.2em;margin-right:.5em}#status{padding:1em;border:1px solid #888;border-radius:.6em}small{display:block}button:disabled{opacity:.5}</style>
<h1>Connect your Glyph to Wi-Fi</h1><p>Your board hotspot: <strong>)HTML")) + setupName() + F(R"HTML(</strong></p>
<p>Match this ID to your USB Serial Monitor. Choose the router or phone hotspot that your app/computer will use.</p>
<p id="status" role="status" aria-live="polite">)HTML") + portalMessage + F(R"HTML(</p>
<form method="post" action="/save"><label for="ssid">Wi-Fi name (SSID)</label>
<input id="ssid" name="ssid" placeholder="Your Wi-Fi name" maxlength="32" autocomplete="off" autocapitalize="none" spellcheck="false" required>
<label for="password">Wi-Fi password</label><input id="password" name="password" type="password" minlength="8" maxlength="63" autocomplete="new-password">
<label><input id="open" name="open" type="checkbox" value="1">Open network (no password)</label>
<small>Only select this for a trusted open network. WPA2 Personal is recommended. Protected networks need 8–63 printable ASCII password characters.</small>
<input name="token" type="hidden" value=")HTML") + token + F(R"HTML("><button id="save" type="submit">Save and restart</button></form>
<p><strong>2.4 GHz only.</strong> Wi-Fi names are case-sensitive (maximum 32 UTF-8 bytes). 5 GHz-only, enterprise Wi-Fi and networks requiring a web login are not supported.</p>
<p>Settings are saved on this board only after it connects. Then it restarts: leave BOOT released and rejoin your normal Wi-Fi. Enter the new <strong>[ready] recording IP</strong> in the Android/web app, not this setup-page address.</p>
<p>If this same phone will host the target hotspot, save first, then turn its 2.4 GHz/WPA2 hotspot on immediately. Using a separate computer/phone for setup is easier.</p>
<p>No internet is needed for this page. It may disconnect while joining: check USB Serial Monitor or rejoin this exact setup hotspot if the attempt fails. Configure on a trusted network near your own board.</p>
<script>const openBox=document.getElementById('open'),password=document.getElementById('password');
function security(){password.disabled=openBox.checked;password.required=!openBox.checked;if(openBox.checked)password.value='';}openBox.addEventListener('change',security);security();
let polling=false;setInterval(async()=>{if(polling)return;polling=true;try{const r=await fetch('/status',{cache:'no-store'});if(!r.ok)return;const s=await r.json();document.getElementById('status').textContent=s.message+(s.ip?' Recording address: '+s.ip+':8080':'');document.getElementById('save').disabled=['queued','joining','restarting'].includes(s.state);}catch{}finally{polling=false}},1000);</script></html>)HTML"));
    });
    server.on("/status", HTTP_GET, [this]() {
      server.sendHeader("Cache-Control", "no-store");
      const String ip = restartPending ? WiFi.localIP().toString() : "";
      // State/message are fixed firmware strings, never SSIDs/passwords or user HTML.
      server.send(200, "application/json", String("{\"state\":\"") + portalState + "\",\"message\":\"" + portalMessage + "\",\"ip\":\"" + ip + "\"}");
    });
    server.on("/save", HTTP_POST, [this]() {
      if (server.arg("token") != token || busy()) { server.send(403, "text/plain", "Reopen setup and retry."); return; }
      const String ssid = server.arg("ssid"), password = server.arg("password");
      const bool openNetwork = server.arg("open") == "1";
      if (!validWifiCredentials(ssid.c_str(), ssid.length(), password.c_str(), password.length(), openNetwork)) {
        server.sendHeader("Cache-Control", "no-store");
        server.send(400, "text/plain", "Use a 1-32 byte Wi-Fi name and 8-63 printable ASCII password, or explicitly select Open network with no password. Go back and correct the form.");
        return;
      }
      ssid.toCharArray(candidate.ssid, sizeof(candidate.ssid)); password.toCharArray(candidate.password, sizeof(candidate.password));
      server.sendHeader("Cache-Control", "no-store");
      portalQueued = true; portalQueuedAt = millis();
      portalState = "queued"; portalMessage = "Checking your Wi-Fi settings. Enable the target hotspot now if needed. Successful setup saves settings and restarts automatically.";
      server.sendHeader("Location", "/"); server.send(303, "text/plain", "Checking Wi-Fi; return to setup page for status.");
    });
    server.onNotFound([this]() { server.sendHeader("Location", "http://192.168.4.1/"); server.send(302, "text/plain", "Open setup"); });
    }
    server.begin();
    Serial.println("[setup] CONFIGURATION HOTSPOT READY - not the audio recording network");
    printStatus();
  }
};
