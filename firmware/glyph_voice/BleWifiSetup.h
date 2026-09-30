#pragma once
#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLESecurity.h>
#include <atomic>
#include "ProvisioningFrame.h"

// Dedicated setup service. UUIDs must match the Java and web clients.
namespace GlyphBle {
constexpr char SERVICE[] = "c8c0f100-7d8c-4b9e-9a26-12f467a3e001";
constexpr char STATUS[]  = "c8c0f101-7d8c-4b9e-9a26-12f467a3e001";
constexpr char COMMAND[] = "c8c0f102-7d8c-4b9e-9a26-12f467a3e001";
}

class BleWifiSetup : public BLEServerCallbacks, public BLECharacteristicCallbacks, public BLESecurityCallbacks {
 public:
  std::atomic<bool> running{false};
  bool begin(const String& id, uint32_t pin) {
    boardId = id;
    if (!BLEDevice::init("GLYPH-" + id.substring(6))) return false;
    BLESecurity::setAuthenticationMode(true, true, true); // Bond + MITM + LE Secure Connections.
    BLESecurity::setCapability(ESP_IO_CAP_OUT); // PIN comes from the board's USB/kit label, never GATT.
    BLESecurity::setKeySize(16);
    BLESecurity::setPassKey(true, pin);
    BLEDevice::setSecurityCallbacks(this);
    server = BLEDevice::createServer(); server->setCallbacks(this);
    BLEService* service = server->createService(GlyphBle::SERVICE);
    status = service->createCharacteristic(GlyphBle::STATUS, BLECharacteristic::PROPERTY_READ
        | BLECharacteristic::PROPERTY_READ_ENC | BLECharacteristic::PROPERTY_READ_AUTHEN);
    command = service->createCharacteristic(GlyphBle::COMMAND, BLECharacteristic::PROPERTY_WRITE
        | BLECharacteristic::PROPERTY_WRITE_ENC | BLECharacteristic::PROPERTY_WRITE_AUTHEN);
#if defined(CONFIG_BLUEDROID_ENABLED)
    status->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM);
    command->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);
#endif
    status->setCallbacks(this); command->setCallbacks(this);
    update("ready", "", "");
    service->start();
    BLEAdvertising* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(GlyphBle::SERVICE);
    advertising->setScanResponse(true);
    running = true; accepting.store(true); openedAt = millis(); advertising->start();
    Serial.printf("[setup] BLE GLYPH-%s enabled for 5 minutes. Pairing PIN: %06lu (keep private; use on kit label)\n",
                  boardId.substring(6).c_str(), static_cast<unsigned long>(pin));
    return true;
  }
  void update(const char* state, const String& ip, const char* error) {
    char value[224] = {};
    snprintf(value, sizeof(value), "{\"version\":1,\"id\":\"%s\",\"state\":\"%s\",\"ip\":\"%s\",\"port\":8080,\"path\":\"/audio\",\"error\":\"%s\"}",
             boardId.c_str(), state, ip.c_str(), error);
    portENTER_CRITICAL(&mux); memcpy(snapshot, value, sizeof(snapshot)); portEXIT_CRITICAL(&mux);
  }
  bool takeCredentials(WifiCredentials& value) {
    bool result = false;
    portENTER_CRITICAL(&mux);
    if (pending) { value = credentials; memset(&credentials, 0, sizeof(credentials)); pending = false; result = true; }
    portEXIT_CRITICAL(&mux); return result;
  }
  void allowCredentials(bool allow) { accepting.store(allow); }
  bool reserveCredentials() {
    portENTER_CRITICAL(&mux);
    const bool reserved = accepting.exchange(false);
    if (reserved) frame.reset();
    portEXIT_CRITICAL(&mux); return reserved;
  }
  bool packetError() { return badPacket.exchange(false); }
  void poll() {
    if (!running) return;
    // Only a physical re-entry/reboot extends this finite provisioning window.
    if (millis() - openedAt >= 300000) { stop(); return; }
    if (restartAdvertising.exchange(false)) BLEDevice::startAdvertising();
  }
  void stop() {
    if (!running) return;
    accepting.store(false); running = false;
    BLEDevice::deinit(true); // Return controller/host RAM to audio; re-entry uses reboot.
    portENTER_CRITICAL(&mux); frame.reset(); memset(&credentials, 0, sizeof(credentials)); pending = false; portEXIT_CRITICAL(&mux);
    Serial.println("[setup] BLE closed; restart or hold BOOT while no audio client is connected to reopen");
  }
#if defined(CONFIG_NIMBLE_ENABLED)
  void onConnect(BLEServer* owner, ble_gap_conn_desc* desc) override {
    uint16_t unowned = UINT16_MAX;
    if (!connection.compare_exchange_strong(unowned, desc->conn_handle)) { owner->disconnect(desc->conn_handle); return; }
    authenticated.store(false);
    portENTER_CRITICAL(&mux); frame.reset(); portEXIT_CRITICAL(&mux);
  }
  void onDisconnect(BLEServer*, ble_gap_conn_desc* desc) override {
    if (connection.load() != desc->conn_handle) return;
    connection.store(UINT16_MAX); authenticated.store(false);
    portENTER_CRITICAL(&mux); frame.reset(); portEXIT_CRITICAL(&mux);
    if (running) restartAdvertising.store(true);
  }
#else
#error "Glyph BLE provisioning requires the ESP32-C6 NimBLE build of Arduino ESP32 3.3.10"
#endif
  bool onSecurityRequest() override { return running; }
  void onPassKeyNotify(uint32_t) override {} // Already shown by begin(); never log credentials from BLE callbacks.
  bool onConfirmPIN(uint32_t) override { return false; } // No unauthenticated Just Works fallback.
#if defined(CONFIG_NIMBLE_ENABLED)
  void onAuthenticationComplete(ble_gap_conn_desc* desc) override {
    const bool secure = desc->sec_state.encrypted && desc->sec_state.authenticated && desc->sec_state.key_size == 16;
    if (connection.load() == desc->conn_handle) authenticated.store(secure);
    if (!secure && server) server->disconnect(desc->conn_handle);
  }
#else
  void onAuthenticationComplete(esp_ble_auth_cmpl_t desc) override { authenticated.store(desc.success); }
#endif
  void onRead(BLECharacteristic* characteristic) override {
    if (characteristic != status) return;
    char value[224]; portENTER_CRITICAL(&mux); memcpy(value, snapshot, sizeof(value)); portEXIT_CRITICAL(&mux);
    characteristic->setValue(String(value));
  }
  void onWrite(BLECharacteristic* characteristic) override {
    if (characteristic != command) return;
    String value = characteristic->getValue();
    bool committed = false;
    if (authenticated.load() && accepting.load()) {
      portENTER_CRITICAL(&mux);
      if (accepting.load() && !pending) {
        const auto result = frame.accept(reinterpret_cast<const uint8_t*>(value.c_str()), value.length(), credentials);
        if (result == ProvisioningFrame::Complete) { pending = true; accepting.store(false); committed = true; }
        if (result == ProvisioningFrame::Invalid) badPacket.store(true);
      }
      portEXIT_CRITICAL(&mux);
    } // Ignore additional writes while a committed transaction is joining Wi-Fi.
    if (committed) update("queued", "", "");
    if (value.length()) memset(const_cast<char*>(value.c_str()), 0, value.length());
    characteristic->setValue("");
  }
 private:
  BLEServer* server = nullptr;
  BLECharacteristic *status = nullptr, *command = nullptr;
  String boardId;
  uint32_t openedAt = 0;
  std::atomic<bool> authenticated{false}, accepting{false}, restartAdvertising{false}, badPacket{false};
  std::atomic<uint16_t> connection{UINT16_MAX};
  portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;
  ProvisioningFrame frame;
  WifiCredentials credentials = {};
  bool pending = false;
  char snapshot[224] = {};
};
