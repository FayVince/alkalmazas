# BLE Ellenállás Mérő Android Alkalmazás

Android alkalmazás amely Bluetooth LE-n keresztül fogad ellenállás mérési adatokat egy kesztyűből, GPS koordinátákkal együtt naplózza őket, és térképen megjeleníti.

## Funkciók

- **BLE Kommunikáció**: Standard BLE Notify karakterisztikán keresztül fogadja az ellenállás értékeket
- **Adatfeldolgozás**: Az utolsó n darab mérés lineáris középértékének számítása
- **GPS Naplózás**: B másodpercenként GPS koordinátákkal együtt menti a méréseket
- **Fájl kezelés**: JSON formátumban menti az adatokat a Documents mappába
- **Térkép megjelenítés**: Google Maps-en jeleníti meg a mérési pontokat színkódolással
- **Demo mód**: Szimulált adatokat generál BLE eszköz nélküli teszteléshez

## Telepítés

1. Klónozd le a repository-t
2. Nyisd meg Android Studio-ban
3. Állítsd be a Google Maps API kulcsot a `local.properties` fájlban:
   ```
   MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY_HERE
   ```
4. Build és futtasd az alkalmazást

## Használat

### Főképernyő
- **N paraméter**: 1-99 közötti érték - hány mérést átlagolja (billentyűzettel szerkeszthető)
- **B paraméter**: 1-999 közötti érték másodpercben - milyen gyakran menti az átlagot (billentyűzettel szerkeszthető)
- **Demo Mode**: Kapcsoló a szimulált adatok generálásához
- **Start/Stop**: Mérés indítása/megállítása

### MeasurementService (Háttérszolgáltatás)
- **Előtérszolgáltatás**: Folyamatos értesítéssel és GPS frissítéssel fut
- **Értesítés**: "Kesztyű mérés fut" címmel, futási időt mutatja másodpercekben
- **GPS frissítés**: ~1 másodpercenként kéri a helyadatokat
- **Helyadat kezelés**: 0/0 koordinátákat ignorálja, utolsó érvényes pozíciót használja
- **Mozgó átlag**: Max 99 mintát tárol ArrayDeque-ban
- **Paraméter változások**: N és B értékek futás közben is módosíthatók, JSON-ba logolva

### BLE Integráció
A BLE kód a következő módon hívhatja meg a `MeasurementService.onBleMeasurement(rawValue: Int)` metódust:

**1. Service Binding módszer (ajánlott):**
```kotlin
// BLE Repository vagy Service-ben
private var measurementService: MeasurementService? = null
private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val localBinder = binder as MeasurementService.LocalBinder
        measurementService = localBinder.getService()
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        measurementService = null
    }
}

// BLE mérés érkezésekor:
measurementService?.onBleMeasurement(rawValue)
```

**2. Broadcast Intent módszer:**
```kotlin
// Alternatíva: Intent küldése
val intent = Intent("com.ble.resistancemeter.action.BLE_MEASUREMENT")
intent.setPackage(packageName)
intent.putExtra("raw_value", rawValue)
sendBroadcast(intent)
```

**3. Demo/Teszt módszer:**
```kotlin
// Direkt hívás teszteléshez (ha van referencia a Service-re)
measurementService.onBleMeasurement(randomValue)
```

### Térkép képernyő
- Megjeleníti a mérési pontokat
- Színkódolás: kék (alacsony) → zöld → sárga → piros (magas)
- Lehetőség korábbi mérési fájlok betöltésére

## Technikai részletek

- **Nyelv**: Kotlin
- **Minimum SDK**: API 21 (Android 5.0)
- **Target SDK**: API 35 (Android 16 kompatibilis)
- **Architektúra**: MVVM pattern
- **BLE UUID**: `0000ffe1-0000-1000-8000-00805f9b34fb`
- **Adat formátum**: Low-High byte (2 byte integer)

## Fájl formátum

A mérések JSON formátumban kerülnek mentésre:

```json
{
  "startTime": "2025-12-10T14:30:00",
  "measurements": [
    {
      "timestamp": "2025-12-10T14:30:05",
      "value": 1234.5,
      "latitude": 47.4979,
      "longitude": 19.0402
    }
  ],
  "parameterChanges": [
    {
      "timestamp": "2025-12-10T14:35:00",
      "n": 10,
      "B": 5
    }
  ]
}
```

## Jogosultságok

Az alkalmazás a következő jogosultságokat kéri:
- Bluetooth (BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
- Helymeghatározás (ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
- Háttér szolgáltatás (FOREGROUND_SERVICE_LOCATION)

## Licenc

MIT License