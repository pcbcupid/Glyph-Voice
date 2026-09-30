#pragma once
#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <Preferences.h>
#include "BleWifiSetup.h"

// BLE handles setup only; microphone PCM still travels over Wi-Fi.
class WifiSetup {
 public:
  bool active = false;
  String boardId;
  void begin() {
    WiFi.persistent(false); // Our validated, atomic NVS record is the only credential store.
    WiFi.mode(WIFI_STA); WiFi.setSleep(false); WiFi.setAutoReconnect(true);
    boardId = WiFi.macAddress(); boardId.replace(":", "");
    prefs.begin("glyph-wifi", false);
    uint32_t pin = prefs.getUInt("ble-pin", 0);
    if (pin < 100000 || pin > 999999) {
      pin = 100000 + esp_random() % 900000;
      if (!prefs.putUInt("ble-pin", pin)) pin = 0;
    }
    if (!pin || !ble.begin(boardId, pin)) Serial.println("[setup] BLE unavailable; use Wi-Fi setup portal");
    WifiCredentials saved = {};
    if (prefs.getBytesLength("wifi-v2") == sizeof(saved)) prefs.getBytes("wifi-v2", &saved, sizeof(saved));
    else {
      prefs.getString("ssid", "").toCharArray(saved.ssid, sizeof(saved.ssid));
      prefs.getString("password", "").toCharArray(saved.password, sizeof(saved.password));
    }
    saved.ssid[32] = 0; saved.password[63] = 0;
    const bool forcePortal = prefs.getBool("setup", false); prefs.remove("setup");
    if (!saved.ssid[0] || forcePortal) startPortal();
    else { WiFi.begin(saved.ssid, saved.password); Serial.println("[network] Joining saved 2.4 GHz Wi-Fi"); }
    memset(&saved, 0, sizeof(saved));
  }
  void poll(bool noAudioClient) {
    ble.poll();
    if (ble.packetError() && !joining) { attemptedJoin = true; ble.update("failed", "", "packet"); }
    WifiCredentials requested = {};
    if (ble.takeCredentials(requested)) { startJoin(requested); memset(&requested, 0, sizeof(requested)); }
    if (portalQueued) { portalQueued = false; startJoin(candidate); }
    if (joining) {
      if (millis() - joinStarted >= 1000 && WiFi.status() == WL_CONNECTED && WiFi.SSID() == candidate.ssid
          && WiFi.localIP() != IPAddress(0, 0, 0, 0)) {
        const bool saved = prefs.putBytes("wifi-v2", &candidate, sizeof(candidate)) == sizeof(candidate);
        memset(&candidate, 0, sizeof(candidate)); joining = false;
        ble.update(saved ? "connected" : "failed", saved ? WiFi.localIP().toString() : "", saved ? "" : "storage");
        ble.allowCredentials(true);
        if (saved) { attemptedJoin = false; prefs.remove("ssid"); prefs.remove("password"); Serial.println("[setup] Wi-Fi connected; credentials saved (never logged)"); }
      } else if (millis() - joinStarted >= 30000) {
        joining = false; WiFi.disconnect(); memset(&candidate, 0, sizeof(candidate));
        ble.update("failed", "", "network"); ble.allowCredentials(true);
        Serial.println("[setup] Could not join Wi-Fi; retry credentials. Previous saved network is unchanged");
      }
    } else if (ble.running && WiFi.status() == WL_CONNECTED && WiFi.localIP() != IPAddress(0, 0, 0, 0)) {
      if (!attemptedJoin) ble.update("connected", WiFi.localIP().toString(), "");
    } else if (ble.running && !attemptedJoin) {
      ble.update("ready", "", "");
    }
    if (active) { dns.processNextRequest(); server.handleClient(); }
    if (noAudioClient && !joining && digitalRead(Config::RECORD_BUTTON) == LOW) {
      if (!heldSince) heldSince = millis();
      if (millis() - heldSince >= 5000) {
        prefs.putBool("setup", true); Serial.println("[setup] Rebooting into BLE / Wi-Fi setup"); ESP.restart();
      }
    } else heldSince = 0;
  }
  void audioConnected() { ble.stop(); }
  bool busy() const { return joining; }
 private:
  Preferences prefs;
  BleWifiSetup ble;
  WebServer server{80};
  DNSServer dns;
  uint32_t heldSince = 0, joinStarted = 0;
  bool joining = false, attemptedJoin = false, portalQueued = false;
  WifiCredentials candidate = {};
  String token;
  void startJoin(const WifiCredentials& value) {
    candidate = value; joining = attemptedJoin = true; joinStarted = millis();
    ble.allowCredentials(false); ble.update("joining", "", "");
    if (active) { server.stop(); dns.stop(); WiFi.softAPdisconnect(true); active = false; }
    WiFi.mode(WIFI_STA); WiFi.disconnect(); WiFi.setSleep(false); WiFi.begin(candidate.ssid, candidate.password);
  }
  void startPortal() {
    active = true; WiFi.disconnect(); WiFi.mode(WIFI_AP_STA);
    const String name = "GLYPH-Setup-" + boardId.substring(6);
    WiFi.softAP(name.c_str(), "glyphvoice");
    token = String(esp_random(), HEX) + String(esp_random(), HEX);
    dns.start(53, "*", WiFi.softAPIP());
    server.on("/", HTTP_GET, [this]() {
      server.sendHeader("Cache-Control", "no-store");
      server.send(200, "text/html", String(F("<!doctype html><meta name='viewport' content='width=device-width'><title>GLYPH VOICE setup</title><style>body{font:18px system-ui;max-width:32em;margin:3em auto;padding:1em}input,button{display:block;font:inherit;margin:1em 0;padding:.6em;width:90%}</style><h1>GLYPH VOICE</h1><p>Bluetooth setup is available in the Android app and supported HTTPS browsers. This page is the fallback for iPhone Safari.</p><p>Enter your 2.4 GHz Wi-Fi or phone hotspot details. Enable the hotspot after saving.</p><form method='post' action='/save'><input name='ssid' aria-label='Wi-Fi name' placeholder='Wi-Fi name' maxlength='32' required><input name='password' aria-label='Wi-Fi password' placeholder='Wi-Fi password' type='password' minlength='8' maxlength='63' required><input name='token' type='hidden' value='")) + token + F("'><button>Connect Glyph</button></form>"));
    });
    server.on("/save", HTTP_POST, [this]() {
      if (server.arg("token") != token || joining || portalQueued) { server.send(403, "text/plain", "Reopen setup and retry."); return; }
      const String ssid = server.arg("ssid"), password = server.arg("password");
      bool valid = ssid.length() >= 1 && ssid.length() <= 32 && password.length() >= 8 && password.length() <= 63;
      for (unsigned i = 0; i < password.length(); ++i) valid &= password[i] >= 32 && password[i] <= 126;
      for (unsigned i = 0; i < ssid.length(); ++i) valid &= ssid[i] != 0;
      if (!valid) { server.send(400, "text/plain", "Use a 1-32 byte Wi-Fi name and 8-63 printable ASCII password."); return; }
      if (ble.running && !ble.reserveCredentials()) { server.send(409, "text/plain", "Bluetooth setup is already in progress."); return; }
      ssid.toCharArray(candidate.ssid, sizeof(candidate.ssid)); password.toCharArray(candidate.password, sizeof(candidate.password));
      server.sendHeader("Cache-Control", "no-store");
      server.send(200, "text/html", "<h1>Connecting</h1><p>Leave this setup network and enable your hotspot now. Credentials are saved only if the connection succeeds within 30 seconds. If it fails, hold BOOT for five seconds to reopen setup.</p>");
      portalQueued = true;
    });
    server.onNotFound([this]() { server.sendHeader("Location", "http://192.168.4.1/"); server.send(302, "text/plain", "Open setup"); });
    server.begin();
    Serial.printf("[setup] Fallback: join %s (password glyphvoice), open http://192.168.4.1\n", name.c_str());
  }
};
