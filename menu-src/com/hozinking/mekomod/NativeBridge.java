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
}
