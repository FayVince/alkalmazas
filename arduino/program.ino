/*
 * ESP32-C3 BLE Ellenállás Mérő (NimBLE - API kompatibilis, egyszerű)
 *
 * Service UUID:        0000ffe0-0000-1000-8000-00805f9b34fb
 * Characteristic UUID: 0000ffe1-0000-1000-8000-00805f9b34fb
 *
 * 2 byte little-endian notify, 1 mp-enként
 */

#include <Arduino.h>
#include <NimBLEDevice.h>

static const char* DEVICE_NAME         = "BLE_Resistance_Meter";
static const char* SERVICE_UUID        = "0000ffe0-0000-1000-8000-00805f9b34fb";
static const char* CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb";

#define ANALOG_PIN 0   // ESP32-C3 GPIO0 (ADC1_CH0)

static NimBLEServer* pServer = nullptr;
static NimBLECharacteristic* pCharacteristic = nullptr;

static bool deviceConnected = false;
static unsigned long lastSend = 0;
static const unsigned long SEND_INTERVAL_MS = 1000;

class MyServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& info) override {
    deviceConnected = true;
    Serial.print("Csatlakozott: ");
    Serial.println(info.getAddress().toString().c_str());
  }

  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& info, int reason) override {
    deviceConnected = false;
    Serial.print("Lecsatlakozott, reason=");
    Serial.println(reason);

    delay(150);
    NimBLEDevice::startAdvertising();
    Serial.println("Advertising újraindítva");
  }
};

uint16_t readResistance() {
  int adc = analogRead(ANALOG_PIN);
  if (adc < 0) adc = 0;
  if (adc > 4095) adc = 4095;
  return (uint16_t)adc;
}

void startAdvertising() {
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->stop();

  NimBLEAdvertisementData ad;
  ad.setFlags(0x06); // LE General Discoverable + BR/EDR not supported
  ad.setCompleteServices(NimBLEUUID(SERVICE_UUID));

  NimBLEAdvertisementData sr;
  sr.setName(DEVICE_NAME);

  adv->setAdvertisementData(ad);
  adv->setScanResponseData(sr);

  adv->start();
  Serial.println("Advertising elindítva");
}

void startBle() {
  NimBLEDevice::init(DEVICE_NAME);

  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  NimBLEService* service = pServer->createService(SERVICE_UUID);

  // READ + WRITE + NOTIFY (app kompatibilisebb)
  pCharacteristic = service->createCharacteristic(
    CHARACTERISTIC_UUID,
    NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY
  );

  uint8_t initData[2] = {0x00, 0x00};
  pCharacteristic->setValue(initData, 2);

  service->start();

  startAdvertising();
}

void setup() {
  Serial.begin(115200);
  delay(200);

  Serial.println("ESP32-C3 BLE Ellenállás Mérő (NimBLE)");

  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);

  startBle();
}

void loop() {
  unsigned long now = millis();

  if (deviceConnected && (now - lastSend >= SEND_INTERVAL_MS)) {
    lastSend = now;

    uint16_t value = readResistance();

    uint8_t data[2];
    data[0] = (uint8_t)(value & 0xFF);
    data[1] = (uint8_t)((value >> 8) & 0xFF);

    pCharacteristic->setValue(data, 2);
    pCharacteristic->notify();

    Serial.print("Küldve: ");
    Serial.println(value);
  }

  delay(10);
}
