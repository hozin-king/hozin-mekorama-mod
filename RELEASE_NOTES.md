# Hozin Mekorama Mod v1.0 — untuk Mekorama 1.7.2

Rilisan pertama **Hozin Mekorama Mod**: mod menu **non-root** untuk game puzzle Mekorama 1.7.2. Hasilnya satu file APK game yang sudah terpasang menu melayang di dalamnya — tanpa root, tanpa aplikasi injector terpisah.

## Fitur

- **🔓 Unlock Semua Level — Stabil.** Membuka seluruh 80 level Story dengan mengedit file save profil lokal game (format zlib JSON bawaan game). Logika editnya deterministik dan diuji otomatis di CI terhadap data asli game.
- **↩ Pulihkan Save — Stabil.** Save asli dicadangkan otomatis sebelum edit pertama; tombol ini mengembalikannya.
- **🌀 Teleport ke Finish — EKSPERIMENTAL.** Payload native di dalam proses game mencoba menemukan badan robot dan blok finish dari data level yang sedang dimuat, lalu memindahkan robot ke sana. Fitur ini berbasis analisis statis + heuristik dan **belum terverifikasi di perangkat** — hasil setiap percobaan dilaporkan apa adanya di panel menu. Ketuk-untuk-teleport sengaja belum dimasukkan di versi ini.

Yang tidak diubah: donasi *Pay What You Want* dan status bebas-iklan adalah gerbang pembayaran nyata milik developer — mod ini tidak menyentuhnya.

## Cara Instal

> ⚠️ **Uninstall dulu Mekorama asli.** APK mod ini memakai sertifikat signing proyek (bukan sertifikat developer game), jadi tidak bisa dipasang menimpa game asli. Meng-uninstall game asli akan menghapus save lama.

1. Uninstall Mekorama asli dari HP.
2. Pasang `HozinMekoramaMod-v1.0.apk` dari rilisan ini.
3. Buka game — tombol melayang **MOD** muncul di sisi kiri. Seret untuk memindahkan, ketuk untuk membuka panel.
4. Untuk Unlock: ketuk **Unlock Semua Level**, lalu game akan ditutup paksa ±2 detik (disengaja, agar hasil edit tidak tertimpa save di memori). Buka lagi game-nya — semua level Story sudah terbuka.

## Kompatibilitas

- Khusus **Mekorama 1.7.2** (versionCode 44), Android 5.0+ (API 21+), arm64-v8a / armeabi-v7a.
- Tidak butuh root.

## Catatan

- Menu tampil di dalam game dan payload termuat adalah hal yang terverifikasi dari struktur APK; perilaku Teleport di perangkat nyata masih menunggu uji — masukan hasil uji (terutama teks status yang ditampilkan menu) sangat membantu untuk kalibrasi v1.1.
- Mod tidak resmi untuk pemakaian pribadi/edukasi. Game Mekorama dan seluruh asetnya adalah karya **Martin Magni**.

---
Dibuat oleh **Hozin King** — https://hozinking.my.id
