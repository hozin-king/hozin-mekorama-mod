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
 * Format (terbukti dari REA libmekorama.so 1.7.2 + 144 file level asli):
 *   file = [header 4 byte][stream zlib]
 *   header = {0x02, 0x13, 0x0d, 0xfc} pada 132/144 file (versi 2); 12 file
 *            lama memakai 0x01 sebagai byte pertama (versi 1). Bukan
 *            checksum — nilainya konstan antar level.
 *   isi zlib = [1 byte panjang judul][judul UTF-8]
 *              [1 byte panjang author][author UTF-8]
 *              [4096 byte grid voxel][ekor N byte NOL]
 *   grid: indeks i -> x = i%16, y = (i/16)%16 (vertikal), z = i/256.
 *   Ekornya semua nol dengan panjang bervariasi (16..148 byte di file
 *   asli) — pembaca game mengabaikannya (panjang bervariasi = tidak
 *   divalidasi); saat menulis kita pakai 32 byte nol.
 *
 * Tipe blok (dari tabel block_type_names di libmekorama.so, 68 entri):
 * lihat konstanta di Solver.
 */
public final class LevelFormat {
    private LevelFormat() {}

    /** Ukuran grid voxel: 16 x 16 x 16. */
    public static final int GRID_SIZE = 4096;
    public static final int DIM = 16;

    /** Header file level versi 2 (yang dipakai 132 dari 144 file asli). */
    public static final byte[] HEADER_V2 = new byte[]{(byte) 0x02, (byte) 0x13, (byte) 0x0d, (byte) 0xfc};

    /** Panjang ekor nol yang kita tulis (dalam rentang file asli). */
    public static final int TRAILER_LEN = 32;

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
        /** Byte pertama header file (1 atau 2 = versi format). */
        public final int version;

        public Level(String title, String author, byte[] grid, int version) {
            this.title = title;
            this.author = author;
            this.grid = grid;
            this.version = version;
        }
    }

    /** Decode file .bin (header + zlib) menjadi Level. */
    public static Level decode(byte[] fileBytes) throws IOException {
        if (fileBytes == null || fileBytes.length < 4 + 2) {
            throw new IOException("file level terlalu pendek");
        }
        int version = fileBytes[0] & 0xFF;
        byte[] raw = inflate(fileBytes, 4, fileBytes.length - 4);
        if (raw.length < 2 + GRID_SIZE) {
            throw new IOException("isi level terdekompres terlalu pendek: " + raw.length);
        }
        int p = 0;
        int titleLen = raw[p++] & 0xFF;
        if (p + titleLen + 1 > raw.length) throw new IOException("judul level rusak");
        String title = new String(raw, p, titleLen, "UTF-8");
        p += titleLen;
        int authorLen = raw[p++] & 0xFF;
        if (p + authorLen + GRID_SIZE > raw.length) throw new IOException("author level rusak");
        String author = new String(raw, p, authorLen, "UTF-8");
        p += authorLen;
        byte[] grid = Arrays.copyOfRange(raw, p, p + GRID_SIZE);
        return new Level(title, author, grid, version);
    }

    /** Encode grid menjadi file .bin (header v2 + zlib). */
    public static byte[] encode(String title, String author, byte[] grid) throws IOException {
        if (grid == null || grid.length != GRID_SIZE) {
            throw new IOException("grid harus 4096 byte");
        }
        byte[] titleBytes = title.getBytes("UTF-8");
        byte[] authorBytes = author.getBytes("UTF-8");
        if (titleBytes.length > 255 || authorBytes.length > 255) {
            throw new IOException("judul/author terlalu panjang (maks 255 byte)");
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(titleBytes.length);
        raw.write(titleBytes);
        raw.write(authorBytes.length);
        raw.write(authorBytes);
        raw.write(grid);
        for (int i = 0; i < TRAILER_LEN; i++) raw.write(0);
        byte[] comp = deflate(raw.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HEADER_V2);
        out.write(comp);
        return out.toByteArray();
    }

    /** Hitung berapa sel berisi tipe tertentu. */
    public static int countType(byte[] grid, int type) {
        int n = 0;
        for (int i = 0; i < GRID_SIZE; i++) {
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
