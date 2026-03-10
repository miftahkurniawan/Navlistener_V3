# NavListener — Android App untuk Intersep Notifikasi Google Maps

App Android sederhana yang menangkap notifikasi navigasi dari Google Maps
dan mengirimkan datanya ke ESP8266 via MQTT (internet).

---

## 🔑 Teknologi Utama

### `NotificationListenerService`
API resmi Android (tidak butuh root) yang memungkinkan satu app membaca
notifikasi dari app lain. Google Maps selalu menampilkan notifikasi navigasi
persistent saat navigasi aktif — inilah yang kita baca.

```
[Google Maps]
  ↓ navigasi aktif → tampilkan notifikasi
  "Belok kanan dalam 350 m"
  "Jl. MH Thamrin"

[NavListenerService]
  ↓ onNotificationPosted() dipanggil Android
  ↓ filter paket: com.google.android.apps.maps
  ↓ ekstrak title, text, bigText, subText
  ↓ parse maneuver, jarak, nama jalan, ETA

[MqttManager]
  ↓ publish JSON ke broker.emqx.io
  ↓ topic: navmqtt/{channelID}/data

[ESP8266]
  ↓ subscribe MQTT
  ↓ tampilkan di OLED / TFT
```

---

## 📋 Cara Build di Android Studio

### Persyaratan
- Android Studio Hedgehog (2023.1) atau lebih baru
- JDK 11+
- Android SDK API 26+ (Android 8.0)
- Perangkat Android 8.0+ (untuk test)

### Langkah Build

```bash
# 1. Buka Android Studio → Open Project → pilih folder NavListener/
# 2. Tunggu Gradle sync selesai (~2-3 menit pertama kali)
# 3. Build → Run 'app' (atau Shift+F10)
# 4. Pilih perangkat Android Anda
```

### Jika ada error Paho MQTT:
Pastikan `build.gradle` project level sudah ada repository Paho:
```groovy
maven { url "https://repo.eclipse.org/content/repositories/paho-snapshots/" }
```

---

## 📱 Cara Penggunaan

### Step 1: Beri Izin NotificationListener

1. Buka app NavListener di HP
2. Klik tombol **"⚠ BERI IZIN NOTIFIKASI"**
3. Akan muncul halaman Settings Android
4. Cari **"NavListener"** dalam daftar → aktifkan
5. Konfirmasi dialog yang muncul
6. Kembali ke app → status berubah **hijau "AKTIF"**

> ⚠️ **Catatan Privasi**: Izin ini memungkinkan app membaca SEMUA notifikasi.
> Kode app ini hanya memproses notifikasi dari package `com.google.android.apps.maps`.
> Tidak ada data yang dikirim ke server selain ESP8266 Anda sendiri.

### Step 2: Konfigurasi MQTT

1. Isi **Channel ID** (contoh: `NAVKU001`)
   - Harus sama persis dengan Channel ID di ESP8266 firmware
   - Case-sensitive, gunakan huruf kapital
2. Broker URI sudah terisi default `tcp://broker.emqx.io:1883`
3. Klik **CONNECT MQTT**
4. Status berubah hijau jika berhasil

### Step 3: Mulai Navigasi Google Maps

1. Buka Google Maps
2. Masukkan tujuan → mulai navigasi
3. Kembali ke NavListener — data langsung muncul di card navigasi
4. ESP8266 menerima data dan menampilkan di OLED!

---

## 🔍 Format Notifikasi Google Maps

Google Maps mengirim beberapa format notifikasi. NavListenerService
menangani semua varian ini:

### Format A — Paling Umum
```
Title : "Belok kanan dalam 350 m"
Text  : "Jl. MH Thamrin"
```

### Format B — Dengan ETA
```
Title  : "Jl. MH Thamrin"
Text   : "Belok kanan dalam 350 m"
SubText: "Tiba pk 14:30 · 2,3 km tersisa"
```

### Format C — Bahasa Inggris
```
Title : "Turn right in 350 m"
Text  : "onto Jl. MH Thamrin"
```

### Format D — Arriving Soon
```
Title : "Tiba dalam 2 menit"
Text  : "Tujuan di kanan"
```

---

## 📡 Payload MQTT yang Dikirim

```json
{
  "a":  1,              // active
  "m":  "turn-right",   // maneuver
  "d":  350,            // jarak (angka)
  "u":  "m",            // satuan: m atau km
  "s":  "Jl. MH Thamrin", // nama jalan
  "e":  "14:30",        // ETA
  "r":  2.3,            // jarak sisa
  "ru": "km",           // satuan sisa
  "sp": 0,              // speed (0 karena tidak ada GPS di versi ini)
  "h":  0,              // heading
  "la": "0",            // lat (tidak tersedia dari notifikasi)
  "ln": "0"             // lng
}
```

> **Catatan**: `speed`, `heading`, `lat`, `lng` akan selalu 0 karena
> notifikasi Google Maps tidak menyertakan data GPS mentah.
> Untuk data GPS, gunakan pendekatan Web App + Google Maps API (proyek sebelumnya).

---

## ⚙️ Modifikasi Channel ID di ESP8266

Pastikan firmware ESP8266 menggunakan Channel ID yang sama:

```cpp
// esp8266_nav.ino
const char* CHANNEL_ID = "NAVKU001"; // ← sama dengan app Android
```

Topic MQTT yang di-subscribe ESP8266:
```
navmqtt/NAVKU001/data
```

---

## 🔧 Troubleshooting

| Masalah | Solusi |
|---------|--------|
| Status listener merah | Ulangi proses beri izin di Settings |
| MQTT tidak connect | Cek koneksi internet, coba ganti broker |
| Tidak dapat notifikasi | Pastikan Google Maps sedang navigasi aktif |
| Data tidak muncul di ESP8266 | Cek Channel ID sama persis (case-sensitive) |
| App crash saat start | Pastikan Android 8.0+, cek Gradle sync |
| Paho library not found | Tambah maven repo Paho di build.gradle project |

### Test tanpa ESP8266:
Gunakan MQTT Explorer (desktop app) untuk subscribe topic dan lihat data real-time:
```
Broker: broker.emqx.io
Port  : 1883
Topic : navmqtt/NAVKU001/data  (subscribe)
```

---

## 🏗️ Struktur Kode

```
NavListener/
├── app/
│   ├── src/main/
│   │   ├── java/com/navlistener/
│   │   │   ├── MainActivity.java         ← UI + konfigurasi
│   │   │   ├── NavListenerService.java   ← Intersep + parse notifikasi
│   │   │   ├── NavState.java             ← Model data navigasi
│   │   │   └── MqttManager.java          ← Koneksi + publish MQTT
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml  ← Layout UI
│   │   │   ├── drawable/                 ← Ikon panah maneuver
│   │   │   └── values/                   ← Warna, style, string
│   │   └── AndroidManifest.xml           ← Permission + service declaration
│   └── build.gradle                      ← Dependencies (Paho MQTT)
└── build.gradle                          ← Project-level config
```

### Alur Data
```
Google Maps notif
  → NavListenerService.onNotificationPosted()
  → parseNavigation() → detectManeuver() + extractDistance() + extractStreetName()
  → NavState object
  → MqttManager.publish(navState.toJson())
  → broker.emqx.io:1883
  → ESP8266 NavListenerService.onMessage()
  → OLED / TFT display
  + sendBroadcast() → MainActivity update UI
```

---

## 🔒 Limitasi

1. **Tidak ada data GPS raw** — notifikasi tidak menyertakan koordinat
2. **Bergantung pada format notifikasi Google Maps** — bisa berubah setiap update app
3. **Delay ~0.5-1 detik** — dari notifikasi muncul sampai ESP8266 menerima
4. **Android only** — iOS tidak mengizinkan NotificationListenerService
5. **Google Maps harus foreground/background aktif** — jika di-kill, navigasi berhenti

---

*NavListener v1.0 — Intersep notifikasi Google Maps ke ESP8266*
