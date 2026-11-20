# Hozin Mekorama Mod v1.2 — untuk Mekorama 1.7.2

Tiga perbaikan dari laporan uji v1.1 di HP: **Auto Play kini bisa geser blok (drag/slider) + putar kamera**, **Buat World Acak kini benar-benar bisa di-play**, dan **Auto Play kini saklar Switch asli**. Semua fitur v1.0/v1.1 tetap ada dan tidak berubah.

## Yang diperbaiki

- **Auto Play bisa geser blok (drag/slider).** Solver kini memodelkan blok yang bisa digeser — slider (tipe 41, sumbu dibaca dari byte rot game) dan blok drag tembaga/silinder (tipe 17/36) — lalu menyusun rencana jalan + geser yang terverifikasi simulasi. Eksekusi memakai fungsi game sendiri (`voxel_remove` + `voxel_add`), bukan suntik posisi robot. **"Ride C Over Sea" kini selesai: 12 langkah jalan + 4 geser blok, solve 19 ms** (v1.1: auto-off "jalur putus"). Teruji terhadap 144 level bawaan: **92 solvable** (49 murni jalan kaki, 43 butuh geser blok) — naik dari 38 di v1.1. Empat level uji: "5x5x5" (2 langkah), "Ant Farm" (21 langkah + 2 geser), "Ride C Over Sea" (12 + 4 geser) selesai; "Shock Shuffle" dilaporkan jujur tidak bisa di-solve (sel awal robot tertutup).
- **Auto Play putar kamera.** Bila waypoint tidak tercapai (kemungkinan tertutup struktur dari sudut sekarang), Auto Play memutar kamera 90° lewat target yaw milik game (`camera_yaw_target` + `camera_mod_yaw` — `camera_step` menganimasikannya halus seperti swipe pemain), lalu mencoba lagi. Plus sesekali putar natural tiap ~10 waypoint supaya kelihatan seperti pemain sungguhan, bukan robot kaku. Tidak putar-putar tanpa henti.
- **Buat World Acak benar-benar bisa di-play (bug v1.1 diperbaiki).** Akar masalah: format file level ternyata **berselip** (tiap sel membawa 0–2 byte ekstra sesuai tabel `block_types` game) — encode v1.1 menulis grid polos 4096 byte + ekor nol, sehingga loader game salah parse dan menolak level (kartu muncul tapi play tidak jalan, author "Unknown Author", preview kosong). v1.2 menulis format yang benar (judul + author + sel berselip byte ekstra, tanpa ekor) — kartu ter-render, author "Hozin", dan **play dari kartu menjalankan level sampai selesai**. Uji host: 20/20 world lolos simulasi load game + solvable + author benar.
- **Auto Play kini Switch Android asli.** Tombol teks ("▶ Auto Play — OFF") diganti saklar geser berlabel "Auto Play": posisi saklar menunjukkan ON/OFF, diteken langsung flip. Perilaku tetap: ON = main sendiri level demi level sampai dimatikan; auto-off setelah 3x gagal berurutan + alasan jelas di status.

## Fitur (tetap)

- **🔓 Unlock Semua Level — Stabil.** Membuka seluruh 80 level Story dengan mengedit file save profil lokal game (format zlib JSON bawaan game). Logika editnya deterministik dan diuji otomatis di CI terhadap data asli game.
- **↩ Pulihkan Save — Stabil.** Save asli dicadangkan otomatis sebelum edit pertama; tombol ini mengembalikannya.
- **🌀 Teleport ke Finish — EKSPERIMENTAL.** Payload native di dalam proses game mencoba menemukan badan robot dan blok finish dari data level yang sedang dimuat, lalu memindahkan robot ke sana. Fitur ini berbasis analisis statis + heuristik dan **belum terverifikasi di perangkat** — hasil setiap percobaan dilaporkan apa adanya di panel menu.

Yang tidak diubah: donasi *Pay What You Want* dan status bebas-iklan adalah gerbang pembayaran nyata milik developer — mod ini tidak menyentuhnya.

## Batas jujur (diuji terhadap 144 level bawaan)

- **92/144 solvable** (49 jalan kaki, 43 butuh geser blok). Sisanya **dilaporkan alasannya lalu berhenti** — tidak dipaksa, tidak pura-pura berhasil:
  - **Motor / bola** — gerak motor dan fisika bola belum dimodelkan (butuh simulasi engine).
  - **Kotak dorong (SOKOBAN)** — mekanik dorong belum dimodelkan (beda dari drag).
  - **Timing zapper (tesla)** — solver menghindari area zapper; bila satu-satunya jalan lewat dekat zapper, ia berhenti (timing zapper belum bisa dibaca dari game).
  - **Logika / tombol / timer** — level yang butuh urutan tombol/sinyal belum dimodelkan.
- **Tanpa delay:** solve level yang berhasil hanya belasan–puluhan milidetik (Ride C: 19 ms); tiap langkah hanya menunggu robot selesai bergerak — tidak ada jeda buatan. Level yang tidak bisa di-solve butuh sampai ~2 detik (batas A*) sebelum dilaporkan — sama seperti v1.1.
- **Geser slider:** eksekusi slider memulihkan rel di sel yang ditinggal (sesuai model solver). Naik slider (robot ikut bergerak bersama slider) dimodelkan solver tapi belum teruji di perangkat — bila slider tidak bergerak seperti rencana, Auto Play berhenti + melapor, bukan diam-diam lanjut.

## Cara Instal

> ⚠️ **Uninstall dulu Mekorama asli.** APK mod ini memakai sertifikat signing proyek (bukan sertifikat developer game), jadi tidak bisa dipasang menimpa game asli. Meng-uninstall game asli akan menghapus save lama.

1. Uninstall Mekorama asli dari HP.
2. Pasang `HozinMekoramaMod-v1.2.apk` dari rilisan ini.
3. Buka game — tombol melayang **MOD** muncul di sisi kiri. Seret untuk memindahkan, ketuk untuk membuka panel.
4. Untuk Unlock: ketuk **Unlock Semua Level**, lalu game akan ditutup paksa ±2 detik (disengaja, agar hasil edit tidak tertimpa save di memori). Buka lagi game-nya — semua level Story sudah terbuka.
5. Untuk Auto Play: masuk ke dalam satu level Story, buka panel, geser saklar **Auto Play** ke ON — robot jalan sendiri (termasuk geser blok bila perlu). Geser lagi ke OFF kapan pun.

## Kompatibilitas

- Khusus **Mekorama 1.7.2** (versionCode 44), Android 5.0+ (API 21+), arm64-v8a / armeabi-v7a.
- Tidak butuh root.

## Catatan

- Auto Play memakai fungsi engine game sendiri (`figure_tap` untuk jalan, `voxel_load` untuk pindah level, `voxel_add`/`voxel_remove` untuk geser blok, `camera_mod_yaw` untuk putar kamera, `sandbox_write` untuk world acak) — bukan tap layar palsu. Perilaku di perangkat nyata (terutama ketepatan deteksi posisi robot, geser blok, dan putar kamera) masih menunggu uji — masukan hasil uji (terutama teks status yang ditampilkan menu) sangat membantu kalibrasi v1.3.
- Mod tidak resmi untuk pemakaian pribadi/edukasi. Game Mekorama dan seluruh asetnya adalah karya **Martin Magni**.

---
Dibuat oleh **Hozin King** — https://hozinking.my.id
