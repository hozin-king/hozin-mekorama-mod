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

    g_status = fmt("Payload aktif. lib base=0x%lx | db_worlds:%s voxels:%s "
                   "world_min/max:%s cheatflag:%s",
                   (unsigned long)g_lib_base,
                   g_db_worlds ? "OK" : "TIDAK ADA",
                   g_voxels ? "OK" : "TIDAK ADA",
                   (g_world_min && g_world_max) ? "OK" : "TIDAK ADA",
                   g_unlock_cheat ? "OK" : "TIDAK ADA");
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

} // extern "C"
