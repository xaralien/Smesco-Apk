# Smesco — WebView App

Aplikasi Android yang membungkus **https://smesco.kodesis.id/auth** dan
menjembatani fitur perangkat ke website:

- **Lokasi / GPS** -> `navigator.geolocation`
- **Kamera + Mikrofon** -> `getUserMedia` dan ambil foto lewat file input
- **Berkas lokal** -> upload `<input type="file">` (dengan opsi ambil foto)
- **Unduh berkas** -> tautan download tersimpan ke folder **Download** publik,
  lengkap dengan notifikasi progres. Cookie sesi ikut terkirim, jadi berkas
  di balik login tetap terunduh dengan benar.
- **Berkas buatan halaman** (`blob:` / `data:`, mis. PDF dari jsPDF atau Excel
  dari SheetJS) -> ikut tersimpan ke folder **Download**
- **Print** -> `window.print()` membuka pilihan:
  - *Printer biasa / Simpan sebagai PDF* (dialog cetak Android, mendukung
    printer Wi-Fi dan plugin printer seperti HP, Epson, Canon)
  - *Printer thermal Bluetooth* 58/80mm (ESC/POS). Halaman dicetak dengan CSS
    `@media print`, jadi tidak perlu ubah website.

Nama app: **Smesco** • Package: `id.smesco.mobile`

---

## Build lewat GitHub Actions (tanpa install apa-apa)

1. Push seluruh isi folder ini ke sebuah repo GitHub.
2. Buka tab **Actions** -> workflow **Build APK** jalan otomatis.
3. Setelah selesai, buka run tersebut -> bagian **Artifacts** -> unduh
   `smesco-debug-apk`.
4. Ekstrak zip-nya, kirim `app-debug.apk` ke HP, lalu install.
   (HP perlu mengizinkan "Install dari sumber tidak dikenal".)

## Build lewat Android Studio

1. `File > Open`, pilih folder ini, tunggu Gradle sync selesai.
2. `Build > Build Bundle(s) / APK(s) > Build APK(s)`
   -> `app/build/outputs/apk/debug/app-debug.apk`

## Build lewat command line

```bash
gradle wrapper          # sekali saja, kalau ./gradlew belum ada
./gradlew assembleDebug # -> app/build/outputs/apk/debug/app-debug.apk
```

## Rilis ke Google Play

Buat keystore sekali:

```bash
keytool -genkey -v -keystore smesco.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias smesco
```

Tambahkan di dalam blok `android { }` pada `app/build.gradle`:

```groovy
signingConfigs {
    release {
        storeFile file("../smesco.jks")
        storePassword "PASSWORD_STORE"
        keyAlias "smesco"
        keyPassword "PASSWORD_KEY"
    }
}
buildTypes {
    release {
        signingConfig signingConfigs.release
        minifyEnabled false
    }
}
```

Lalu `./gradlew bundleRelease` -> `app/build/outputs/bundle/release/app-release.aab`.

Simpan baik-baik file `.jks` dan passwordnya. Setiap update berikutnya wajib
ditandatangani dengan kunci yang sama.

## Printer thermal Bluetooth

1. Nyalakan printer, lalu **pair** di pengaturan Bluetooth HP (PIN biasanya
   `0000` atau `1234`).
2. Di website, tekan tombol Print -> pilih *Printer thermal Bluetooth*.
   Pertama kali, aplikasi minta izin Bluetooth, lalu minta pilih printer dan
   lebar kertas. Pilihan disimpan, jadi selanjutnya langsung cetak.
3. Ganti printer / ukuran kertas: Print -> *Pilih printer Bluetooth & ukuran kertas*.

Agar struk rapi, buat CSS khusus cetak di website, misalnya:

```css
@media print {
  nav, .sidebar, .no-print { display: none !important; }
  body { margin: 0; font-size: 12px; }
}
```

### API JavaScript (opsional)

Kalau website mau mengatur sendiri isi struk, gunakan `window.SmescoPrint`
(hanya ada di dalam aplikasi). Semua fungsi cetak mengembalikan Promise.

```js
if (window.SmescoPrint) {
  const cols = SmescoPrint.columns();          // 32 (58mm) atau 48 (80mm)
  await SmescoPrint.text("TOKO SMESCO\nTotal: Rp 25.000\n");
  await SmescoPrint.image(canvasAtauDataUrl);  // logo, QR code, dll
  await SmescoPrint.raw(base64EscPos);         // perintah ESC/POS mentah
  await SmescoPrint.page();                    // cetak halaman ini ke printer Bluetooth
  await SmescoPrint.selectPrinter();           // ganti printer
  SmescoPrint.getPrinter();                    // {name, address, paper, columns} atau null
  SmescoPrint.dialog();                        // dialog cetak Android biasa
}
// Kalau skrip website jalan lebih dulu, tunggu event 'smescoprintready'.
```

Jembatan ini hanya aktif untuk domain situs sendiri (domain `startUrl` dan
subdomainnya).

## Ubah-ubah

- URL: `startUrl` di `app/src/main/java/id/smesco/mobile/MainActivity.kt`
- Nama app: `app/src/main/res/values/strings.xml`
- Warna: `app/src/main/res/values/colors.xml`
- Icon: PNG di `app/src/main/res/mipmap-*/`
- Logo splash: PNG di `app/src/main/res/drawable-*/logo_splash.png`
