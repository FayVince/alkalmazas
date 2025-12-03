/*
 * ESP32-C3 BLE Ellenállás Mérő
 * Kompatibilis a FayVince/alkalmazas Android alkalmazással
 * 
 * BLE Service UUID: 0000ffe0-0000-1000-8000-00805f9b34fb
 * BLE Characteristic UUID: 0000ffe1-0000-1000-8000-00805f9b34fb
 * 
 * Adat formátum: 2 byte (Low byte first, High byte second)
 * Küldési frekvencia: 1 másodpercenként
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// BLE UUID-k - megegyeznek az Android app-ban használtakkal
#define SERVICE_UUID        "0000ffe0-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_UUID "0000ffe1-0000-1000-8000-00805f9b34fb"

// Analóg bemenet pin az ellenállás méréshez
#define ANALOG_PIN 0  // GPIO0 az ESP32-C3-n (ADC1_CH0)

// BLE változók
BLEServer* pServer = NULL;
BLECharacteristic* pCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Időzítés
unsigned long previousMillis = 0;
const long interval = 1000;  // 1 másodperces intervallum

// Callback osztály a kapcsolat állapotának kezelésére
class MyServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        Serial.println("Eszköz csatlakozva!");
    }

    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        Serial.println("Eszköz lecsatlakozva!");
    }
};

void setup() {
    Serial.begin(115200);
    Serial.println("ESP32-C3 BLE Ellenállás Mérő indítása...");

    // Analóg bemenet beállítása
    analogReadResolution(12);  // 12-bit felbontás (0-4095)
    analogSetAttenuation(ADC_11db);  // 0-3. 3V tartomány

    // BLE inicializálás
    BLEDevice::init("BLE_Resistance_Meter");

    // BLE szerver létrehozása
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    // BLE szolgáltatás létrehozása
    BLEService* pService = pServer->createService(SERVICE_UUID);

    // BLE karakterisztika létrehozása NOTIFY tulajdonsággal
    pCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_NOTIFY
    );

    // Descriptor hozzáadása a NOTIFY-hoz (CCCD - Client Characteristic Configuration Descriptor)
    pCharacteristic->addDescriptor(new BLE2902());

    // Szolgáltatás indítása
    pService->start();

    // Hirdetés indítása
    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    pAdvertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();

    Serial.println("BLE szerver elindítva, várakozás kapcsolatra...");
    Serial.println("Eszköz neve: BLE_Resistance_Meter");
}

void loop() {
    unsigned long currentMillis = millis();

    // Másodpercenként küld adatot ha van csatlakozott eszköz
    if (currentMillis - previousMillis >= interval) {
        previousMillis = currentMillis;

        if (deviceConnected) {
            // Ellenállás érték olvasása (analóg bemenetről)
            uint16_t resistanceValue = readResistance();

            // 2 byte-os adat előkészítése (Low byte first - Little Endian)
            uint8_t data[2];
            data[0] = resistanceValue & 0xFF;         // Low byte
            data[1] = (resistanceValue >> 8) & 0xFF;  // High byte

            // Adat küldése BLE-n keresztül
            pCharacteristic->setValue(data, 2);
            pCharacteristic->notify();

            Serial.print("Küldött érték: ");
            Serial.print(resistanceValue);
            Serial.print(" (0x");
            Serial.print(data[1], HEX);
            Serial.print(data[0], HEX);
            Serial.println(")");
        }
    }

    // Újracsatlakozás kezelése
    if (! deviceConnected && oldDeviceConnected) {
        delay(500);  // Várakozás a Bluetooth stack rendezésére
        pServer->startAdvertising();
        Serial.println("Hirdetés újraindítva...");
        oldDeviceConnected = deviceConnected;
    }

    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
    }
}

// Ellenállás érték olvasása
// Módosítsd ezt a függvényt a konkrét szenzor/áramkör szerint! 
uint16_t readResistance() {
    // Analóg érték olvasása (0-4095 12-bit)
    int analogValue = analogRead(ANALOG_PIN);
    
    // Példa: direkt ADC érték használata
    // Ha feszültségosztóval mérsz ellenállást, itt számold át
    // Például: R_mérő = R_ismert * (Vcc/Vmért - 1)
    
    // Az Android app 500-2000 közötti értékeket vár a demo módban
    // Skálázd az értéket a szükséges tartományra
    uint16_t resistanceValue = map(analogValue, 0, 4095, 0, 4095);
    
    return resistanceValue;
}
