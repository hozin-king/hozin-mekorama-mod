# Hozin Mekorama Mod

Mod menu **non-root** untuk game puzzle **Mekorama** (versi game **1.7.2**). Berbeda dari aplikasi injector, mod ini berbentuk **satu file APK game yang sudah dimodifikasi**: menu melayang berjalan di dalam proses game itu sendiri, lengkap dengan payload native kecil untuk fitur eksperimental. Tidak perlu root, tidak perlu aplikasi pendamping.

[![Build](https://github.com/hozin-king/hozin-mekorama-mod/actions/workflows/build.yml/badge.svg)](https://github.com/hozin-king/hozin-mekorama-mod/actions/workflows/build.yml)
![Versi Mod](https://img.shields.io/badge/versi%20mod-v1.0-orange)
![Versi Game](https://img.shields.io/badge/game-Mekorama%201.7.2-blue)
![Root](https://img.shields.io/badge/root-tidak%20perlu-brightgreen)

---

## Fitur

| Fitur | Status | Keterangan |
|---|---|---|
| **Unlock Semua Level** | ✅ Stabil | Membuka seluruh 80 level Story dengan mengedit file save profil lokal (format zlib JSON bawaan game). Deterministik dan teruji otomatis di CI terhadap data game asli. |
| **Pulihkan Save** | ✅ Stabil | Mengembalikan save asli dari backup yang dibuat otomatis sebelum edit pertama (`<save>.hozibak`). |
| **Teleport ke Finish** | 🧪 Eksperimental | Payload native mencari badan robot & blok finish dari data level yang sedang dimuat, lalu memindahkan robot. Berbasis analisis statis + heuristik — **menunggu uji perangkat**, belum tentu bekerja di semua level. Laporan hasil selalu ditampilkan apa adanya di menu. |
| Ketuk-untuk-Teleport | ⏸ Belum ada | Sengaja tidak dimasukkan di v1.0: dasar teleportnya belum terbukti cukup meyakinkan secara statis. |

Yang **sengaja tidak** dimodifikasi: donasi *Pay What You Want* dan status bebas-iklan yang melekat padanya adalah gerbang pembayaran nyata milik developer — mod ini tidak menyentuhnya.

## Cara Instal

> ⚠️ **Wajib uninstall Mekorama asli terlebih dahulu.** APK mod ditandatangani dengan sertifikat proyek ini (bukan sertifikat Martin Magni), jadi Android akan menolak pemasangan di atas game asli.

1. (Opsional) Cadangkan save lama bila ingin aman — walau begitu, meng-uninstall game asli akan menghapus save-nya.
2. Uninstall **Mekorama** asli dari perangkat.
3. Unduh `HozinMekoramaMod-v1.0.apk` dari halaman [Releases](../../releases).
4. Pasang APK-nya (izinkan "instal dari sumber tidak dikenal" bila diminta).
5. Buka game — tombol melayang **MOD** muncul di sisi kiri layar.

## Cara Pakai Menu

- **Seret** tombol `MOD` untuk memindahkannya; **ketuk** untuk membuka/menutup panel.
- **🔓 Unlock Semua Level** — menulis ulang save profil: semua level Story terbuka. Game akan **ditutup paksa ±2 detik** setelahnya; ini disengaja agar save di memori game tidak menimpa hasil edit. Buka lagi game-nya dan semua level sudah terbuka.
- **↩ Pulihkan Save Asli** — mengembalikan backup yang dibuat otomatis saat Unlock pertama kali.
- **🌀 Teleport ke Finish (EKSPERIMENTAL)** — gunakan saat sudah masuk ke dalam level. Hasil percobaan (berhasil/gagal/alasannya) ditampilkan sebagai teks status di panel.

## Kompatibilitas

- Game: **Mekorama 1.7.2** (`com.martinmagni.mekorama`, versionCode 44) — satu-satunya versi yang didukung.
- Android: minSdk 21 (mengikuti game); arsitektur `arm64-v8a` dan `armeabi-v7a`.
- Root: **tidak diperlukan**.

## Cara Kerja Singkat

- **Lapisan menu (Java)** di-compile menjadi dex tambahan (`classes3.dex`) di dalam APK; activity game di-patch satu baris di `onCreate` untuk menempelkan tombol + panel menu ke jendela game.
- **Unlock** murni pengeditan file: save ditemukan dengan memindai isi berkas (zlib → JSON berisi array `lvs`), lalu transformasi bintang diterapkan persis mengikuti format asli game. Logikanya diuji di CI melawan template save & `level_infos.json` dari APK asli.
- **Payload native** (`libmekomod.so`) dimuat oleh game sendiri lewat `System.loadLibrary`; ia menyelesaikan simbol ekspor engine (`db_worlds`, `voxels_typ`, `world_min/max`) secara dinamis dan menerapkan heuristik teleport dari data level yang sedang aktif. Detail & batasan: [docs/TECHNICAL.md](docs/TECHNICAL.md).

## Build dari Sumber

Build resmi hanya berjalan di GitHub Actions (workflow `.github/workflows/build.yml`):

1. APK game dasar **tidak didistribusikan** di repo ini. Yang tersimpan di `base/` hanyalah salinan **terenkripsi AES-256** (`mekorama-1.7.2.apk.enc`) yang hanya bisa dibuka dengan kunci di GitHub Secrets pemilik repo, plus hash SHA-256 bentuk aslinya untuk verifikasi.
2. Keystore signing proyek hanya ada di GitHub Secrets — tidak pernah ter-commit.
3. Rilis dibuat dengan mendorong tag `v*`; workflow menempelkan APK ke GitHub Release secara otomatis.

## Struktur Proyek

```
menu-src/    Lapisan menu Java + SaveEditor (murni Java, teruji di host)
native/      Payload libmekomod.so (C++/CMake, 2 ABI)
test/        Uji host SaveEditor yang dijalankan CI
tools/       patch_smali.py — penyisip titik masuk menu ke activity game
base/        APK dasar terenkripsi + hash verifikasi (bukan APK yang bisa dipakai)
```

## Disclaimer

Proyek modifikasi tidak resmi ini dibuat untuk **pemakaian pribadi dan edukasi** (studi reverse engineering & modding Android). Mod ini tidak berafiliasi dengan, dan tidak didukung oleh, developer game aslinya. Seluruh hak atas game Mekorama — kode, aset, level, dan mereknya — tetap milik pembuatnya. Unduh dan gunakan atas risiko sendiri; bila menyukai gamenya, dukung developer aslinya (misalnya lewat donasi *Pay What You Want* di dalam game).

## Kredit

- **Game asli:** *Mekorama* — karya **Martin Magni** (Martin Magni AB), [mekorama.com](https://www.mekorama.com).
- **Mod ini:** Dibuat oleh **Hozin King** — [https://hozinking.my.id](https://hozinking.my.id)

## Lisensi

Kode mod (menu, payload, peralatan build) dirilis di bawah lisensi MIT — lihat [LICENSE](LICENSE). Lisensi ini **tidak** mencakup game Mekorama atau aset-asetnya.
