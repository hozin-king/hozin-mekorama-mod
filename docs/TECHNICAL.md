# Catatan Teknis — Hozin Mekorama Mod v1.1

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
kalibrasi v1.2.

## 4. Alur build (GitHub Actions saja)

1. Dekripsi `base/mekorama-1.7.2.apk.enc` (AES-256-CBC + PBKDF2, kunci di
   Secrets) → verifikasi SHA-256 terhadap `base/base.sha256.txt`.
2. Uji host SaveEditor.
3. Uji host Solver + WorldGen (terhadap 144 file level asli dari APK).
4. Build payload untuk `arm64-v8a` + `armeabi-v7a` (NDK/CMake, STL statis —
   APK game tidak membawa `libc++_shared.so`).
5. Compile menu Java → d8 → baksmali → folder `smali_classes3`.
6. apktool decode APK dasar → `tools/patch_smali.py` menyisipkan satu baris
   `Menu.attach(...)` setelah `invoke-super onCreate` di activity game
   (gagal keras bila jangkar berubah) → tambahkan `smali_classes3` +
   `libmekomod.so` kedua ABI → apktool build.
7. zipalign + apksigner dengan keystore proyek (Secrets) → verifikasi
   tanda tangan & isi APK (dex menu + kedua payload + lib game utuh).
8. Tag `v*` memicu pembuatan GitHub Release dengan isi `RELEASE_NOTES.md`.

## 5. Auto Play & Buat World Acak (v1.1)

### 5.1 Dasar REA baru

| Simbol | Peran |
|---|---|
| `walkable` (0xd4110) | Aturan gerak engine: robot berdiri di sel (x,y,z) bila pijakan di bawah (y-1) bertipe > 1 (bukan air/tesla/bola/roda), sel kaki + sel di atasnya kosong. Batu polos (tipe 1) BUKAN pijakan valid — level dirancang demikian. |
| `figure_tap` (0xff854) | Tap internal game ke indeks voxel (0..4095): robot berjalan ke sana lewat pathfinding game sendiri (pplan). Dipakai executor Auto Play, satu tap per waypoint. |
| `voxel_load` (0x150288) | Muat level dari registry game: `voxel_load(indeks, 0)`. Dipakai untuk pindah level otomatis. |
| `level_get` (0x10f154) | Struct level 48 byte per indeks; field pertama = nama file. Bounds-check sendiri (NULL di luar jangkauan) — aman dipindai. Dipakai untuk mencari indeks level dari nama file + verifikasi nama sebelum load. |
| `sandbox_write` (0xd1114) | Tulis world ke sandbox game: `sandbox_write(nama, data, len)` — dipakai game sendiri saat scan QR selesai (`w_scanned.bin`). Dipakai untuk world acak. |
| `sandbox_path` | `sandbox_path(nama)` -> path lengkap sandbox (buffer statis, disalin segera). |
| `figures` / `player_fi` | Array figur 416 byte; tidak dipakai — posisi robot dibaca dari badan Bullet seperti teleport v1.0. |

Format file level (dipakai generator + solver, teruji 144/144 file asli):
`[header 4 byte: 02 13 0d fc (v2; 12 file lama 01 ...)] [stream zlib]` →
`[1B pjg judul][judul][1B pjg author][author][4096 byte grid][ekor N byte NOL]`.
Grid: `x=i%16, y=(i/16)%16 (vertikal), z=i/256`. Ekor bervariasi (16..148
byte, semua nol) dan diabaikan pembaca game; generator menulis 32 byte nol.
Tabel 68 tipe blok dipulihkan dari `block_type_names` (0x237620) — lihat
konstanta di `Solver.java`.

### 5.2 Solver (A*, `Solver.java` — murni Java, teruji host)

- State = sel kaki robot; gerakan: jalan datar, naik 1 tingkat, jatuh
  (jalan ke tetangga kosong lalu jatuh ke pijakan walkable pertama; mati
  bila jatuh ke air / keluar dunia / menabrak blok tak bisa dipijak).
- Heuristik: jarak Manhattan (|dx|+|dy|+|dz|) — sudah mencakup tinggi.
  Batas 200.000 node / ~2 detik; "Crash Course" selesai 13 ms (9 langkah),
  "Flat Path" 2 langkah, seluruh 144 level dalam ~200 ms.
- Zapper (tesla): fase 1 memblokir sel yang berdekatan dengan tesla; bila
  hanya ada jalan lewat dekat zapper, fase 2 menemukannya tapi status
  HAS_ZAPPER (fail-closed — fase zapper tidak bisa dibaca dari game).
- Bila jalan buntu, BFS "longgar" (elemen dinamis dianggap tembus)
  menamai penyebab paling mungkin: motor / bola / butuh geser blok.
- **Hasil terukur pada 144 level bawaan: 38 walk-solvable** (28 dari 90
  Story). Sisanya butuh geser blok / motor / bola / timing zapper —
  dilaporkan alasannya, bukan dipaksa. Uji host (`SolverTest`) mengunci
  angka ini (ambang ≥ 35) + kasus sintetik motor/bola/dinding/tanpa-bot.

### 5.3 Executor Auto Play (`AutoPlay.java`)

- Toggle ON/OFF; loop: baca grid live → solve → kenali level saat ini
  (cocokkan grid dengan 144 file bawaan) → jalankan jalan waypoint per
  waypoint (`figure_tap` di UI thread game + polling posisi robot tiap
  ~16 ms, tanpa jeda buatan) → sampai goal → tunggu 1,2 dtk (win +
  bintang tersimpan) → `voxel_load` level berikut (nama diverifikasi
  dulu lewat `level_get`) → ulangi.
- Tiap waypoint: timeout 4 dtk, tap ulang sekali, lalu menyerah.
- Gagal 3x berturut-turut → toggle mati sendiri + alasan. Level yang
  tidak bisa di-solve → berhenti + auto-off + alasan (tidak di-skip
  diam-diam). Toggle OFF berhenti total (dicek tiap polling & waypoint).

### 5.4 Buat World Acak (`WorldGen.java` — murni Java, teruji host)

- Jalan acak di tapak ≤ 8×8, tinggi lantai ≤ 4; tiap langkah naik/turun
  maks 1 tingkat (persis gerakan robot) sehingga world terhubung oleh
  konstruksi. Lantai pertama = BOT_YELLOW, terakhir = WIN, 1 STAR di
  tengah jalan, dekorasi yang dijamin tidak menutup jalan/headroom.
- Setiap world diverifikasi Solver (harus OK, 3..60 langkah); gagal 50x
  → lapor gagal. World murni jalan kaki (tanpa air/zapper/motor/bola)
  supaya Auto Play bisa langsung memainkannya.
- Ditulis lewat `sandbox_write` (nama `w_acak_hozin_N.bin`) + verifikasi
  berkas ada dengan ukuran sama; kartu + QR muncul karena game sendiri
  mendaftar file sandbox sebagai kartu level (mekanisme yang sama dengan
  level hasil scan QR). Bila kartu belum muncul, user ketuk "Muat Ulang
  Game" (kill proses, seperti Unlock) — game memindai sandbox saat start.

### 5.5 Batasan yang diketahui (v1.1, dilaporkan apa adanya)

- Auto Play hanya menyelesaikan level jalan-kaki (38/144). Drag
  (geser blok), motor, bola, dan timing zapper BELUM didukung — solver
  menamai alasannya dan berhenti.
- Pindah level otomatis memakai urutan `level_infos.json`
  (Story lalu Featured) + verifikasi nama via `level_get`; bila urutan
  registry game berbeda, level berikut yang dimuat tetap level valid
  (namanya diverifikasi), tapi mungkin bukan "berikutnya" menurut kartu
  game. Level workshop/acak tidak punya "berikutnya" → Auto Play
  berhenti setelah selesai dengan pesan jelas.
- Deteksi posisi robot memakai badan Bullet (seperti teleport v1.0):
  toleransi kedatangan ±0,65 sel horizontal, dy −0,2..+1,0. Bila
  identifikasi badan salah (multi-bot), executor mendeteksi macet lewat
  timeout → strike → auto-off.
- Kartu world acak dijamin ditulis ke sandbox + terverifikasi ada;
  kemunculannya di daftar level bergantung pada pemindaian game
  (saat masuk halaman level / start) — tombol "Muat Ulang Game"
  disediakan untuk itu.
