package com.hozinking.mekomod;

/**
 * Jembatan JNI ke payload native libmekomod.so yang dimuat oleh proses game
 * itu sendiri (bukan injector/root). Semua method native mengembalikan
 * String status yang JUJUR: apa yang ditemukan payload dan apa yang
 * berhasil/gagal dilakukan — menu menampilkannya apa adanya.
 */
public final class NativeBridge {
    private NativeBridge() {}

    /** true bila libmekomod.so berhasil dimuat dari APK mod ini. */
    public static final boolean loaded;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("mekomod");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        loaded = ok;
    }

    /** Mulai inisialisasi payload (resolusi simbol libmekorama.so). */
    public static native String nativeInit();

    /** Ringkasan status payload saat ini. */
    public static native String nativeStatus();

    /**
     * Coba teleport robot ke blok finish.
     * EKSPERIMENTAL — lihat docs/TECHNICAL.md. Mengembalikan laporan
     * langkah demi langkah; kegagalan di tengah jalan dilaporkan apa
     * adanya, tidak ditutupi.
     */
    public static native String nativeTeleport();

    // ------------------------------------------------------------------
    // Auto Play (v1.1) — semua panggilan game-state (tap / load / tulis)
    // HARUS dipanggil dari UI thread game (AutoPlay menjaminnya):
    // figure_tap / voxel_load / sandbox_write menyentuh state engine
    // yang juga dipakai game loop. Pembacaan (grid / posisi robot) aman
    // dari thread mana pun.
    // ------------------------------------------------------------------

    /** Salinan grid voxel level aktif (4096 byte), null bila belum init. */
    public static native byte[] nativeGetVoxels();

    /**
     * Ketuk sel grid (indeks 0..4095) lewat figure_tap milik game —
     * robot berjalan ke sana dengan pathfinding game sendiri.
     * Mengembalikan 0 bila dipanggil, < 0 bila gagal (simbol tidak ada /
     * payload belum init). HARUS dari UI thread.
     */
    public static native int nativeTapCell(int cellIndex);

    /** Kenali badan robot (di-cache) dari sel awal solver. 0 = ok. */
    public static native int nativeFindRobotBody(int startCellIndex);

    /** Posisi badan robot dalam satuan sel {x, y, z} (float), null bila gagal. */
    public static native float[] nativeRobotCellPos();

    /** Lupakan cache badan robot (dipanggil setelah ganti level). */
    public static native void nativeResetRobotCache();

    /**
     * Muat level berdasarkan indeks registry game (level_get).
     * Mengembalikan 0 bila ok, < 0 bila gagal (indeks di luar jangkauan /
     * nama kosong / simbol tidak ada). HARUS dari UI thread.
     */
    public static native int nativeLoadLevel(int levelIndex);

    /** Cari indeks registry level dari nama file-nya, -1 bila tidak ada. */
    public static native int nativeFindLevelIndex(String fileName);

    /** Nama file level pada indeks registry, null bila tidak ada. */
    public static native String nativeLevelName(int levelIndex);

    /** Jumlah level di registry game, -1 bila tidak bisa dibaca. */
    public static native int nativeNumLevels();

    /**
     * Tulis world ke sandbox game lewat sandbox_write milik game, lalu
     * verifikasi berkasnya ada. 0 = ok, < 0 = gagal. HARUS dari UI thread.
     */
    public static native int nativeWriteWorld(String fileName, byte[] data);

    /** Path lengkap sandbox game untuk sebuah nama file, null bila gagal. */
    public static native String nativeSandboxPath(String fileName);
}
