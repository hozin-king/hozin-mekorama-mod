# Hozin Mekorama Mod v1.1 — untuk Mekorama 1.7.2

Dua fitur baru: **Auto Play (toggle)** dan **Buat World Acak**. Semua fitur v1.0 tetap ada dan tidak berubah.

## Fitur baru

- **▶ Auto Play — main sendiri (TOGGLE ON/OFF).** Masuk ke dalam level, ketuk toggle sampai ON — robot memainkan level itu sendiri sampai finish, lalu **otomatis lanjut ke level berikut**, terus begitu sampai kamu ketuk lagi untuk OFF. Status toggle (ON/OFF) selalu kelihatan di menu.
  - **Tanpa delay:** solver A* (heuristik Manhattan, sudah termasuk tinggi) menyelesaikan level dalam belasan milidetik; tiap langkah hanya menunggu robot selesai bergerak — tidak ada jeda buatan antar langkah.
  - **Pengaman:** kalau Auto Play gagal menyusun/menjalankan rencana **3x berturut-turut**, toggle **mati sendiri** + menu menampilkan alasannya. Satu level yang tidak bisa di-solve **tidak di-skip diam-diam** — berhenti + auto-off + pesan jujur.
  - **Batas jujur (diuji terhadap 144 level bawaan):** Auto Play menyelesaikan level yang bisa diselesaikan dengan **jalan kaki saja — 38 dari 144 level** (termasuk "Crash Course" 9 langkah, "Flat Path" 2 langkah). Level yang butuh **geser blok (drag)**, **motor**, **bola**, atau **timing zapper** dilaporkan alasannya di menu lalu berhenti — tidak dipaksa, tidak pura-pura berhasil. Solver menghindari area zapper; bila satu-satunya jalan lewat dekat zapper, ia berhenti (timing zapper belum bisa dibaca dari game).
- **🎲 Buat World Acak.** Generator membuat satu world baru ("Acak Hozin #n", tapak ≤ 8×8, tinggi ≤ 4) yang **sudah diverifikasi solvable oleh solver yang sama** (gagal 50x percobaan → lapor gagal, bukan world asal jadi), lalu menulisnya ke sandbox game lewat fungsi `sandbox_write` milik game — jadi muncul sebagai **kartu level biasa lengkap dengan QR** (sama seperti level hasil scan QR). Kembali ke daftar level untuk melihat kartunya; kalau belum muncul, ketuk "↻ Muat Ulang Game" di menu.

## Fitur lama (tetap)

- **🔓 Unlock Semua Level — Stabil.** Membuka seluruh 80 level Story dengan mengedit file save profil lokal game (format zlib JSON bawaan game). Logika editnya deterministik dan diuji otomatis di CI terhadap data asli game.
- **↩ Pulihkan Save — Stabil.** Save asli dicadangkan otomatis sebelum edit pertama; tombol ini mengembalikannya.
- **🌀 Teleport ke Finish — EKSPERIMENTAL.** Payload native di dalam proses game mencoba menemukan badan robot dan blok finish dari data level yang sedang dimuat, lalu memindahkan robot ke sana. Fitur ini berbasis analisis statis + heuristik dan **belum terverifikasi di perangkat** — hasil setiap percobaan dilaporkan apa adanya di panel menu.

Yang tidak diubah: donasi *Pay What You Want* dan status bebas-iklan adalah gerbang pembayaran nyata milik developer — mod ini tidak menyentuhnya.

## Cara Instal

> ⚠️ **Uninstall dulu Mekorama asli.** APK mod ini memakai sertifikat signing proyek (bukan sertifikat developer game), jadi tidak bisa dipasang menimpa game asli. Meng-uninstall game asli akan menghapus save lama.

1. Uninstall Mekorama asli dari HP.
2. Pasang `HozinMekoramaMod-v1.1.apk` dari rilisan ini.
3. Buka game — tombol melayang **MOD** muncul di sisi kiri. Seret untuk memindahkan, ketuk untuk membuka panel.
4. Untuk Unlock: ketuk **Unlock Semua Level**, lalu game akan ditutup paksa ±2 detik (disengaja, agar hasil edit tidak tertimpa save di memori). Buka lagi game-nya — semua level Story sudah terbuka.
5. Untuk Auto Play: masuk ke dalam satu level Story, buka panel, ketuk **Auto Play** sampai ON — robot jalan sendiri. Ketuk lagi untuk OFF kapan pun.

## Kompatibilitas

- Khusus **Mekorama 1.7.2** (versionCode 44), Android 5.0+ (API 21+), arm64-v8a / armeabi-v7a.
- Tidak butuh root.

## Catatan

- Auto Play memakai fungsi engine game sendiri (`figure_tap` untuk jalan, `voxel_load` untuk pindah level, `sandbox_write` untuk world acak) — bukan tap layar palsu. Perilaku di perangkat nyata (terutama ketepatan deteksi posisi robot dan pindah level otomatis) masih menunggu uji — masukan hasil uji (terutama teks status yang ditampilkan menu) sangat membantu kalibrasi v1.2.
- Mod tidak resmi untuk pemakaian pribadi/edukasi. Game Mekorama dan seluruh asetnya adalah karya **Martin Magni**.

---
Dibuat oleh **Hozin King** — https://hozinking.my.id
