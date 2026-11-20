package com.hozinking.mekomod;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Random;

/**
 * Test host untuk Solver + WorldGen + LevelFormat — dijalankan di CI
 * (dan lokal) terhadap file level ASLI dari APK game.
 * argumen [0] = direktori assets game (berisi x_*.bin / f_*.bin / w_*.bin).
 *
 * Yang dibuktikan (v1.2 — format level interleaved, REA read_world_cached):
 *  1. LevelFormat: roundtrip encode->decode identik (grid + rot + var
 *     + judul + author) pada level asli; gameWouldLoad() true.
 *  2. Solver pada level asli: "Crash Course" solvable 14 langkah
 *     (grid benar hasil parse interleaved — dulu 9 di grid naive yang
 *     salah), "Flat Path" solvable 4 langkah + 1 geser blok.
 *  3. Solver pada 144 level asli: >= 85 solvable (jalan kaki + geser
 *     blok), >= 45 di antaranya murni jalan kaki. (v1.1: 38 jalan kaki.)
 *  4. Empat level uji v1.2: "Ride C Over Sea" HARUS solvable
 *     (rencana aksi terverifikasi simulasi), "5x5x5" + "Ant Farm"
 *     solvable, "Shock Shuffle" dilaporkan jujur UNSOLVABLE
 *     (sel awal robot tertutup — bukan ditebak).
 *  5. Solver sintetik: koridor terhalang motor -> HAS_MOTOR; terhalang
 *     bola -> HAS_BALL; goal tertutup total -> NEEDS_DRAG; tanpa bot
 *     -> NO_BOT; parit kosong solvable 4 langkah.
 *  6. WorldGen (rantai penuh, bukan cuma "solvable"): 20 world (seed
 *     tetap) — gameWouldLoad() true (game bisa load + play), solver
 *     OK, tapak <= 8x8, tinggi lantai <= 4, tepat 1 bot + 1 win,
 *     author "Hozin" (bukan "Unknown Author"), roundtrip identik.
 */
public final class SolverTest {
    private static int failures = 0;

    private static void check(boolean ok, String msg) {
        System.out.println((ok ? "  OK  " : " FAIL ") + msg);
        if (!ok) failures++;
    }

    private static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("pakai: SolverTest <direktori assets game>");
            System.exit(2);
        }
        File assets = new File(args[0]);

        // ---- 1. LevelFormat roundtrip (level asli, termasuk byte ekstra) ----
        LevelFormat.Level crash = LevelFormat.decode(readAll(new File(assets, "x_crash_course.bin")));
        check("Crash Course".equals(crash.title), "decode x_crash_course: judul = " + crash.title);
        byte[] re = LevelFormat.encode(crash.title, crash.author, crash.grid, crash.rot, crash.var);
        LevelFormat.Level crash2 = LevelFormat.decode(re);
        boolean gridEq = java.util.Arrays.equals(crash.grid, crash2.grid)
                && java.util.Arrays.equals(crash.rot, crash2.rot)
                && java.util.Arrays.equals(crash.var, crash2.var);
        check(gridEq && crash.title.equals(crash2.title) && crash.author.equals(crash2.author),
                "LevelFormat roundtrip identik (grid + rot + var + judul + author)");
        check(LevelFormat.gameWouldLoad(re),
                "gameWouldLoad: level hasil encode ulang bisa di-load game");

        // ---- 2. Solver pada level asli ----
        long t0 = System.currentTimeMillis();
        Solver.Result rCrash = Solver.solve(crash.grid, crash.rot);
        long msCrash = System.currentTimeMillis() - t0;
        check(rCrash.status == Solver.Status.OK && rCrash.steps() == 14,
                "Crash Course solvable, 14 langkah (dapat " + rCrash.status + "/" + rCrash.steps()
                + ", " + msCrash + " ms)");

        LevelFormat.Level flat = LevelFormat.decode(readAll(new File(assets, "x_flat_path.bin")));
        Solver.Result rFlat = Solver.solve(flat.grid, flat.rot);
        check(rFlat.status == Solver.Status.OK && rFlat.steps() == 4
                        && rFlat.actions != null,
                "Flat Path solvable, 4 langkah + 1 geser blok (dapat " + rFlat.status
                + "/" + rFlat.steps() + ", actions="
                + (rFlat.actions == null ? 0 : rFlat.actions.size()) + ")");

        // ---- 3. Hitung solvable di semua level asli ----
        File[] bins = assets.listFiles();
        int total = 0, okCount = 0, walkOnly = 0;
        long tAll = System.currentTimeMillis();
        if (bins != null) {
            for (File f : bins) {
                String n = f.getName();
                if (!n.endsWith(".bin")) continue;
                if (!(n.startsWith("x_") || n.startsWith("f_") || n.startsWith("w_"))) continue;
                try {
                    LevelFormat.Level lv = LevelFormat.decode(readAll(f));
                    Solver.Result r = Solver.solve(lv.grid, lv.rot);
                    total++;
                    if (r.status == Solver.Status.OK) {
                        okCount++;
                        if (r.actions == null) walkOnly++;
                    }
                } catch (Exception e) {
                    System.out.println("  info gagal decode " + n + ": " + e.getMessage());
                }
            }
        }
        long msAll = System.currentTimeMillis() - tAll;
        System.out.println("  info solvable: " + okCount + "/" + total
                + " level (murni jalan kaki: " + walkOnly + ", " + msAll + " ms total)");
        check(total >= 130, "level asli terdecode >= 130 (dapat " + total + ")");
        check(okCount >= 85, "level solvable (jalan + geser) >= 85 (dapat " + okCount + ")");
        check(walkOnly >= 45, "level murni jalan kaki >= 45 (dapat " + walkOnly + ")");

        // ---- 4. Empat level uji v1.2 ----
        checkFour(assets, "x_5x5x5.bin", true);
        checkFour(assets, "x_ant_farm_v2.bin", true);
        checkFour(assets, "x_ride_c_over_sea.bin", true);
        checkFour(assets, "x_shock_shuffle.bin", false);

        // ---- 5. Solver sintetik ----
        Solver.Result rTrench = Solver.solve(trench());
        check(rTrench.status == Solver.Status.OK && rTrench.steps() == 4,
                "parit kosong solvable, 4 langkah (dapat " + rTrench.status + "/" + rTrench.steps() + ")");
        check(syntheticMotor().status == Solver.Status.HAS_MOTOR,
                "koridor terhalang motor -> HAS_MOTOR (dapat " + syntheticMotor().status + ")");
        check(syntheticBall().status == Solver.Status.HAS_BALL,
                "koridor terhalang bola -> HAS_BALL (dapat " + syntheticBall().status + ")");
        check(syntheticWalled().status == Solver.Status.NEEDS_DRAG,
                "goal tertutup total -> NEEDS_DRAG (dapat " + syntheticWalled().status + ")");
        check(syntheticNoBot().status == Solver.Status.NO_BOT,
                "level tanpa bot -> NO_BOT (dapat " + syntheticNoBot().status + ")");

        // ---- 6. WorldGen — rantai penuh (bukan cuma "solvable") ----
        Random rnd = new Random(12345L);
        int genOk = 0;
        for (int i = 0; i < 20; i++) {
            WorldGen.World w = WorldGen.generate(rnd, i + 1);
            if (w == null) {
                System.out.println("  info WorldGen gagal di iterasi " + i);
                continue;
            }
            Solver.Result r = Solver.solve(w.grid);
            boolean boundsOk = true;
            int bots = 0, wins = 0, maxFloorY = 0;
            for (int idx = 0; idx < LevelFormat.GRID_SIZE; idx++) {
                int t = w.grid[idx] & 0xFF;
                if (t == 0) continue;
                int x = LevelFormat.xOf(idx), y = LevelFormat.yOf(idx), z = LevelFormat.zOf(idx);
                if (x >= 8 || z >= 8) boundsOk = false;
                if (t == Solver.BOT_YELLOW) bots++;
                if (t == Solver.WIN) wins++;
                if (t != Solver.STAR && y > maxFloorY) maxFloorY = y;
            }
            LevelFormat.Level back = LevelFormat.decode(w.binBytes);
            boolean rt = java.util.Arrays.equals(w.grid, back.grid)
                    && ("Acak Hozin #" + (i + 1)).equals(back.title)
                    && "Hozin".equals(back.author);
            boolean loads = LevelFormat.gameWouldLoad(w.binBytes);
            if (r.status == Solver.Status.OK && boundsOk && bots == 1 && wins == 1
                    && maxFloorY <= WorldGen.MAX_HEIGHT && rt && loads) {
                genOk++;
            } else {
                System.out.println("  info world #" + (i + 1) + " bermasalah: solve=" + r.status
                        + " bounds=" + boundsOk + " bot=" + bots + " win=" + wins
                        + " maxY=" + maxFloorY + " roundtrip=" + rt + " gameLoads=" + loads);
            }
        }
        check(genOk == 20, "WorldGen rantai penuh: 20/20 solvable + game bisa load + author benar (" + genOk + "/20)");

        if (failures > 0) {
            System.out.println("SolverTest GAGAL: " + failures + " pemeriksaan");
            System.exit(1);
        }
        System.out.println("SolverTest LULUS semua pemeriksaan");
    }

    /** Satu dari empat level uji v1.2 — solvable harus benar-benar bisa dijalani. */
    private static void checkFour(File assets, String fileName, boolean mustSolve) throws Exception {
        LevelFormat.Level lv = LevelFormat.decode(readAll(new File(assets, fileName)));
        long t0 = System.currentTimeMillis();
        Solver.Result r = Solver.solve(lv.grid, lv.rot);
        long ms = System.currentTimeMillis() - t0;
        int drags = 0;
        if (r.actions != null) {
            for (Solver.Step s : r.actions) {
                if (s.kind != Solver.Step.Kind.WALK) drags++;
            }
        }
        System.out.println("  info " + lv.title + ": " + r.status + " langkah=" + r.steps()
                + " geser=" + drags + " (" + ms + " ms) — " + r.reason);
        if (mustSolve) {
            check(r.status == Solver.Status.OK,
                    lv.title + " HARUS solvable (dapat " + r.status + ": " + r.reason + ")");
            check(verifyPlan(lv.grid, r),
                    lv.title + ": rencana aksi terverifikasi simulasi (sampai finish)");
        } else {
            check(r.status != Solver.Status.OK,
                    lv.title + " dilaporkan jujur tidak solvable (dapat " + r.status + ")");
        }
    }

    /**
     * Simulasi independen rencana aksi di atas salinan grid (bukan state
     * internal solver): tiap langkah jalan harus bisa dipijak dari posisi
     * robot saat itu, tiap geser harus memindahkan blok yang benar, dan
     * robot harus berakhir di finish. Menangkap rencana yang "solvable"
     * di kertas tapi tidak bisa dijalani.
     */
    private static boolean verifyPlan(byte[] grid0, Solver.Result r) {
        if (r.status != Solver.Status.OK || r.start == null || r.goal == null) return false;
        if (r.actions == null) {
            // Jalan murni jalan kaki — path-nya sendiri adalah buktinya.
            return r.path != null && !r.path.isEmpty()
                    && r.path.get(r.path.size() - 1).equals(r.goal);
        }
        byte[] g = grid0.clone();
        int rx = r.start.x, ry = r.start.y, rz = r.start.z;
        for (Solver.Step s : r.actions) {
            if (s.kind == Solver.Step.Kind.WALK) {
                if (!walkStepOk(g, rx, ry, rz, s.to.x, s.to.y, s.to.z)) return false;
                rx = s.to.x; ry = s.to.y; rz = s.to.z;
            } else {
                int fi = LevelFormat.index(s.from.x, s.from.y, s.from.z);
                if ((g[fi] & 0xFF) != s.type) return false;
                int ti = LevelFormat.index(s.to.x, s.to.y, s.to.z);
                int tt = g[ti] & 0xFF;
                if (tt != Solver.NONE && tt != Solver.STAR && tt != Solver.EYE) return false;
                g[fi] = (byte) Solver.NONE;
                g[ti] = (byte) s.type;
                if (!Solver.walkable(g, rx, ry, rz)) return false;
            }
        }
        return rx == r.goal.x && ry == r.goal.y && rz == r.goal.z;
    }

    /** Satu langkah jalan valid? datar / naik 1 / jatuh ke pijakan. */
    private static boolean walkStepOk(byte[] g, int cx, int cy, int cz, int nx, int ny, int nz) {
        if (!Solver.walkable(g, nx, ny, nz)) return false;
        int dx = Math.abs(nx - cx), dz = Math.abs(nz - cz);
        if (dx + dz != 1) return false;
        if (ny == cy || ny == cy + 1) return true;
        if (ny < cy) {
            // Jatuh: harus mendarat di sel ini dari kolom tetangga.
            Solver.Cell land = Solver.fallLand(g, nx, cy, nz);
            return land != null && land.x == nx && land.y == ny && land.z == nz;
        }
        return false;
    }

    // ---- Level sintetik ----
    //
    // Parit tertutup: koridor x=0..4 di z=5, lantai y=0, dinding setinggi
    // penuh (y=0..15) di z=4 dan z=6 (x=0..6) + penutup ujung di x=5.
    // Robot tidak bisa keluar parit (tidak bisa memanjat dinding setinggi
    // penuh, tidak bisa memutar ujung) — satu-satunya jalan adalah sel
    // (2,1,5). Menaruh penghalang di sana benar-benar menutup jalan.

    /** Parit kosong (tanpa penghalang) — baseline yang harus solvable. */
    private static byte[] trench() {
        byte[] g = new byte[LevelFormat.GRID_SIZE];
        for (int x = 0; x <= 4; x++) {
            g[LevelFormat.index(x, 0, 5)] = (byte) Solver.CUBE_DIRT;
        }
        g[LevelFormat.index(0, 0, 5)] = (byte) Solver.BOT_YELLOW;
        g[LevelFormat.index(4, 0, 5)] = (byte) Solver.WIN;
        for (int x = 0; x <= 6; x++) {
            for (int y = 0; y <= 15; y++) {
                g[LevelFormat.index(x, y, 4)] = (byte) Solver.CUBE_STEEL;
                g[LevelFormat.index(x, y, 6)] = (byte) Solver.CUBE_STEEL;
            }
        }
        for (int y = 0; y <= 15; y++) {
            g[LevelFormat.index(5, y, 5)] = (byte) Solver.CUBE_STEEL;
        }
        return g;
    }

    private static Solver.Result syntheticMotor() {
        byte[] g = trench();
        // Kolom motor 2 tinggi (robot tidak bisa melangkah naik 2 sekaligus,
        // dan tidak bisa memutar parit) — model longgar menembus motor.
        g[LevelFormat.index(2, 1, 5)] = (byte) Solver.MOTOR;
        g[LevelFormat.index(2, 2, 5)] = (byte) Solver.MOTOR;
        return Solver.solve(g);
    }

    private static Solver.Result syntheticBall() {
        byte[] g = trench();
        g[LevelFormat.index(2, 1, 5)] = (byte) Solver.BALL_STEEL;
        g[LevelFormat.index(2, 2, 5)] = (byte) Solver.BALL_STEEL;
        return Solver.solve(g);
    }

    private static Solver.Result syntheticWalled() {
        byte[] g = trench();
        // Kolom baja 2 tinggi (bukan dinamis) di tengah parit.
        g[LevelFormat.index(2, 1, 5)] = (byte) Solver.CUBE_STEEL;
        g[LevelFormat.index(2, 2, 5)] = (byte) Solver.CUBE_STEEL;
        return Solver.solve(g);
    }

    private static Solver.Result syntheticNoBot() {
        byte[] g = trench();
        g[LevelFormat.index(0, 0, 5)] = (byte) Solver.CUBE_DIRT; // bot diganti lantai
        g[LevelFormat.index(2, 1, 5)] = (byte) Solver.BALL_STEEL; // level bola
        return Solver.solve(g);
    }
}
