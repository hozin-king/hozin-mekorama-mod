package com.hozinking.mekomod;

import android.app.Activity;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AutoPlay — toggle ON/OFF untuk "Auto Play — main sendiri" (v1.2).
 *
 * Saat ON: solve level yang sedang dimainkan (Solver A* + perencana
 * konstruktif untuk geser blok), jalankan robot lewat figure_tap milik
 * game (satu tap per waypoint, polling posisi robot tiap ~16 ms —
 * TANPA jeda buatan; robot jalan secepat game), geser blok lewat
 * voxel_remove + voxel_add milik game, putar kamera 90° bila waypoint
 * macet + sesekali natural tiap ~10 waypoint, lalu OTOMATIS lanjut ke
 * level berikutnya, terus sampai toggle OFF.
 *
 * Pengaman (pola yang sama seperti Pool Billiards v1.3): gagal menyusun
 * atau menjalankan rencana 3x berturut-turut -> toggle MATI SENDIRI +
 * alasan jelas di status. Satu level yang tidak bisa di-solve TIDAK
 * di-skip diam-diam: berhenti + auto-off + pesan jujur.
 *
 * Semua panggilan yang menyentuh state engine (tap / load level /
 * geser blok / putar kamera) dijalankan di UI thread game; pembacaan
 * (grid / posisi robot) dari thread worker ini (read-only, aman).
 */
public final class AutoPlay {
    private AutoPlay() {}

    private static final String TAG = "MEKOMOD";
    /** Gagal 3x berturut-turut -> auto-off. */
    public static final int MAX_STRIKES = 3;
    /** Timeout per waypoint (robot jalan 1 sel ~0,3-0,8 dtk). */
    private static final long WAYPOINT_TIMEOUT_MS = 4000;
    /** Jeda setelah robot sampai goal (biar win + bintang tersimpan). */
    private static final long WIN_SETTLE_MS = 1200;
    /** Polling posisi robot (satu frame — bukan jeda buatan). */
    private static final long POLL_MS = 16;
    /** Timeout menunggu level berikut termuat. */
    private static final long LOAD_WAIT_MS = 3000;

    /** Listener status (dipasang Menu — dipanggil dari thread worker). */
    public interface Listener {
        void onStatus(String s);
        void onStateChanged(boolean on);
    }

    private static volatile boolean on = false;
    private static volatile int strikes = 0;
    private static Thread worker;
    private static Listener listener;
    private static Activity activity;

    /** True bila Auto Play sedang ON. */
    public static boolean isOn() { return on; }

    /** Nyalakan toggle. Mengembalikan false bila payload tidak termuat. */
    public static synchronized boolean start(Activity a, Listener l) {
        if (on) return true;
        if (!NativeBridge.loaded) {
            if (l != null) l.onStatus("Auto Play tidak tersedia: payload native tidak termuat.");
            return false;
        }
        activity = a;
        listener = l;
        on = true;
        strikes = 0;
        worker = new Thread(new Runnable() {
            @Override public void run() { runLoop(); }
        }, "mekomod-autoplay");
        worker.start();
        report("Auto Play ON — mulai solve level ini…");
        if (listener != null) listener.onStateChanged(true);
        return true;
    }

    /** Matikan toggle (dari user). */
    public static synchronized void stop(String reason) {
        if (!on) return;
        on = false;
        if (worker != null) worker.interrupt();
        report(reason != null ? reason : "Auto Play OFF.");
        if (listener != null) listener.onStateChanged(false);
    }

    /** Dipanggil Menu saat toggle ditekan. */
    public static synchronized void toggle(Activity a, Listener l) {
        if (on) stop("Auto Play OFF — berhenti.");
        else start(a, l);
    }

    private static void report(final String s) {
        Log.i(TAG, "AutoPlay: " + s);
        if (listener != null) listener.onStatus(s);
    }

    private static void autoOff(String reason) {
        on = false;
        report("Auto Play MATI SENDIRI: " + reason);
        if (listener != null) listener.onStateChanged(false);
    }

    /** Satu kegagalan. 3x berturut-turut -> auto-off. */
    private static boolean strike(String reason) {
        strikes++;
        if (strikes >= MAX_STRIKES) {
            autoOff("gagal 3x berturut-turut (" + reason + ").");
            return false;
        }
        report("Auto Play gagal (" + strikes + "/" + MAX_STRIKES + "): " + reason
                + " — coba level ini lagi…");
        return on;
    }

    // ------------------------------------------------------------------
    // Loop utama
    // ------------------------------------------------------------------

    private static void runLoop() {
        int levelNo = 0;
        while (on) {
            levelNo++;
            // 1) Baca grid level aktif.
            byte[] grid = NativeBridge.nativeGetVoxels();
            if (grid == null) {
                if (!strike("tidak bisa membaca grid level (payload belum init)")) return;
                if (!sleepOk(500)) return;
                continue;
            }
            if (isAllZero(grid)) {
                if (!strike("belum ada level yang dimuat — masuk ke dalam level dulu")) return;
                if (!sleepOk(500)) return;
                continue;
            }
            // 2) Solve (dengan byte rot live — sumbu slider dibaca dari sini).
            long t0 = System.currentTimeMillis();
            Solver.Result r = Solver.solve(grid, NativeBridge.nativeGetVoxelRots());
            long solveMs = System.currentTimeMillis() - t0;
            if (r.status != Solver.Status.OK) {
                // Level tidak bisa di-solve: berhenti + auto-off + alasan.
                autoOff("level ini tidak bisa di-solve: " + r.reason
                        + " (dinamika: " + r.dynamics + ").");
                return;
            }
            int drags = 0;
            if (r.actions != null) {
                for (Solver.Step s : r.actions) {
                    if (s.kind != Solver.Step.Kind.WALK) drags++;
                }
            }
            report("Level " + levelNo + " ter-solve (" + r.steps() + " langkah"
                    + (drags > 0 ? " + " + drags + " geser blok" : "") + ", "
                    + solveMs + " ms) — robot jalan…");
            // 3) Kenali level saat ini (untuk lanjut ke berikut).
            LevelId current = identify(grid);
            // 4) Jalankan rencana (jalan + geser blok).
            if (!executePlan(r)) {
                if (!on) return; // dimatikan user di tengah jalan
                if (!strike("robot macet di tengah jalan")) return;
                if (!sleepOk(500)) return;
                continue;
            }
            // 5) Sampai goal — biarkan win tersimpan.
            report("Level " + levelNo + " SELESAI (sampai finish).");
            strikes = 0; // sukses mereset hitungan gagal
            if (!sleepOk(WIN_SETTLE_MS)) return;
            // 6) Lanjut ke level berikut.
            if (current == null || current.nextFile == null) {
                autoOff("level selesai, tapi level berikutnya tidak dikenali "
                        + "(bukan level Story/Featured bawaan?) — pilih manual "
                        + "lalu ON-kan lagi.");
                return;
            }
            if (!advanceTo(current.nextFile)) {
                if (!on) return;
                if (!strike("gagal memuat level berikut (" + current.nextFile + ")")) return;
                if (!sleepOk(500)) return;
                continue;
            }
            report("Lanjut ke level berikut: " + current.nextFile);
            if (!waitForNewGrid(grid)) {
                if (!strike("level berikut tidak kunjung termuat")) return;
                if (!sleepOk(500)) return;
                continue;
            }
        }
    }

    /** Tidur yang bisa dibatalkan toggle OFF. false bila dimatikan. */
    private static boolean sleepOk(long ms) {
        try {
            Thread.sleep(ms);
            return on;
        } catch (InterruptedException e) {
            return on;
        }
    }

    private static boolean isAllZero(byte[] grid) {
        for (int i = 0; i < grid.length; i++) if (grid[i] != 0) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Eksekusi rencana (v1.2: jalan + geser blok + putar kamera)
    // ------------------------------------------------------------------

    /**
     * Jalankan rencana solver: jalan kaki (tap per waypoint + polling
     * posisi robot) diselingi geser blok (voxel_remove + voxel_add
     * milik game). Kamera diputar 90° bila waypoint macet (mungkin
     * tertutup dari sudut ini) + sesekali secara natural tiap ~10
     * waypoint supaya kelihatan seperti pemain sungguhan.
     */
    private static boolean executePlan(Solver.Result r) {
        if (r.start == null) return false;
        // Kenali badan robot dari sel awal solver (hasilnya dicek lewat
        // polling posisi di bawah — bila null, tap tetap dicoba dan
        // ketidaksampaian akan terdeteksi sebagai macet).
        final int startIdx = r.start.index();
        runOnUiAndWait(new Runnable() {
            @Override public void run() {
                NativeBridge.nativeFindRobotBody(startIdx);
            }
        });
        // Ubah path jalan-kaki murni jadi daftar aksi jalan.
        List<Solver.Step> actions = r.actions;
        if (actions == null) {
            if (r.path == null || r.path.size() < 2) return false;
            actions = new ArrayList<Solver.Step>();
            for (int i = 1; i < r.path.size(); i++) {
                actions.add(Solver.Step.walk(r.path.get(i)));
            }
        }
        int waypoints = 0;
        int camDir = 1;
        for (Solver.Step s : actions) {
            if (!on) return false;
            if (s.kind == Solver.Step.Kind.WALK) {
                waypoints++;
                // Sesekali putar kamera secara natural (tiap ~10 waypoint,
                // arah selang-seling) — jangan putar-putar tanpa henti.
                if (waypoints % 10 == 0) {
                    rotateCamera(camDir);
                    camDir = -camDir;
                }
                if (!tapAndWait(s.to)) {
                    // Macet — putar kamera 90° (waypoint mungkin tertutup
                    // struktur dari sudut ini), lalu coba tap sekali lagi.
                    rotateCamera(1);
                    if (!tapAndWait(s.to)) {
                        report("Robot tidak sampai di " + s.to + " — berhenti.");
                        return false;
                    }
                }
            } else {
                // Geser blok (drag/slider) lewat fungsi game sendiri.
                final int fromIdx = s.from.index();
                final int toIdx = s.to.index();
                final int[] rc = {-99};
                Boolean ok = runOnUiAndWait(new Runnable() {
                    @Override public void run() {
                        rc[0] = NativeBridge.nativeMoveBlock(fromIdx, toIdx);
                    }
                });
                if (ok == null || rc[0] != 0) {
                    report("Gagal geser blok " + s.from + " -> " + s.to
                            + " (kode " + rc[0] + ") — berhenti.");
                    return false;
                }
                // Verifikasi: blok sudah pindah di grid live.
                if (!sleepOk(100)) return false;
                byte[] g = NativeBridge.nativeGetVoxels();
                if (g == null || (g[toIdx] & 0xFF) != s.type
                        || (g[fromIdx] & 0xFF) != (s.type == Solver.SLIDER
                                ? Solver.RAIL : 0)) {
                    report("Verifikasi geser blok gagal (" + s.from + " -> "
                            + s.to + ") — berhenti.");
                    return false;
                }
            }
        }
        return true;
    }

    /** Tap sel + tunggu robot sampai (sekali coba + sekali ulang). */
    private static boolean tapAndWait(final Solver.Cell wp) {
        final int cellIdx = wp.index();
        runOnUiAndWait(new Runnable() {
            @Override public void run() {
                NativeBridge.nativeTapCell(cellIdx);
            }
        });
        if (waitArrival(wp, WAYPOINT_TIMEOUT_MS)) return true;
        runOnUiAndWait(new Runnable() {
            @Override public void run() {
                NativeBridge.nativeTapCell(cellIdx);
            }
        });
        return waitArrival(wp, WAYPOINT_TIMEOUT_MS / 2);
    }

    /** Putar kamera game 90° (arah +1 / -1) — halus via camera_step. */
    private static void rotateCamera(final int direction) {
        runOnUiAndWait(new Runnable() {
            @Override public void run() {
                NativeBridge.nativeRotateCamera(direction);
            }
        });
    }

    /** Tunggu robot sampai di waypoint (toleransi posisi badan robot). */
    private static boolean waitArrival(Solver.Cell wp, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (on && System.currentTimeMillis() < deadline) {
            float[] pos = NativeBridge.nativeRobotCellPos();
            if (pos != null && pos.length >= 3) {
                float dx = pos[0] - wp.x;
                float dy = pos[1] - wp.y;
                float dz = pos[2] - wp.z;
                // Pusat badan robot ~0,5 sel di atas kaki: dy boleh -0,2..+1,0.
                if (Math.abs(dx) < 0.65f && dy >= -0.2f && dy <= 1.0f
                        && Math.abs(dz) < 0.65f) {
                    return true;
                }
            }
            if (!sleepOk(POLL_MS)) return false;
        }
        return false;
    }

    /**
     * Jalankan Runnable di UI thread game dan tunggu selesai.
     * Mengembalikan Boolean.TRUE bila selesai, null bila gagal/timeout.
     * Dipakai Menu juga (untuk sandbox_write).
     */
    static Boolean runOnUiAndWait(final Runnable r) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    err.set(t);
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            return null;
        }
        return err.get() == null ? Boolean.TRUE : null;
    }

    // ------------------------------------------------------------------
    // Identifikasi level & lanjut ke berikut
    // ------------------------------------------------------------------

    private static final class LevelId {
        final String file;
        final String nextFile;
        LevelId(String file, String nextFile) { this.file = file; this.nextFile = nextFile; }
    }

    /** Cache daftar level bawaan (nama file -> grid), dimuat sekali. */
    private static List<String> cachedFiles;
    private static List<byte[]> cachedGrids;
    private static List<String> cachedOrder; // story lalu featured (urutan level_infos)

    private static LevelId identify(byte[] liveGrid) {
        try {
            if (activity == null) return null;
            AssetManager am = activity.getAssets();
            if (cachedFiles == null) {
                cachedFiles = new ArrayList<String>();
                cachedGrids = new ArrayList<byte[]>();
                String[] names = am.list("");
                if (names != null) {
                    for (String n : names) {
                        if (!n.endsWith(".bin")) continue;
                        if (!(n.startsWith("x_") || n.startsWith("f_") || n.startsWith("w_"))) continue;
                        try {
                            LevelFormat.Level lv = LevelFormat.decode(readAsset(am, n));
                            cachedFiles.add(n);
                            cachedGrids.add(lv.grid);
                        } catch (Throwable ignored) {
                            // file bukan level — lewati
                        }
                    }
                }
                // Urutan seri: story lalu featured dari level_infos.json.
                cachedOrder = new ArrayList<String>();
                try {
                    String json = new String(readAsset(am, "level_infos.json"), "UTF-8");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> infos = (Map<String, Object>) SaveEditor.parseJson(json);
                    cachedOrder.addAll(SaveEditor.storyNamesFromLevelInfos(infos));
                    cachedOrder.addAll(featuredNamesFromLevelInfos(infos));
                } catch (Throwable ignored) {
                    // tanpa urutan: advance tidak bisa
                }
            }
            String found = null;
            for (int i = 0; i < cachedFiles.size(); i++) {
                if (Arrays.equals(liveGrid, cachedGrids.get(i))) {
                    found = cachedFiles.get(i);
                    break;
                }
            }
            if (found == null) return null;
            String next = null;
            if (cachedOrder != null) {
                int pos = cachedOrder.indexOf(found);
                if (pos >= 0 && pos + 1 < cachedOrder.size()) {
                    next = cachedOrder.get(pos + 1);
                }
            }
            return new LevelId(found, next);
        } catch (Throwable t) {
            Log.e(TAG, "identify gagal", t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> featuredNamesFromLevelInfos(Map<String, Object> levelInfos) {
        List<String> names = new ArrayList<String>();
        Object feat = levelInfos.get("featured");
        if (feat instanceof List) {
            for (Object o : (List<Object>) feat) {
                if (o instanceof Map) {
                    Object n = ((Map<String, Object>) o).get("n");
                    if (n instanceof String) names.add((String) n);
                }
            }
        }
        return names;
    }

    private static boolean advanceTo(final String nextFile) {
        // Cari indeks registry dari nama file, verifikasi namanya, baru muat.
        final int[] idx = {-1};
        Boolean ok = runOnUiAndWait(new Runnable() {
            @Override public void run() {
                idx[0] = NativeBridge.nativeFindLevelIndex(nextFile);
            }
        });
        if (ok == null || idx[0] < 0) return false;
        final int target = idx[0];
        String regName = NativeBridge.nativeLevelName(target);
        if (regName == null || !regName.contains(nextFile)) return false;
        final int[] rc = {-99};
        Boolean ok2 = runOnUiAndWait(new Runnable() {
            @Override public void run() {
                rc[0] = NativeBridge.nativeLoadLevel(target);
            }
        });
        if (ok2 == null || rc[0] != 0) return false;
        NativeBridge.nativeResetRobotCache();
        return true;
    }

    /** Tunggu grid berubah (level baru termuat), maks LOAD_WAIT_MS. */
    private static boolean waitForNewGrid(byte[] oldGrid) {
        long deadline = System.currentTimeMillis() + LOAD_WAIT_MS;
        while (on && System.currentTimeMillis() < deadline) {
            byte[] g = NativeBridge.nativeGetVoxels();
            if (g != null && !Arrays.equals(g, oldGrid)) return true;
            if (!sleepOk(100)) return false;
        }
        return false;
    }

    private static byte[] readAsset(AssetManager am, String name) throws Exception {
        InputStream in = am.open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
