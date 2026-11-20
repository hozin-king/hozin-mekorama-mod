// Hozin Mekorama Mod — payload native (libmekomod.so)
//
// Dimuat oleh proses game itu sendiri lewat System.loadLibrary("mekomod")
// dari lapisan menu Java. BUKAN injector, BUKAN ptrace, tidak butuh root.
//
// Tugas payload v1: fitur Teleport ke Finish yang EKSPERIMENTAL.
//
// Dasar statis (dari REA libmekorama.so 1.7.2, tidak di-strip):
//   - Simbol ekspor yang dipakai (diselesaikan via dlsym, tanpa offset
//     hardcode antar-build): "db_worlds" (penunjuk dunia fisika Bullet),
//     "voxels_typ" (4096 byte tipe blok grid 16x16x16), "world_min" /
//     "world_max" (3 float batas dunia), "level_unlock_cheat" (flag
//     cheat bawaan game — hanya dibaca untuk diagnostik).
//   - Tata letak Bullet yang diasumsikan (versi Bullet yang dibundel
//     game; TIDAK terverifikasi runtime):
//       btCollisionObject: [vtable][btTransform world][btTransform interp]...
//       btTransform = basis 3*16 byte + origin 16 byte -> origin di
//       +0x38 (64-bit) / +0x34 (32-bit); origin interpolasi +0x78 / +0x74.
//       btCollisionWorld: member pertama m_collisionObjects
//       (btAlignedObjectArray: data*, size, capacity) — offset kandidat
//       divalidasi dari isi (ukuran wajar + vtable elemen di rentang lib).
//
// Heuristik teleport (dijelaskan juga di docs/TECHNICAL.md):
//   1. Blok "unik" = tipe yang muncul tepat 1x di voxels_typ level aktif.
//      Pusat massa sel unik ~ posisi awal robot (bagian-bagian robot
//      berkelompok), sel unik TERJAUH dari pusat massa = kandidat blok
//      finish. Heuristik ini bisa salah — hasilnya selalu dilaporkan.
//   2. Badan robot = objek fisika yang origin-nya paling dekat ke pusat
//      massa sel unik (posisi awal robot).
//   3. Origin badan robot (dan origin interpolasinya) ditulis ke posisi
//      dunia sel finish, 3x berturut-turut untuk melawan sisa kecepatan
//      (offset kecepatan linear btRigidBody tidak cukup pasti untuk
//      ditulis buta — keterbatasan ini dilaporkan apa adanya).
//
// Semua fungsi mengembalikan laporan teks jujur untuk ditampilkan menu.

#include <jni.h>
#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "MEKOMOD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr size_t kPtr = sizeof(void*);
#if defined(__LP64__)
constexpr size_t kOriginOff = 0x38;       // btCollisionObject + vtable + basis
constexpr size_t kInterpOriginOff = 0x78; // origin transform interpolasi
#else
constexpr size_t kOriginOff = 0x34;
constexpr size_t kInterpOriginOff = 0x74;
#endif

std::atomic<bool> g_init_started{false};
std::atomic<bool> g_init_done{false};
std::string g_status = "Payload belum diinisialisasi.";
uintptr_t g_lib_base = 0;
void **g_db_worlds = nullptr;   // alamat global db_worlds (isinya: penunjuk dunia)
uint8_t *g_voxels = nullptr;    // voxels_typ[4096]
float *g_world_min = nullptr;   // world_min[3]
float *g_world_max = nullptr;   // world_max[3]
int *g_unlock_cheat = nullptr;  // level_unlock_cheat

// ---- v1.1: Auto Play / Buat World Acak ----
using FigureTapFn = void (*)(int, void *);
using VoxelLoadFn = void (*)(int, int);
using LevelGetFn = void *(*)(int);
using SandboxWriteFn = void (*)(const char *, const void *, int);
using SandboxPathFn = char *(*)(const char *);
FigureTapFn g_figure_tap = nullptr;      // figure_tap(voxel_index, vec3*)
VoxelLoadFn g_voxel_load = nullptr;      // voxel_load(level_index, flags)
LevelGetFn g_level_get = nullptr;        // level_get(index) -> struct 48 byte
SandboxWriteFn g_sandbox_write = nullptr; // sandbox_write(nama, data, len)
SandboxPathFn g_sandbox_path = nullptr;  // sandbox_path(nama) -> path statis
int *g_num_levels = nullptr;             // num_levels (jumlah registry level)
void *g_robot_body = nullptr;            // badan robot Bullet (cache per level)

// ---- v1.2: geser blok (drag/slider) + putar kamera ----
// voxel_add(pos[3], type, rot, lbl, var) -> 1 ok / 0 gagal; posisi
// di luar 0..15 memicu geser seluruh level (jangan dipanggil begitu).
// voxel_remove(pos[3]) -> mengosongkan sel (4 array voxel).
// Argumen rot: untuk tipe yang byte-5 block_types-nya != 0, rot
// diacak game (hash); untuk slider (byte-5 = 0) rot yang dilewat
// dipakai apa adanya — jadi sumbu slider terjaga.
// camera_yaw_target: camera_step menginterpolasi camera_yaw ke arah
// target tiap frame (gerakan halus seperti swipe); camera_mod_yaw
// menormalisasi keduanya ke [0, 2*pi).
using VoxelAddFn = int (*)(int *, int, int, int, int);
using VoxelRemoveFn = void (*)(int *);
using CameraModYawFn = void (*)();
VoxelAddFn g_voxel_add = nullptr;
VoxelRemoveFn g_voxel_remove = nullptr;
CameraModYawFn g_camera_mod_yaw = nullptr;
uint8_t *g_voxels_rot = nullptr;  // voxels_rot[4096] (byte ekstra bit-0)
uint8_t *g_voxels_lbl = nullptr;  // voxels_lbl[4096]
uint8_t *g_voxels_var = nullptr;  // voxels_var[4096] (byte ekstra bit-2)
float *g_camera_yaw = nullptr;        // camera_yaw
float *g_camera_yaw_target = nullptr; // camera_yaw_target

int find_lib_cb(dl_phdr_info *info, size_t, void *) {
    if (info->dlpi_name && std::strstr(info->dlpi_name, "libmekorama.so")) {
        g_lib_base = info->dlpi_addr;
        return 1;
    }
    return 0;
}

std::string fmt(const char *pattern, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, pattern);
    vsnprintf(buf, sizeof(buf), pattern, ap);
    va_end(ap);
    return std::string(buf);
}

void *init_thread(void *) {
    dl_iterate_phdr(find_lib_cb, nullptr);
    if (!g_lib_base) {
        g_status = "Init GAGAL: libmekorama.so tidak ditemukan di proses.";
        LOGE("%s", g_status.c_str());
        return nullptr;
    }
    void *h = dlopen("libmekorama.so", RTLD_NOW | RTLD_NOLOAD);
    if (!h) {
        g_status = "Init GAGAL: dlopen libmekorama.so gagal.";
        LOGE("%s", g_status.c_str());
        return nullptr;
    }
    g_db_worlds = reinterpret_cast<void **>(dlsym(h, "db_worlds"));
    g_voxels = reinterpret_cast<uint8_t *>(dlsym(h, "voxels_typ"));
    g_world_min = reinterpret_cast<float *>(dlsym(h, "world_min"));
    g_world_max = reinterpret_cast<float *>(dlsym(h, "world_max"));
    g_unlock_cheat = reinterpret_cast<int *>(dlsym(h, "level_unlock_cheat"));
    // v1.1
    g_figure_tap = reinterpret_cast<FigureTapFn>(dlsym(h, "figure_tap"));
    g_voxel_load = reinterpret_cast<VoxelLoadFn>(dlsym(h, "voxel_load"));
    g_level_get = reinterpret_cast<LevelGetFn>(dlsym(h, "level_get"));
    g_sandbox_write = reinterpret_cast<SandboxWriteFn>(dlsym(h, "sandbox_write"));
    g_sandbox_path = reinterpret_cast<SandboxPathFn>(dlsym(h, "sandbox_path"));
    g_num_levels = reinterpret_cast<int *>(dlsym(h, "num_levels"));
    // v1.2
    g_voxel_add = reinterpret_cast<VoxelAddFn>(dlsym(h, "voxel_add"));
    g_voxel_remove = reinterpret_cast<VoxelRemoveFn>(dlsym(h, "voxel_remove"));
    g_camera_mod_yaw = reinterpret_cast<CameraModYawFn>(dlsym(h, "camera_mod_yaw"));
    g_voxels_rot = reinterpret_cast<uint8_t *>(dlsym(h, "voxels_rot"));
    g_voxels_lbl = reinterpret_cast<uint8_t *>(dlsym(h, "voxels_lbl"));
    g_voxels_var = reinterpret_cast<uint8_t *>(dlsym(h, "voxels_var"));
    g_camera_yaw = reinterpret_cast<float *>(dlsym(h, "camera_yaw"));
    g_camera_yaw_target = reinterpret_cast<float *>(dlsym(h, "camera_yaw_target"));

    g_status = fmt("Payload aktif. lib base=0x%lx | db_worlds:%s voxels:%s "
                   "world_min/max:%s cheatflag:%s | figure_tap:%s voxel_load:%s "
                   "level_get:%s sandbox_write:%s sandbox_path:%s num_levels:%s "
                   "| voxel_add:%s voxel_remove:%s camera_mod_yaw:%s voxels_rot:%s "
                   "camera_yaw:%s",
                   (unsigned long)g_lib_base,
                   g_db_worlds ? "OK" : "TIDAK ADA",
                   g_voxels ? "OK" : "TIDAK ADA",
                   (g_world_min && g_world_max) ? "OK" : "TIDAK ADA",
                   g_unlock_cheat ? "OK" : "TIDAK ADA",
                   g_figure_tap ? "OK" : "TIDAK ADA",
                   g_voxel_load ? "OK" : "TIDAK ADA",
                   g_level_get ? "OK" : "TIDAK ADA",
                   g_sandbox_write ? "OK" : "TIDAK ADA",
                   g_sandbox_path ? "OK" : "TIDAK ADA",
                   g_num_levels ? "OK" : "TIDAK ADA",
                   g_voxel_add ? "OK" : "TIDAK ADA",
                   g_voxel_remove ? "OK" : "TIDAK ADA",
                   g_camera_mod_yaw ? "OK" : "TIDAK ADA",
                   g_voxels_rot ? "OK" : "TIDAK ADA",
                   (g_camera_yaw && g_camera_yaw_target) ? "OK" : "TIDAK ADA");
    LOGI("%s", g_status.c_str());
    g_init_done = true;
    return nullptr;
}

bool finite3(const float *v) {
    return std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]) &&
           std::fabs(v[0]) < 1e6f && std::fabs(v[1]) < 1e6f && std::fabs(v[2]) < 1e6f;
}

struct Vec3 { float x, y, z; };

float dist(Vec3 a, Vec3 b) {
    float dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
    return std::sqrt(dx * dx + dy * dy + dz * dz);
}

std::string do_teleport() {
    if (!g_init_done) return "Teleport: payload belum init — buka menu lagi sebentar lagi.";
    if (!g_voxels) return "Teleport GAGAL: simbol voxels_typ tidak ada.";
    if (!g_db_worlds) return "Teleport GAGAL: simbol db_worlds tidak ada.";

    // ---- 1) Sel unik & heuristik finish ----
    int count[256] = {0};
    for (int i = 0; i < 4096; i++) count[g_voxels[i]]++;
    struct Cell { int x, y, z, type; };
    std::vector<Cell> unique;
    for (int i = 0; i < 4096; i++) {
        if (count[g_voxels[i]] == 1) {
            // pemetaan indeks voxel (asumsi A): x = i%16, y = (i/16)%16
            // (sumbu vertikal), z = i/256.
            unique.push_back({i % 16, (i / 16) % 16, i / 256, g_voxels[i]});
        }
    }
    if (unique.size() < 2) {
        return fmt("Teleport GAGAL: sel blok unik hanya %zu — heuristik finish "
                   "tidak bisa jalan di level ini.", unique.size());
    }
    Vec3 centroid{0, 0, 0};
    for (auto &c : unique) { centroid.x += c.x; centroid.y += c.y; centroid.z += c.z; }
    centroid.x /= unique.size(); centroid.y /= unique.size(); centroid.z /= unique.size();

    Cell goal = unique[0];
    float best = -1;
    for (auto &c : unique) {
        Vec3 p{(float)c.x, (float)c.y, (float)c.z};
        float d = dist(p, centroid);
        if (d > best) { best = d; goal = c; }
    }

    // ---- 2) Pemetaan sel -> dunia ----
    float mn[3] = {0, 0, 0}, mx[3] = {16, 16, 16};
    bool mapping_ok = false;
    if (g_world_min && g_world_max) {
        float tmn[3] = {g_world_min[0], g_world_min[1], g_world_min[2]};
        float tmx[3] = {g_world_max[0], g_world_max[1], g_world_max[2]};
        if (finite3(tmn) && finite3(tmx)) {
            mapping_ok = true;
            for (int i = 0; i < 3; i++) {
                float span = tmx[i] - tmn[i];
                if (span < 0.5f || span > 100000.0f) mapping_ok = false;
            }
            if (mapping_ok) { std::memcpy(mn, tmn, sizeof(mn)); std::memcpy(mx, tmx, sizeof(mx)); }
        }
    }
    auto cell_to_world = [&](int cx, int cy, int cz) {
        return Vec3{mn[0] + (cx + 0.5f) / 16.0f * (mx[0] - mn[0]),
                    mn[1] + (cy + 0.5f) / 16.0f * (mx[1] - mn[1]),
                    mn[2] + (cz + 0.5f) / 16.0f * (mx[2] - mn[2])};
    };
    Vec3 goal_world = cell_to_world(goal.x, goal.y, goal.z);
    goal_world.y += 1.0f; // berdiri DI ATAS blok finish, bukan di dalamnya
    Vec3 start_world = cell_to_world((int)std::lround(centroid.x),
                                     (int)std::lround(centroid.y),
                                     (int)std::lround(centroid.z));

    // ---- 3) Enumerasi objek fisika ----
    void *world = *g_db_worlds;
    if (!world) return "Teleport GAGAL: db_worlds masih kosong — masuk ke level dulu.";
    auto vtable_in_lib = [&](void *body) {
        if (!body) return false;
        uintptr_t vt = *reinterpret_cast<uintptr_t *>(body);
        return vt >= g_lib_base && vt < g_lib_base + 0x4000000;
    };
    void **bodies = nullptr;
    int body_count = 0;
    size_t used_off = 0;
    const size_t candidates[] = {kPtr, kPtr + 8, kPtr + 16, kPtr + 24, kPtr + 32};
    for (size_t off : candidates) {
        auto *base = reinterpret_cast<uint8_t *>(world);
        void **data = *reinterpret_cast<void ***>(base + off);
        int size = *reinterpret_cast<int *>(base + off + kPtr);
        if (!data || size < 1 || size > 8192) continue;
        if (vtable_in_lib(data[0]) && vtable_in_lib(data[size - 1])) {
            bodies = data; body_count = size; used_off = off; break;
        }
    }
    if (!bodies) {
        return "Teleport GAGAL: array objek fisika Bullet tidak teridentifikasi "
               "(offset tidak cocok) — fitur ini butuh kalibrasi dari uji HP.";
    }

    // ---- 4) Pilih badan robot: origin terdekat ke posisi awal ----
    void *bot = nullptr;
    Vec3 bot_from{0, 0, 0};
    float bot_dist = 1e30f;
    int readable = 0;
    for (int i = 0; i < body_count; i++) {
        auto *b = reinterpret_cast<uint8_t *>(bodies[i]);
        if (!b || !vtable_in_lib(b)) continue;
        float *origin = reinterpret_cast<float *>(b + kOriginOff);
        if (!finite3(origin)) continue;
        readable++;
        Vec3 p{origin[0], origin[1], origin[2]};
        float d = dist(p, start_world);
        if (d < bot_dist) { bot_dist = d; bot = bodies[i]; bot_from = p; }
    }
    if (!bot) return "Teleport GAGAL: tidak ada badan fisika dengan origin terbaca.";
    if (bot_dist > 10.0f) {
        return fmt("Teleport DIBATALKAN: kandidat robot terlalu jauh dari posisi "
                   "awal (%.1f satuan) — kemungkinan salah identifikasi.", bot_dist);
    }

    // ---- 5) Tulis posisi (3x, melawan sisa kecepatan) ----
    auto *bb = reinterpret_cast<uint8_t *>(bot);
    float *origin = reinterpret_cast<float *>(bb + kOriginOff);
    float *interp = reinterpret_cast<float *>(bb + kInterpOriginOff);
    for (int round = 0; round < 3; round++) {
        origin[0] = goal_world.x; origin[1] = goal_world.y; origin[2] = goal_world.z;
        interp[0] = goal_world.x; interp[1] = goal_world.y; interp[2] = goal_world.z;
        if (round < 2) usleep(120 * 1000);
    }
    Vec3 after{origin[0], origin[1], origin[2]};

    return fmt("Teleport dicoba (EKSPERIMENTAL):\n"
               "- Blok finish kandidat: tipe %d di sel (%d,%d,%d), dari %zu sel unik\n"
               "- Objek fisika: %d terbaca dari %d (offset array +%zu)\n"
               "- Robot dipindah dari (%.1f, %.1f, %.1f) ke (%.1f, %.1f, %.1f)%s\n"
               "- Kecepatan TIDAK di-nol-kan (offset btRigidBody belum pasti) — "
               "robot bisa bergeser sedikit.\n"
               "Kalau robot tidak berpindah / salah tempat, laporkan teks ini.",
               goal.type, goal.x, goal.y, goal.z, unique.size(),
               readable, body_count, used_off,
               bot_from.x, bot_from.y, bot_from.z,
               after.x, after.y, after.z,
               mapping_ok ? "" : " [pemetaan dunia pakai fallback 16 satuan]");
}

// ------------------------------------------------------------------
// v1.1 — Auto Play & Buat World Acak
//
// Prinsip: semua pemanggilan memakai fungsi ekspor engine sendiri
// (dlsym, tanpa offset hardcode). Panggilan yang menyentuh state engine
// (tap / load / tulis) HARUS dari UI thread game — Java menjaminnya.
// ------------------------------------------------------------------

bool get_bounds(float mn[3], float mx[3]) {
    mn[0] = 0; mn[1] = 0; mn[2] = 0;
    mx[0] = 16; mx[1] = 16; mx[2] = 16;
    if (!g_world_min || !g_world_max) return false;
    float tmn[3] = {g_world_min[0], g_world_min[1], g_world_min[2]};
    float tmx[3] = {g_world_max[0], g_world_max[1], g_world_max[2]};
    if (!finite3(tmn) || !finite3(tmx)) return false;
    for (int i = 0; i < 3; i++) {
        float span = tmx[i] - tmn[i];
        if (span < 0.5f || span > 100000.0f) return false;
    }
    std::memcpy(mn, tmn, sizeof(float) * 3);
    std::memcpy(mx, tmx, sizeof(float) * 3);
    return true;
}

Vec3 cell_to_world2(int cx, int cy, int cz) {
    float mn[3], mx[3];
    get_bounds(mn, mx);
    return Vec3{mn[0] + (cx + 0.5f) / 16.0f * (mx[0] - mn[0]),
                mn[1] + (cy + 0.5f) / 16.0f * (mx[1] - mn[1]),
                mn[2] + (cz + 0.5f) / 16.0f * (mx[2] - mn[2])};
}

// Posisi dunia -> koordinat sel pecahan (pusat badan robot -> sel).
bool world_to_cellf(Vec3 w, float out[3]) {
    float mn[3], mx[3];
    get_bounds(mn, mx);
    for (int i = 0; i < 3; i++) {
        float span = mx[i] - mn[i];
        if (span <= 0) return false;
        out[i] = ( (&w.x)[i] - mn[i]) / span * 16.0f - 0.5f;
    }
    return true;
}

bool vtable_in_lib2(void *body) {
    if (!body || !g_lib_base) return false;
    uintptr_t vt = *reinterpret_cast<uintptr_t *>(body);
    return vt >= g_lib_base && vt < g_lib_base + 0x4000000;
}

// Enumerasi badan fisika Bullet (sama seperti teleport v1.0).
// Mengembalikan jumlah badan yang terbaca; bodies diisi bila muat.
int enumerate_bodies(void **out, int max_out) {
    if (!g_db_worlds) return -1;
    void *world = *g_db_worlds;
    if (!world) return -1;
    const size_t candidates[] = {kPtr, kPtr + 8, kPtr + 16, kPtr + 24, kPtr + 32};
    for (size_t off : candidates) {
        auto *base = reinterpret_cast<uint8_t *>(world);
        void **data = *reinterpret_cast<void ***>(base + off);
        int size = *reinterpret_cast<int *>(base + off + kPtr);
        if (!data || size < 1 || size > 8192) continue;
        if (vtable_in_lib2(data[0]) && vtable_in_lib2(data[size - 1])) {
            int n = 0;
            for (int i = 0; i < size && n < max_out; i++) {
                if (vtable_in_lib2(data[i])) out[n++] = data[i];
            }
            return n;
        }
    }
    return -1;
}

// Kenali badan robot: yang origin-nya paling dekat ke sel awal solver.
int do_find_robot_body(int start_cell) {
    if (!g_init_done) return -1;
    if (start_cell < 0 || start_cell >= 4096) return -2;
    g_robot_body = nullptr;
    int cx = start_cell % 16, cy = (start_cell / 16) % 16, cz = start_cell / 256;
    Vec3 ref = cell_to_world2(cx, cy, cz);
    void *bodies[512];
    int n = enumerate_bodies(bodies, 512);
    if (n <= 0) return -3;
    void *best = nullptr;
    float best_d = 1e30f;
    for (int i = 0; i < n; i++) {
        auto *b = reinterpret_cast<uint8_t *>(bodies[i]);
        float *origin = reinterpret_cast<float *>(b + kOriginOff);
        if (!finite3(origin)) continue;
        float d = dist(Vec3{origin[0], origin[1], origin[2]}, ref);
        if (d < best_d) { best_d = d; best = bodies[i]; }
    }
    if (!best || best_d > 10.0f) return -4;
    g_robot_body = best;
    return 0;
}

// Posisi badan robot (cache) dalam satuan sel. false bila tidak terbaca.
bool do_robot_cell_pos(float out[3]) {
    if (!g_robot_body) return false;
    auto *b = reinterpret_cast<uint8_t *>(g_robot_body);
    if (!vtable_in_lib2(b)) { g_robot_body = nullptr; return false; }
    float *origin = reinterpret_cast<float *>(b + kOriginOff);
    if (!finite3(origin)) { g_robot_body = nullptr; return false; }
    return world_to_cellf(Vec3{origin[0], origin[1], origin[2]}, out);
}

// Muat level registry. 0 = ok, <0 = gagal (lihat NativeBridge).
int do_load_level(int index) {
    if (!g_init_done) return -1;
    if (!g_level_get) return -5;
    if (!g_voxel_load) return -6;
    void *lv = g_level_get(index);
    if (!lv) return -2; // level_get NULL = indeks di luar jangkauan
    const char *name = *reinterpret_cast<const char **>(lv);
    if (!name || !name[0]) return -3;
    g_robot_body = nullptr;
    g_voxel_load(index, 0);
    return 0;
}

int do_find_level_index(const char *file_name) {
    if (!g_level_get || !file_name || !file_name[0]) return -1;
    int limit = (g_num_levels && *g_num_levels > 0 && *g_num_levels <= 4096)
                    ? *g_num_levels : 1024;
    for (int i = 0; i < limit; i++) {
        void *lv = g_level_get(i);
        if (!lv) break; // level_get NULL = akhir registry
        const char *name = *reinterpret_cast<const char **>(lv);
        if (name && name[0] && std::strstr(name, file_name)) return i;
    }
    return -1;
}

std::string do_level_name(int index) {
    if (!g_level_get) return std::string();
    void *lv = g_level_get(index);
    if (!lv) return std::string();
    const char *name = *reinterpret_cast<const char **>(lv);
    if (!name || !name[0]) return std::string();
    return std::string(name);
}

int do_write_world(const char *file_name, const uint8_t *data, int len) {
    if (!g_init_done) return -1;
    if (!g_sandbox_write) return -5;
    if (!g_sandbox_path) return -6;
    if (!file_name || !file_name[0] || !data || len <= 0) return -2;
    g_sandbox_write(file_name, data, len);
    // Verifikasi: berkas harus ada di sandbox dengan ukuran yang sama.
    char *path = g_sandbox_path(file_name);
    if (!path || !path[0]) return -3;
    FILE *f = std::fopen(path, "rb");
    if (!f) return -4;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fclose(f);
    if (sz != len) return -7;
    return 0;
}

// ---- v1.2: geser blok + putar kamera ----

// Pindahkan satu blok dari sel asal ke sel tujuan lewat fungsi game
// sendiri (voxel_remove + voxel_add) — voxel + fisika + render ikut
// diperbarui game (voxel_update / update_dirty_chunks).
// Slider (tipe 41): sel yang ditinggal dipulihkan jadi RAIL (33),
// sesuai model solver (rel yang ditinggal slider kembali jadi rel).
// 0 = ok; <0 = gagal.
int do_move_block(int from_cell, int to_cell) {
    if (!g_init_done) return -1;
    if (!g_voxel_add || !g_voxel_remove || !g_voxels) return -5;
    if (from_cell < 0 || from_cell >= 4096 || to_cell < 0 || to_cell >= 4096) return -2;
    if (from_cell == to_cell) return -3;
    int type = g_voxels[from_cell];
    if (type == 0) return -4; // sel asal kosong
    int rot = g_voxels_rot ? g_voxels_rot[from_cell] : 0;
    int lbl = g_voxels_lbl ? g_voxels_lbl[from_cell] : 0;
    int var = g_voxels_var ? g_voxels_var[from_cell] : 0;
    int fx = from_cell % 16, fy = (from_cell / 16) % 16, fz = from_cell / 256;
    int tx = to_cell % 16, ty = (to_cell / 16) % 16, tz = to_cell / 256;
    int from_pos[3] = {fx, fy, fz};
    int to_pos[3] = {tx, ty, tz};
    g_voxel_remove(from_pos);
    if (type == 41) {
        // Slider: pulihkan rel di sel yang ditinggal.
        g_voxel_add(from_pos, 33, 0, 0, 0);
    }
    int rc = g_voxel_add(to_pos, type, rot, lbl, var);
    if (rc != 1) return -6;
    // Verifikasi: blok sudah di sel tujuan, sel asal kosong (atau rel).
    if (g_voxels[to_cell] != type) return -7;
    if (type == 41) {
        if (g_voxels[from_cell] != 33) return -8;
    } else if (g_voxels[from_cell] != 0) {
        return -8;
    }
    return 0;
}

// Putar kamera 90 derajat (direction +1 / -1) lewat target yaw game.
// camera_step menganimasikannya dengan halus (interpolasi tiap frame),
// seperti swipe pemain — bukan lompat instan. 0 = ok; <0 = gagal.
int do_rotate_camera(int direction) {
    if (!g_init_done) return -1;
    if (!g_camera_mod_yaw || !g_camera_yaw_target) return -5;
    if (direction != 1 && direction != -1) return -2;
    const float half_pi = 1.57079632679f;
    *g_camera_yaw_target += direction * half_pi;
    g_camera_mod_yaw();
    return 0;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeInit(JNIEnv *env, jclass) {
    if (!g_init_started.exchange(true)) {
        pthread_t th;
        pthread_create(&th, nullptr, init_thread, nullptr);
        pthread_detach(th);
        return env->NewStringUTF("Inisialisasi payload dimulai…");
    }
    return env->NewStringUTF(g_status.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeStatus(JNIEnv *env, jclass) {
    std::string s = g_status;
    if (g_unlock_cheat) {
        s += fmt(" | level_unlock_cheat (bawaan game) = %d", *g_unlock_cheat);
    }
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeTeleport(JNIEnv *env, jclass) {
    std::string s = do_teleport();
    LOGI("%s", s.c_str());
    return env->NewStringUTF(s.c_str());
}

// ---- v1.1: Auto Play & Buat World Acak ----

JNIEXPORT jbyteArray JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeGetVoxels(JNIEnv *env, jclass) {
    if (!g_init_done || !g_voxels) return nullptr;
    jbyteArray out = env->NewByteArray(4096);
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, 4096,
                            reinterpret_cast<const jbyte *>(g_voxels));
    return out;
}

JNIEXPORT jint JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeTapCell(JNIEnv *env, jclass, jint cell) {
    (void)env;
    if (!g_init_done) return -1;
    if (!g_figure_tap) return -2;
    if (cell < 0 || cell >= 4096) return -3;
    // figure_tap(indeks_voxel, NULL): tap internal game ke sel itu —
    // robot berjalan ke sana lewat pathfinding game sendiri (pplan).
    g_figure_tap(static_cast<int>(cell), nullptr);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeFindRobotBody(JNIEnv *env, jclass, jint start_cell) {
    (void)env;
    return do_find_robot_body(static_cast<int>(start_cell));
}

JNIEXPORT jfloatArray JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeRobotCellPos(JNIEnv *env, jclass) {
    float pos[3];
    if (!do_robot_cell_pos(pos)) return nullptr;
    jfloatArray out = env->NewFloatArray(3);
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, 3, pos);
    return out;
}

JNIEXPORT void JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeResetRobotCache(JNIEnv *env, jclass) {
    (void)env;
    g_robot_body = nullptr;
}

JNIEXPORT jint JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeLoadLevel(JNIEnv *env, jclass, jint index) {
    (void)env;
    return do_load_level(static_cast<int>(index));
}

JNIEXPORT jint JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeFindLevelIndex(JNIEnv *env, jclass, jstring file_name) {
    if (!file_name) return -1;
    const char *name = env->GetStringUTFChars(file_name, nullptr);
    if (!name) return -1;
    int r = do_find_level_index(name);
    env->ReleaseStringUTFChars(file_name, name);
    return r;
}

JNIEXPORT jstring JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeLevelName(JNIEnv *env, jclass, jint index) {
    std::string s = do_level_name(static_cast<int>(index));
    if (s.empty()) return nullptr;
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jint JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeNumLevels(JNIEnv *env, jclass) {
    (void)env;
    if (!g_num_levels) return -1;
    return *g_num_levels;
}

JNIEXPORT jint JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeWriteWorld(JNIEnv *env, jclass, jstring file_name, jbyteArray data) {
    if (!file_name || !data) return -2;
    const char *name = env->GetStringUTFChars(file_name, nullptr);
    if (!name) return -2;
    jsize len = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        env->ReleaseStringUTFChars(file_name, name);
        return -2;
    }
    int r = do_write_world(name, reinterpret_cast<const uint8_t *>(bytes),
                           static_cast<int>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    env->ReleaseStringUTFChars(file_name, name);
    return r;
}

JNIEXPORT jstring JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeSandboxPath(JNIEnv *env, jclass, jstring file_name) {
    if (!file_name || !g_sandbox_path) return nullptr;
    const char *name = env->GetStringUTFChars(file_name, nullptr);
    if (!name) return nullptr;
    char *path = g_sandbox_path(name);
    env->ReleaseStringUTFChars(file_name, name);
    if (!path || !path[0]) return nullptr;
    // sandbox_path mengembalikan buffer statis — salin segera.
    std::string copy(path);
    return env->NewStringUTF(copy.c_str());
}

// ---- v1.2: geser blok + putar kamera ----

JNIEXPORT jbyteArray JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeGetVoxelRots(JNIEnv *env, jclass) {
    if (!g_init_done || !g_voxels_rot) return nullptr;
    jbyteArray out = env->NewByteArray(4096);
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, 4096,
                            reinterpret_cast<const jbyte *>(g_voxels_rot));
    return out;
}

JNIEXPORT jint JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeMoveBlock(JNIEnv *env, jclass, jint from_cell, jint to_cell) {
    (void)env;
    return do_move_block(static_cast<int>(from_cell), static_cast<int>(to_cell));
}

JNIEXPORT jint JNICALL
Java_com_hozinking_mekomod_NativeBridge_nativeRotateCamera(JNIEnv *env, jclass, jint direction) {
    (void)env;
    return do_rotate_camera(static_cast<int>(direction));
}

} // extern "C"
