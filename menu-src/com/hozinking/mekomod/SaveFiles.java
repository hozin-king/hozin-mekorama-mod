package com.hozinking.mekomod;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Penemuan & penulisan file save Mekorama di penyimpanan aplikasi.
 *
 * Nama file save runtime tidak muncul sebagai string polos di
 * libmekorama.so (temuan REA), jadi file dicari secara isi: berkas yang
 * bila didekode zlib menghasilkan JSON dengan array "lvs" — persis format
 * template assets/db. Lokasi yang dipindai (berurutan prioritas):
 * filesDir (rekursif), dataDir, externalFilesDir, /sdcard/Mekorama.
 *
 * Backup: sebelum penulisan pertama, file asli disalin ke
 * "<nama>.hozibak" di folder yang sama. Pulihkan = salin balik.
 */
public final class SaveFiles {
    private SaveFiles() {}

    public static final String BACKUP_SUFFIX = ".hozibak";

    /** Nama-nama level story dari assets/level_infos.json milik game. */
    public static List<String> storyNames(Context ctx) throws Exception {
        InputStream in = ctx.getAssets().open("level_infos.json");
        String json = new String(readAll(in), "UTF-8");
        @SuppressWarnings("unchecked")
        Map<String, Object> infos = (Map<String, Object>) SaveEditor.parseJson(json);
        return SaveEditor.storyNamesFromLevelInfos(infos);
    }

    /** Cari file save aktif. null bila tidak ditemukan. */
    public static File findSave(Context ctx) {
        List<File> roots = new ArrayList<File>();
        try { roots.add(ctx.getFilesDir()); } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 24) {
            try { roots.add(ctx.getDataDir()); } catch (Throwable ignored) {}
        }
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) roots.add(ext);
        } catch (Throwable ignored) {}
        roots.add(new File("/sdcard/Mekorama"));

        for (File root : roots) {
            File hit = scan(root, 0);
            if (hit != null) return hit;
        }
        return null;
    }

    private static File scan(File dir, int depth) {
        if (dir == null || depth > 3 || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File hit = scan(f, depth + 1);
                if (hit != null) return hit;
            } else if (isCandidate(f)) {
                try {
                    byte[] bytes = readAll(new FileInputStream(f));
                    if (SaveEditor.looksLikeSave(bytes)) return f;
                } catch (Exception ignored) {
                    // bukan save — lanjut
                }
            }
        }
        return null;
    }

    private static boolean isCandidate(File f) {
        long len = f.length();
        if (len < 64 || len > 2_000_000) return false;
        String name = f.getName();
        return !name.endsWith(BACKUP_SUFFIX);
    }

    public static File backupOf(File save) {
        return new File(save.getParentFile(), save.getName() + BACKUP_SUFFIX);
    }

    /** Salin file (untuk backup & pulihkan). */
    public static void copy(File src, File dst) throws Exception {
        byte[] bytes = readAll(new FileInputStream(src));
        FileOutputStream out = new FileOutputStream(dst);
        out.write(bytes);
        out.flush();
        out.close();
    }

    public static byte[] read(File f) throws Exception {
        return readAll(new FileInputStream(f));
    }

    public static void write(File f, byte[] bytes) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(bytes);
        out.flush();
        out.close();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
