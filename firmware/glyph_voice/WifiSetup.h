#pragma once
#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <Preferences.h>

// Provision on a temporary WPA2 setup network; public binaries contain no owner's credentials.
class WifiSetup {
 public:
  bool active = false;
  String boardId;
  void begin() {
    WiFi.mode(WIFI_STA);
    boardId = WiFi.macAddress(); boardId.replace(":", "");
    prefs.begin("glyph-wifi", false);
    const String ssid = prefs.getString("ssid", "");
    if (ssid.isEmpty()) { startPortal(); return; }
    WiFi.setSleep(false);
    WiFi.setAutoReconnect(true);
    WiFi.begin(ssid.c_str(), prefs.getString("password", "").c_str());
    Serial.println("[network] Joining saved hotspot (2.4 GHz)");
  }
  void poll(bool disconnected) {
    if (active) {
      dns.processNextRequest(); server.handleClient();
      if (restartAt && static_cast<int32_t>(millis() - restartAt) >= 0) ESP.restart();
      return;
    }
    // Hold BOOT after startup while disconnected to change hotspot credentials.
    if (disconnected && digitalRead(Config::RECORD_BUTTON) == LOW) {
      if (!heldSince) heldSince = millis();
      if (millis() - heldSince >= 5000) startPortal();
    } else heldSince = 0;
  }
 private:
  Preferences prefs;
  WebServer server{80};
  DNSServer dns;
  uint32_t heldSince = 0, restartAt = 0;
  String token;
  void startPortal() {
    active = true;
    WiFi.disconnect(); WiFi.mode(WIFI_AP);
    const String name = "GLYPH-Setup-" + boardId.substring(6);
    WiFi.softAP(name.c_str(), "glyphvoice");
    token = String(esp_random(), HEX) + String(esp_random(), HEX);
    dns.start(53, "*", WiFi.softAPIP());
    server.on("/", HTTP_GET, [this]() {
      server.sendHeader("Cache-Control", "no-store");
      server.send(200, "text/html", String(F("<!doctype html><meta name='viewport' content='width=device-width'><title>GLYPH VOICE setup</title><style>body{font:18px system-ui;max-width:32em;margin:3em auto;padding:1em}input,button{display:block;font:inherit;margin:1em 0;padding:.6em;width:90%}</style><h1>GLYPH VOICE</h1><p>Enter your phone hotspot name and password. Use 2.4 GHz / WPA2 compatibility mode.</p><form method='post' action='/save'><input name='ssid' aria-label='Hotspot name' placeholder='Hotspot name' maxlength='32' required><input name='password' aria-label='Hotspot password' placeholder='Hotspot password' type='password' minlength='8' maxlength='63' required><input name='token' type='hidden' value='")) + token + F("'><button>Save and restart</button></form><p>After saving, leave this setup network, enable your phone hotspot, then tap Find Glyph in the app.</p>"));
    });
    server.on("/save", HTTP_POST, [this]() {
      if (server.arg("token") != token) { server.send(403, "text/plain", "Reopen setup and retry."); return; }
      const String ssid = server.arg("ssid"), password = server.arg("password");
      if (ssid.length() < 1 || ssid.length() > 32 || password.length() < 8 || password.length() > 63) {
        server.send(400, "text/plain", "Check hotspot name (1-32 bytes) and password (8-63 bytes)."); return;
      }
      if (!prefs.putString("password", password) || !prefs.putString("ssid", ssid)) {
        server.send(500, "text/plain", "Could not save settings. Reset and retry."); return;
      }
      server.sendHeader("Cache-Control", "no-store");
      server.send(200, "text/html", "<h1>Saved</h1><p>Enable your phone hotspot, then open GLYPH VOICE and tap Find Glyph. This board is restarting.</p>");
      restartAt = millis() + 1000;
    });
    server.onNotFound([this]() { server.sendHeader("Location", "http://192.168.4.1/"); server.send(302, "text/plain", "Open setup"); });
    server.begin();
    Serial.printf("[setup] Join %s (password glyphvoice), then open http://192.168.4.1\n", name.c_str());
  }
};
