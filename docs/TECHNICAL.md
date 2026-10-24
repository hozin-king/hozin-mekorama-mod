# Catatan Teknis — Hozin Mekorama Mod v1.0

Dokumen ini merangkum dasar teknis mod: dari mana format save diketahui,
bagaimana unlock bekerja, dan — yang terpenting — apa dasar fitur Teleport
serta batas-batasnya. Ditulis agar status tiap fitur bisa dinilai jujur.

## 1. Dasar analisis (REA Mekorama 1.7.2)

- APK resmi Martin Magni (`com.martinmagni.mekorama`, v1.7.2 / code 44),
  sertifikat asli `CN=Mekorama, O=Martin Magni AB`. Engine: library native
  kustom `libmekorama.so` (fisika Bullet), tidak di-strip (4.762 simbol
  dinamis), lapisan Java tipis & tidak diobfuskasi. Tidak ditemukan
  anti-tamper / pemeriksaan tanda tangan di lapisan aplikasi.
- Pada dasarnya **offline penuh**: tidak ada server progres/leaderboard.
  Online hanya AdMob, Play Billing (donasi *Pay What You Want*), dan
  Crashlytics/Analytics (bawaan mati).

### Format save

Template profil bawaan (`assets/db`, 958 byte) adalah stream zlib mentah
yang mengembang menjadi JSON kompak:

```json
{"v":1,"fl":-1,"om":1,"os":1,"ocs":1,"prt":0,"ad":0,"up":0,
 "dan":"Unknown Author","pwyw":[],"lvs":[{"n":"x_crash_course.bin","t":0,"s":1,"sb":0}, ...]}
```

- `lvs` = progres per level: `n` nama berkas level, `t` tipe, `s` bintang,
  `sb` langkah terbaik.
- `assets/level_infos.json`: katalog 80 Story + 40 Featured. Level Story
  terkunci oleh **total bintang**: field `su` naik monoton 0 → 150.
- Template hanya memuat 50 dari 80 entri Story; 30 level belum punya entri
  sama sekali. Jumlah `sp` (116) bukan batas total bintang; batas yang
  menentukan adalah `su` maksimum = 150.

## 2. Unlock Semua Level (stabil)

Dilakukan `SaveEditor` (Java murni, `menu-src/.../SaveEditor.java`):

1. Menu menemukan file save runtime dengan **memindai isi berkas** di
   `filesDir` (rekursif), `dataDir`, `externalFilesDir`, dan
   `/sdcard/Mekorama` — berkas yang terdekode zlib → JSON ber-array `lvs`
   adalah save-nya. (Nama berkas save tidak terekspos sebagai string di
   library, jadi penemuan berbasis isi adalah cara yang kokoh.)
2. Backup otomatis ke `<save>.hozibak` sebelum penulisan pertama.
3. Transformasi: setiap level Story dipastikan punya entri `lvs` (yang
   hilang ditambahkan `{n, t:0, s:2, sb:0}`) dan `s` diset **2**
   → total 80 × 2 = **160 ≥ 150**, melewati semua ambang `su`.
   Nilai 3 sengaja dihindari: string internal game (mis.
   `menu_stamp_star_index`) mengindikasikan nilai bintang dipakai sebagai
   indeks array. Entri non-Story dan field lain — termasuk flag donasi
   `ad`/`up`/`pwyw` — tidak disentuh.
4. Hasil ditulis, lalu **dibaca balik & diverifikasi** dari disk
   (total bintang ≥ 150) sebelum dinyatakan berhasil.
5. Proses game **ditutup paksa** ±2 detik setelahnya. Tanpa ini, game yang
   menyimpan profil di memori akan menimpa hasil edit saat keluar normal.

**Verifikasi:** uji host (`test/.../SaveEditorTest.java`) berjalan di CI
melawan `assets/db` + `level_infos.json` ASLI dari APK game: roundtrip
JSON identik, semua 80 entri Story `s=2`, total 160, field lain utuh,
idempoten. Yang belum bisa dibuktikan tanpa perangkat: bahwa engine
menerima berkas hasil edit persis sama seperti save yang dibuatnya sendiri
(formatnya byte-compatible pada level zlib/JSON; risiko dinilai rendah).

## 3. Teleport ke Finish (EKSPERIMENTAL — menunggu uji HP)

Payload `libmekomod.so` dimuat proses game sendiri dan menyelesaikan simbol
ekspor engine via `dlsym` (tanpa offset hardcode antar-build):

| Simbol | Peran |
|---|---|
| `db_worlds` | Penunjuk dunia fisika Bullet aktif |
| `voxels_typ` | 4096 byte tipe blok grid level (16×16×16) |
| `world_min` / `world_max` | Batas dunia (3 float) untuk pemetaan sel → koordinat |
| `level_unlock_cheat` | Flag cheat bawaan game (hanya dibaca, diagnostik) |

### Langkah teleport

1. **Kandidat finish:** tipe blok yang muncul tepat 1× di `voxels_typ`.
   Sel-sel unik itu berkelompok di sekitar posisi awal robot (bagian-bagian
   robot + blok spesial); kandidat finish = sel unik **terjauh dari pusat
   massa** kelompok itu.
2. **Badan robot:** enumerasi objek dari array `m_collisionObjects` milik
   `btCollisionWorld` (offset kandidat divalidasi: ukuran wajar + vtable
   elemen berada di rentang `libmekorama.so`). Badan robot = objek yang
   origin transform-nya (asumsi tata letak Bullet: `+0x38` 64-bit) paling
   dekat ke posisi awal robot. Bila jaraknya > 10 satuan, percobaan
   dibatalkan (indikasi salah identifikasi).
3. **Pemindahan:** origin (dan origin interpolasi `+0x78`) ditulis ke
   posisi sel finish + 1 satuan di atasnya, 3× berturut-turut dengan jeda
   120 ms.

### Batasan yang diketahui (dilaporkan apa adanya di menu)

- Heuristik finish **bisa salah**: tipe blok bukan tabel nama↔angka yang
  berhasil dipulihkan dari analisis statis, dan ada tipe unik lain di
  beberapa level (mis. blok mata/penanda). Inilah alasan fitur ini
  berlabel eksperimental dan **ketuk-untuk-teleport tidak dibuat** di v1.0.
- Tata letak `btCollisionObject`/`btCollisionWorld` adalah asumsi versi
  Bullet yang dibundel game — tidak terverifikasi runtime.
- Kecepatan linear robot **tidak di-nol-kan**: offset `m_linearVelocity`
  pada `btRigidBody` tidak cukup pasti untuk ditulis buta; penulisan 3×
  hanya meredam, robot masih bisa bergeser sedikit.
- Pemetaan indeks voxel memakai asumsi `x=i%16, y=(i/16)%16 (vertikal),
  z=i/256`.

Semua langkah melaporkan hasilnya sebagai teks status di panel menu —
termasuk saat gagal dan alasannya — agar hasil uji HP bisa dipakai untuk
kalibrasi v1.1.

## 4. Alur build (GitHub Actions saja)

1. Dekripsi `base/mekorama-1.7.2.apk.enc` (AES-256-CBC + PBKDF2, kunci di
   Secrets) → verifikasi SHA-256 terhadap `base/base.sha256.txt`.
2. Uji host SaveEditor.
3. Build payload untuk `arm64-v8a` + `armeabi-v7a` (NDK/CMake, STL statis —
   APK game tidak membawa `libc++_shared.so`).
4. Compile menu Java → d8 → baksmali → folder `smali_classes3`.
5. apktool decode APK dasar → `tools/patch_smali.py` menyisipkan satu baris
   `Menu.attach(...)` setelah `invoke-super onCreate` di activity game
   (gagal keras bila jangkar berubah) → tambahkan `smali_classes3` +
   `libmekomod.so` kedua ABI → apktool build.
6. zipalign + apksigner dengan keystore proyek (Secrets) → verifikasi
   tanda tangan & isi APK (dex menu + kedua payload + lib game utuh).
7. Tag `v*` memicu pembuatan GitHub Release dengan isi `RELEASE_NOTES.md`.
