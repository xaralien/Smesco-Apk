# Smesco — WebView App

Aplikasi Android yang membungkus **https://smesco.kodesis.id/auth** dan
menjembatani fitur perangkat ke website:

- **Lokasi / GPS** -> `navigator.geolocation`
- **Kamera + Mikrofon** -> `getUserMedia` dan ambil foto lewat file input
- **Berkas lokal** -> upload `<input type="file">` (dengan opsi ambil foto)
- **Unduh berkas** -> tautan download tersimpan ke folder **Download** publik,
  lengkap dengan notifikasi progres. Cookie sesi ikut terkirim, jadi berkas
  di balik login tetap terunduh dengan benar.

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

## Ubah-ubah

- URL: `startUrl` di `app/src/main/java/id/smesco/mobile/MainActivity.kt`
- Nama app: `app/src/main/res/values/strings.xml`
- Warna: `app/src/main/res/values/colors.xml`
- Icon: PNG di `app/src/main/res/mipmap-*/`
- Logo splash: PNG di `app/src/main/res/drawable-*/logo_splash.png`
