# HozinInspector

Modul LSPosed — **app inspector universal** untuk reverse engineering aplikasi Android.
Satu modul, bisa dipakai ke app apa saja: intip method, URL, klik tombol, SharedPreferences,
manifest, dan layout — semua dari HP yang sudah root.

## Fitur

| Fitur | Yang dilakukan |
|---|---|
| **Method tracer** | Hook semua method dari class yang match filter prefix → log pemanggilan (argumen + return value + thread) |
| **URL tracker** | Catat semua request keluar: `URL.openConnection`, `HttpURLConnection`, OkHttp/Retrofit, `WebView.loadUrl` |
| **UI interaction tracer** | Tap tombol/view → ketahuan class listener-nya + alur eksekusi "dari mana ke mana" (stack trace) |
| **Pref access tracer** | Setiap read/write SharedPreferences dicatat **beserta class pemicunya** |
| **Manifest viewer** | Activities, services, receivers, providers, permissions app target |
| **Layout dump** | Dump view hierarchy layar yang sedang tampil (on-demand) |

## Syarat

- HP **root** (Magisk / KernelSU)
- **LSPosed** terinstall & aktif

## Cara install & setup

1. Install APK (dari artifact CI / build manual).
2. Buka **LSPosed Manager** → Modules → aktifkan **HozinInspector**.
3. Di LSPosed Manager, centang **scope** = aplikasi yang mau diinspeksi.
4. Buka app HozinInspector:
   - **Pilih** app target.
   - Nyalakan toggle fitur yang mau dipakai; untuk method tracer isi **filter class prefix** (cth: `com.target.app.`).
   - **SIMPAN CONFIG** (butuh root — config ditulis ke `/data/local/tmp/HozinInspector/config.json`, fallback `/sdcard/HozinInspector/config.json`).
   - **GRANT READ_LOGS** (root) agar bisa baca log.
5. **Force-stop lalu buka ulang** app target supaya hook kepasang.
6. Pakai app target seperti biasa → buka **Lihat Log** di HozinInspector untuk melihat hasilnya.

## Format config.json

```json
{
  "targetPackage": "com.contoh.app",
  "methodTrace": true,
  "traceClasses": ["com.contoh.app."],
  "urlTrack": true,
  "uiTrace": true,
  "prefTrace": true,
  "dumpUi": false
}
```

## Cara kerja

- `assets/xposed_init` menunjuk `HookEntry` (`IXposedHookLoadPackage`).
- Tiap proses app dicek: hanya `targetPackage` yang di-hook, sisanya diabaikan.
- Semua event ditulis via `XposedBridge.log("[HozinInspector][TAG] ...")` → dibaca app UI dari logcat.
- Xposed API (`de.robv.android.xposed:api:82`) hanya `compileOnly` — disediakan LSPosed saat runtime.

## Batasan jujur

- **Hanya kode Java/Kotlin** — kode native (`.so`) tidak terlihat.
- **Isi body HTTPS terenkripsi** — yang kelihatan cuma URL + method.
- **EncryptedSharedPreferences** (Jetpack Security): key kelihatan, value terenkripsi.
- **MMKV / DataStore** belum ke-cover (format beda).
- **App obfuscated** (nama class `a/b/c`) tetap ke-track tapi susah dibaca.
- **Method tracer tanpa filter = lemot** — selalu pakai prefix yang spesifik.
- Beberapa app (banking, dsb.) mendeteksi Xposed/LSPosed dan menolak jalan.

## Build manual

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
