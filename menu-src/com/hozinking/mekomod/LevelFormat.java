package com.hozinking.mekomod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * LevelFormat — format file level Mekorama (.bin), murni Java (tanpa import
 * Android) supaya bisa diuji di host/CI.
 *
 * Format (v1.2 — DIPERBAIKI dari REA read_world_cached 0x144678 +
 * write_world 0x144388 di libmekorama.so 1.7.2, dibuktikan terhadap 144
 * file level asli — parse interleaved cocok 144/144, byte-per-byte):
 *
 *   file = [header 4 byte][stream zlib]
 *   header = {versi, 0x13, 0x0d, 0xfc}; versi = 1 bila tipe blok maks
 *            <= 48, 2 bila <= 52, 3 bila > 52 (dihitung write_world
 *            dari tipe maks; pembaca hanya menolak versi > 3).
 *   isi zlib = [1 byte panjang judul][judul UTF-8, 1..16 byte]
 *              [1 byte panjang author][author UTF-8]
 *              [4096 sel, TIAP sel = 1 byte tipe + byte ekstra
 *               sesuai tabel block_types: bit 0 flag -> +1 byte
 *               (disimpan game di voxels_rot = "rot"), bit 2 flag ->
 *               +1 byte (voxels_var = "var"). TIDAK ADA ekor nol —
 *               yang dulu dikira "ekor 47..148 byte nol" sebenarnya
 *               adalah byte ekstra yang terselip DI ANTARA sel-sel
 *               grid (dan sel-sel ekor grid yang kosong).
 *
 *   grid: indeks i -> x = i%16, y = (i/16)%16 (vertikal), z = i/256.
 *
 * Validasi pembaca game (yang kita tiru di decode): magic harus
 * 13 0d fc, versi <= 3, panjang judul 1..16, tipe blok <= 67,
 * byte ekstra (rot) <= 23. Bila tidak lolos, level TIDAK termuat
 * sama sekali — inilah yang terjadi pada world acak v1.1: encode lama
 * menulis grid polos 4096 byte + ekor nol TANPA byte ekstra, sehingga
 * pembaca game salah parse (byte grid tetangga dibaca sebagai "rot",
 * lalu gagal validasi) — kartu muncul tapi play tidak jalan dan
 * author tampil "Unknown Author".
 *
 * Tipe blok (dari tabel block_type_names di libmekorama.so, 68 entri):
 * lihat konstanta di Solver. Flag per tipe (byte 6 tabel block_types)
 * ada di BLOCK_FLAGS di bawah.
 */
public final class LevelFormat {
    private LevelFormat() {}

    /** Ukuran grid voxel: 16 x 16 x 16. */
    public static final int GRID_SIZE = 4096;
    public static final int DIM = 16;

    /**
     * Flag per tipe blok = byte 6 entri block_types (68 x 8 byte) di
     * libmekorama.so. Bit 0 -> sel membawa +1 byte ekstra (rot);
     * bit 2 -> +1 byte ekstra lagi (var). Bit 1 tidak memengaruhi
     * jumlah byte (dipakai engine untuk hal lain).
     * Dibaca dari .data libmekorama.so (simbol block_types 0x2373fc)
     * dan dibuktikan: parse 144 level asli cocok byte-per-byte.
     */
    public static final int[] BLOCK_FLAGS = {
        0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, // 0..7
        0x01, 0x02, 0x01, 0x00, 0x00, 0x00, 0x01, 0x03, // 8..15
        0x03, 0x00, 0x00, 0x03, 0x01, 0x01, 0x03, 0x02, // 16..23
        0x00, 0x00, 0x03, 0x00, 0x00, 0x01, 0x01, 0x01, // 24..31
        0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x01, // 32..39
        0x00, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, // 40..47
        0x00, 0x01, 0x03, 0x00, 0x00, 0x00, 0x04, 0x04, // 48..55
        0x04, 0x05, 0x05, 0x05, 0x05, 0x01, 0x01, 0x01, // 56..63
        0x04, 0x05, 0x04, 0x05                          // 64..67
    };

    /** Jumlah byte ekstra yang dibawa satu sel bertipe ini (0, 1, atau 2). */
    public static int extraCount(int type) {
        if (type < 0 || type >= BLOCK_FLAGS.length) return 0;
        int f = BLOCK_FLAGS[type];
        return (f & 1) + ((f & 4) != 0 ? 1 : 0);
    }

    /** true bila sel bertipe ini membawa byte ekstra pertama (rot). */
    public static boolean hasRot(int type) {
        return type >= 0 && type < BLOCK_FLAGS.length && (BLOCK_FLAGS[type] & 1) != 0;
    }

    /** true bila sel bertipe ini membawa byte ekstra kedua (var). */
    public static boolean hasVar(int type) {
        return type >= 0 && type < BLOCK_FLAGS.length && (BLOCK_FLAGS[type] & 4) != 0;
    }

    /** Indeks grid dari koordinat sel. */
    public static int index(int x, int y, int z) {
        return x + y * DIM + z * DIM * DIM;
    }

    public static int xOf(int index) { return index % DIM; }
    public static int yOf(int index) { return (index / DIM) % DIM; }
    public static int zOf(int index) { return index / (DIM * DIM); }

    /** Hasil decode satu file level. */
    public static final class Level {
        public final String title;
        public final String author;
        /** Grid 4096 byte (nilai = tipe blok, lihat Solver). */
        public final byte[] grid;
        /** Byte ekstra pertama per sel (voxels_rot game), 0 bila tidak ada. */
        public final byte[] rot;
        /** Byte ekstra kedua per sel (voxels_var game), 0 bila tidak ada. */
        public final byte[] var;
        /** Byte pertama header file (1, 2, atau 3 = versi format). */
        public final int version;

        public Level(String title, String author, byte[] grid,
                     byte[] rot, byte[] var, int version) {
            this.title = title;
            this.author = author;
            this.grid = grid;
            this.rot = rot;
            this.var = var;
            this.version = version;
        }
    }

    /**
     * Decode file .bin (header + zlib) menjadi Level.
     * Meniru validasi read_world_cached milik game: magic, versi,
     * panjang judul, tipe blok, dan byte ekstra diperiksa — file yang
     * tidak lolos DITOLAK (IOException), bukan ditebak-tebak.
     */
    public static Level decode(byte[] fileBytes) throws IOException {
        if (fileBytes == null || fileBytes.length < 4 + 2) {
            throw new IOException("file level terlalu pendek");
        }
        int version = fileBytes[0] & 0xFF;
        if (fileBytes[1] != (byte) 0x13 || fileBytes[2] != (byte) 0x0d
                || fileBytes[3] != (byte) 0xfc) {
            throw new IOException("magic header level rusak (bukan 13 0d fc)");
        }
        if (version < 1 || version > 3) {
            throw new IOException("versi format level tidak dikenal: " + version);
        }
        byte[] raw = inflate(fileBytes, 4, fileBytes.length - 4);
        if (raw.length < 2) {
            throw new IOException("isi level terdekompres terlalu pendek: " + raw.length);
        }
        int p = 0;
        int titleLen = raw[p++] & 0xFF;
        if (titleLen < 1 || titleLen > 16) {
            throw new IOException("panjang judul level tidak valid: " + titleLen);
        }
        if (p + titleLen + 1 > raw.length) throw new IOException("judul level rusak");
        String title = new String(raw, p, titleLen, "UTF-8");
        p += titleLen;
        int authorLen = raw[p++] & 0xFF;
        if (p + authorLen > raw.length) throw new IOException("author level rusak");
        String author = new String(raw, p, authorLen, "UTF-8");
        p += authorLen;
        byte[] grid = new byte[GRID_SIZE];
        byte[] rot = new byte[GRID_SIZE];
        byte[] var = new byte[GRID_SIZE];
        for (int i = 0; i < GRID_SIZE; i++) {
            if (p >= raw.length) {
                throw new IOException("grid level terpotong di sel " + i);
            }
            int type = raw[p++] & 0xFF;
            if (type > 67) {
                throw new IOException("tipe blok tidak valid: " + type + " di sel " + i);
            }
            grid[i] = (byte) type;
            if (hasRot(type)) {
                if (p >= raw.length) {
                    throw new IOException("byte rot hilang di sel " + i);
                }
                int r = raw[p++] & 0xFF;
                if (r > 23) {
                    throw new IOException("byte rot tidak valid: " + r + " di sel " + i);
                }
                rot[i] = (byte) r;
            }
            if (hasVar(type)) {
                if (p >= raw.length) {
                    throw new IOException("byte var hilang di sel " + i);
                }
                var[i] = raw[p++];
            }
        }
        return new Level(title, author, grid, rot, var, version);
    }

    /** Encode grid menjadi file .bin (tanpa byte ekstra — semuanya 0). */
    public static byte[] encode(String title, String author, byte[] grid) throws IOException {
        return encode(title, author, grid, null, null);
    }

    /**
     * Encode grid + byte ekstra menjadi file .bin.
     * rot/var boleh null (dianggap semua 0). Versi header dihitung dari
     * tipe blok maks seperti write_world milik game (<=48 -> 1,
     * <=52 -> 2, >52 -> 3).
     */
    public static byte[] encode(String title, String author, byte[] grid,
                                byte[] rot, byte[] var) throws IOException {
        if (grid == null || grid.length != GRID_SIZE) {
            throw new IOException("grid harus 4096 byte");
        }
        byte[] titleBytes = title.getBytes("UTF-8");
        byte[] authorBytes = author.getBytes("UTF-8");
        if (titleBytes.length < 1 || titleBytes.length > 16) {
            throw new IOException("judul level harus 1..16 byte (dapat "
                    + titleBytes.length + ") — batas pembaca game");
        }
        if (authorBytes.length > 255) {
            throw new IOException("author terlalu panjang (maks 255 byte)");
        }
        int maxType = 0;
        for (int i = 0; i < GRID_SIZE; i++) {
            int t = grid[i] & 0xFF;
            if (t > maxType) maxType = t;
            if (t > 67) throw new IOException("tipe blok tidak valid: " + t);
        }
        int version = maxType > 52 ? 3 : (maxType > 48 ? 2 : 1);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(titleBytes.length);
        raw.write(titleBytes);
        raw.write(authorBytes.length);
        raw.write(authorBytes);
        for (int i = 0; i < GRID_SIZE; i++) {
            int type = grid[i] & 0xFF;
            raw.write(type);
            if (hasRot(type)) {
                int r = (rot != null) ? (rot[i] & 0xFF) : 0;
                if (r > 23) throw new IOException("byte rot tidak valid: " + r);
                raw.write(r);
            }
            if (hasVar(type)) {
                raw.write((var != null) ? (var[i] & 0xFF) : 0);
            }
        }
        byte[] comp = deflate(raw.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(version);
        out.write(0x13);
        out.write(0x0d);
        out.write(0xfc);
        out.write(comp);
        return out.toByteArray();
    }

    /**
     * Simulasi loader game (read_world_cached) terhadap byte file .bin:
     * true bila game akan MEMUAT level ini (bukan cuma "solvable" menurut
     * solver). Dipakai test WorldGen untuk membuktikan rantai penuh
     * generate -> tulis -> game bisa load -> bisa dimainkan.
     */
    public static boolean gameWouldLoad(byte[] fileBytes) {
        try {
            decode(fileBytes);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Hitung berapa sel berisi tipe tertentu. */
    public static int countType(byte[] grid, int type) {
        int n = 0;
        for (int i = 0; i < LevelFormat.GRID_SIZE; i++) {
            if ((grid[i] & 0xFF) == type) n++;
        }
        return n;
    }

    private static byte[] inflate(byte[] data, int off, int len) throws IOException {
        InflaterInputStream inf = new InflaterInputStream(new ByteArrayInputStream(data, off, len));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = inf.read(buf)) > 0) out.write(buf, 0, n);
        inf.close();
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DeflaterOutputStream def = new DeflaterOutputStream(out);
        def.write(data);
        def.close();
        return out.toByteArray();
    }
}
