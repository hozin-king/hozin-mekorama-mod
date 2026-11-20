# Catatan Teknis — Hozin Mekorama Mod v1.2

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

## 5. Auto Play & Buat World Acak (v1.2)

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
| `voxel_add` (0x14ec94) | `voxel_add(pos[3], type, rot, lbl, var)` -> 1 ok / 0 gagal. Menulis 4 array voxel + `voxel_update` + `update_dirty_chunks` (+ `power_create` bila tipe punya power). Dipakai executor untuk geser blok. Posisi di luar 0..15 memicu geser seluruh level — jangan pernah dipanggil begitu. |
| `voxel_remove` (0x14ef34) | `voxel_remove(pos[3])` — mengosongkan sel (4 array voxel) + update fisika/render. Dipakai executor untuk geser blok. |
| `voxels_rot` / `voxels_var` / `voxels_lbl` | Array 4096 byte per sel: byte ekstra bit-0 (rot — sumbu slider), bit-2 (var), label. Dibaca payload untuk sumbu slider live + byte blok saat digeser. |
| `camera_yaw` / `camera_yaw_target` (0x28110c / 0x281104) | Float yaw kamera + targetnya. `camera_step` (0xdd07c) menginterpolasi yaw ke target tiap frame (gerakan halus). |
| `camera_mod_yaw` (0xdd5b8) | Normalisasi `camera_yaw` + `camera_yaw_target` ke [0, 2π). Dipanggil executor setelah mengubah target yaw. |
| `block_slider_dir` (0x1397d8) + tabel `rots_face_nums` (0x9d5c1) | Arah slider dari byte rot: rot 16..23 -> sumbu Y (vertikal); rot genap -> X; rot ganjil -> Z. Dibuktikan dari tabel + korelasi rel di 144 level. |
| `block_types` (0x2373fc, 68 x 8 byte) | Tabel tipe blok; byte 6 = flag: bit 0 -> +1 byte ekstra (rot), bit 2 -> +1 byte ekstra (var). Dipakai `LevelFormat` (lihat di bawah). |

Format file level — **DIPERBAIKI di v1.2** (REA `read_world_cached`
0x144678 + `write_world` 0x144388, dibuktikan parse 144/144 file asli
byte-per-byte; deskripsi v1.1 "grid 4096 + ekor nol" SALAH):
`[header 4 byte: {versi, 0x13, 0x0d, 0xfc}] [stream zlib]` →
`[1B pjg judul][judul 1..16][1B pjg author][author]` →
`[4096 sel, TIAP sel = 1 byte tipe + byte ekstra sesuai flag block_types
(bit 0 -> +1 byte rot, bit 2 -> +1 byte var)]`. **TIDAK ADA ekor nol** —
yang dulu dikira "ekor" adalah byte ekstra yang terselip di antara sel.
Versi header = 1 bila tipe maks ≤ 48, 2 bila ≤ 52, 3 bila > 52
(dihitung `write_world` dari tipe maks; pembaca menolak versi > 3).
Pembaca game memvalidasi: magic `13 0d fc`, versi ≤ 3, judul 1..16,
tipe ≤ 67, byte rot ≤ 23 — gagal satu saja = level TIDAK termuat sama
sekali (inilah bug world acak v1.1). Grid: `x=i%16, y=(i/16)%16
(vertikal), z=i/256`. Tabel 68 tipe blok dipulihkan dari
`block_type_names` (0x237620) — lihat konstanta di `Solver.java`.

### 5.2 Solver (v1.2, `Solver.java` — murni Java, teruji host)

- **Jalan kaki (v1.1, tidak berubah):** state = sel kaki robot; gerakan:
  jalan datar, naik 1 tingkat, jatuh (jalan ke tetangga kosong lalu jatuh
  ke pijakan walkable pertama; mati bila jatuh ke air / keluar dunia /
  menabrak blok tak bisa dipijak). Heuristik Manhattan (|dx|+|dy|+|dz|).
  Batas 200.000 node / ~2 detik; "Crash Course" selesai 16 ms (**14
  langkah** — dulu 9 di grid naive yang salah), "Flat Path" 4 langkah +
  1 geser.
- **Geser blok (v1.2):** dua perencana, dipakai berurutan.
  1. *Perencana konstruktif* (cepat, terarah): ulangi — (a) coba A* jalan
     kaki saja; (b) bila putus, cari jalur geometris (Dijkstra longgar:
     pijakan diabaikan, sel harus kosong + kepala bebas, biaya +5 untuk
     sel tanpa pijakan, dan sel di atas blok padat bukan-pijakan — mis.
     batu polos tipe 1 — dibuang karena tidak pernah bisa dipijak);
     (c) berjalan sepanjang jalur geometris selagi bisa dipijak, lalu
     isi pijakan di bawah sel pertama yang putus dengan satu blok drag
     (blok tembaga/silinder terdekat; blok harus berhenti tepat di
     target, bertumpu pada blok padat/lantai). Re-planning tiap iterasi.
  2. *A* blok-geser* (cadangan, eksponensial): state = (robot + posisi
     tiap blok geser); aksi jalan (biaya 1), geser slider ±1 sepanjang
     rel (biaya 3; robot ikut naik bila sedang berdiri di atas slider),
     drag blok ke sel kosong dekat robot (Chebyshev ≤ 1, biaya 4 +
     jarak/8) + gravitasi (blok jatuh sampai bertumpu). Prune: drag
     hanya bila pijakan barunya bersebelahan dengan daerah terjangkau
     robot; slider jauh dari robot (> Chebyshev 3) tidak digerakkan
     dulu. Batas sama (200.000 node / ~2 dtk).
- Slider: sumbu dari byte rot (`sliderAxis`: rot ≥ 16 -> Y, genap -> X,
  ganjil -> Z); rel = run RAIL berurutan sepanjang sumbu + sel slider;
  sel yang ditinggal slider kembali jadi RAIL. Drag: target harus
  kosong, tidak menutupi finish, tidak di kolom robot; blok jatuh
  (gravitasi) sampai bertumpu pada blok padat / lantai (y=0).
- Zapper (tesla): fase 1 memblokir sel yang berdekatan dengan tesla; bila
  hanya ada jalan lewat dekat zapper, fase 2 menemukannya tapi status
  HAS_ZAPPER (fail-closed — fase zapper tidak bisa dibaca dari game).
- Bila jalan buntu, BFS "longgar" (elemen dinamis dianggap tembus)
  menamai penyebab paling mungkin: motor / bola / butuh geser blok.
- **Hasil terukur pada 144 level bawaan: 92 solvable** (49 murni jalan
  kaki + 43 butuh geser blok; v1.1: 38 jalan kaki). Uji host
  (`SolverTest`) mengunci angka ini (ambang ≥ 85 total, ≥ 45 jalan
  kaki) + 4 level uji v1.2 ("Ride C Over Sea" HARUS solvable, rencana
  aksi terverifikasi simulasi independen) + kasus sintetik
  motor/bola/dinding/tanpa-bot.

### 5.3 Executor Auto Play (`AutoPlay.java`)

- Switch ON/OFF (saklar Android asli, v1.2); loop: baca grid + rot live
  → solve (dengan rot live untuk sumbu slider) → kenali level saat ini
  (cocokkan grid dengan 144 file bawaan) → jalankan rencana aksi
  (jalan + geser blok) → sampai goal → tunggu 1,2 dtk (win + bintang
  tersimpan) → `voxel_load` level berikut (nama diverifikasi dulu lewat
  `level_get`) → ulangi.
- Jalan: `figure_tap` di UI thread game + polling posisi robot tiap
  ~16 ms, tanpa jeda buatan. Tiap waypoint: timeout 4 dtk, tap ulang
  sekali, lalu putar kamera 90° + tap ulang (waypoint mungkin tertutup
  dari sudut ini), baru menyerah.
- Geser blok: `nativeMoveBlock` (voxel_remove + voxel_add milik game,
  di UI thread) + verifikasi grid live (blok sudah di sel tujuan, sel
  asal kosong / jadi rel untuk slider). Gagal -> berhenti + lapor.
- Kamera: `nativeRotateCamera` (target yaw ±90°, `camera_mod_yaw`) —
  `camera_step` menganimasikannya halus. Diputar bila waypoint macet +
  sesekali natural tiap ~10 waypoint (arah selang-seling).
- Gagal 3x berturut-turut → saklar mati sendiri + alasan. Level yang
  tidak bisa di-solve → berhenti + auto-off + alasan (tidak di-skip
  diam-diam). Saklar OFF berhenti total (dicek tiap polling & aksi).

### 5.4 Buat World Acak (`WorldGen.java` — murni Java, teruji host)

- Jalan acak di tapak ≤ 8×8, tinggi lantai ≤ 4; tiap langkah naik/turun
  maks 1 tingkat (persis gerakan robot) sehingga world terhubung oleh
  konstruksi. Lantai pertama = BOT_YELLOW, terakhir = WIN, 1 STAR di
  tengah jalan, dekorasi yang dijamin tidak menutup jalan/headroom.
- Setiap world diverifikasi Solver (harus OK, 3..60 langkah); gagal 50x
  → lapor gagal. World murni jalan kaki (tanpa air/zapper/motor/bola)
  supaya Auto Play bisa langsung memainkannya.
- **Perbaikan v1.2:** `LevelFormat.encode` kini menulis format berselip
  yang benar (byte ekstra per sel sesuai flag `block_types`, tanpa
  ekor) — world hasil generate lolos validasi `read_world_cached`
  (magic/versi/tipe/rot) sehingga game bisa me-load + me-play-nya.
  Uji host memverifikasi rantai penuh: `gameWouldLoad(binBytes)` +
  solver OK + 1 bot + 1 win + author "Hozin" + roundtrip identik
  (20/20 world, seed tetap).
- Ditulis lewat `sandbox_write` (nama `w_acak_hozin_N.bin`) + verifikasi
  berkas ada dengan ukuran sama; kartu + QR muncul karena game sendiri
  mendaftar file sandbox sebagai kartu level (mekanisme yang sama dengan
  level hasil scan QR). Bila kartu belum muncul, user ketuk "Muat Ulang
  Game" (kill proses, seperti Unlock) — game memindai sandbox saat start.

### 5.5 Batasan yang diketahui (v1.2, dilaporkan apa adanya)

- Auto Play menyelesaikan **92/144** level bawaan (49 jalan kaki + 43
  geser blok). Sisanya butuh mekanisme yang belum dimodelkan — solver
  menamai alasannya dan berhenti:
  - **Motor / bola** — gerak motor dan fisika bola belum dimodelkan.
  - **Kotak dorong (SOKOBAN)** — mekanik dorong beda dari drag.
  - **Timing zapper (tesla)** — solver menghindari area zapper; bila
    satu-satunya jalan lewat dekat zapper, ia berhenti.
  - **Logika / tombol / timer** — level yang butuh urutan tombol/sinyal
    belum dimodelkan.
- **Geser slider:** naik slider (robot ikut bergerak bersama slider)
  dimodelkan solver tapi belum teruji di perangkat. Rel di sel yang
  ditinggal slider dipulihkan oleh payload (sesuai model solver).
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
