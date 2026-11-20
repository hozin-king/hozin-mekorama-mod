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
 * Yang dibuktikan:
 *  1. LevelFormat: roundtrip encode->decode identik (grid + judul).
 *  2. Solver pada level asli: "Crash Course" solvable 9 langkah,
 *     "Flat Path" solvable 2 langkah (cocok dengan model gerak engine).
 *  3. Solver pada 144 level asli: >= 35 level walk-solvable (model ketat:
 *     jatuh tidak tembus blok padat, jalan dekat zapper fail-closed,
 *     batu/bola/tesla bukan pijakan — memberi 38; ambang 35 memberi ruang).
 *  4. Solver sintetik: koridor terhalang motor -> HAS_MOTOR; terhalang
 *     bola -> HAS_BALL; goal tertutup total -> NEEDS_DRAG; tanpa bot -> NO_BOT.
 *  5. WorldGen: 20 world (seed tetap) semuanya solvable oleh Solver,
 *     tapak <= 8x8, tinggi lantai <= 4, tepat 1 bot + 1 win, dan
 *     roundtrip encode->decode identik.
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

        // ---- 1. LevelFormat roundtrip (level asli) ----
        LevelFormat.Level crash = LevelFormat.decode(readAll(new File(assets, "x_crash_course.bin")));
        check("Crash Course".equals(crash.title), "decode x_crash_course: judul = " + crash.title);
        byte[] re = LevelFormat.encode(crash.title, crash.author, crash.grid);
        LevelFormat.Level crash2 = LevelFormat.decode(re);
        boolean gridEq = java.util.Arrays.equals(crash.grid, crash2.grid);
        check(gridEq && crash.title.equals(crash2.title) && crash.author.equals(crash2.author),
                "LevelFormat roundtrip identik (grid + judul + author)");

        // ---- 2. Solver pada level asli ----
        long t0 = System.currentTimeMillis();
        Solver.Result rCrash = Solver.solve(crash.grid);
        long msCrash = System.currentTimeMillis() - t0;
        check(rCrash.status == Solver.Status.OK && rCrash.steps() == 9,
                "Crash Course solvable, 9 langkah (dapat " + rCrash.status + "/" + rCrash.steps()
                + ", " + msCrash + " ms)");

        LevelFormat.Level flat = LevelFormat.decode(readAll(new File(assets, "x_flat_path.bin")));
        Solver.Result rFlat = Solver.solve(flat.grid);
        check(rFlat.status == Solver.Status.OK && rFlat.steps() == 2,
                "Flat Path solvable, 2 langkah (dapat " + rFlat.status + "/" + rFlat.steps() + ")");

        // ---- 3. Hitung walk-solvable di semua level asli ----
        File[] bins = assets.listFiles();
        int total = 0, okCount = 0;
        long tAll = System.currentTimeMillis();
        if (bins != null) {
            for (File f : bins) {
                String n = f.getName();
                if (!n.endsWith(".bin")) continue;
                if (!(n.startsWith("x_") || n.startsWith("f_") || n.startsWith("w_"))) continue;
                try {
                    LevelFormat.Level lv = LevelFormat.decode(readAll(f));
                    Solver.Result r = Solver.solve(lv.grid);
                    total++;
                    if (r.status == Solver.Status.OK) okCount++;
                } catch (Exception e) {
                    System.out.println("  info gagal decode " + n + ": " + e.getMessage());
                }
            }
        }
        long msAll = System.currentTimeMillis() - tAll;
        System.out.println("  info walk-solvable: " + okCount + "/" + total
                + " level (" + msAll + " ms total)");
        check(total >= 130, "level asli terdecode >= 130 (dapat " + total + ")");
        // Model ketat (jatuh tidak tembus blok padat; jalan dekat zapper
        // fail-closed; batu/bola/tesla bukan pijakan) memberi 38 — ambang
        // 35 memberi ruang bila model diperketat lagi.
        check(okCount >= 35, "level walk-solvable >= 35 (dapat " + okCount + ")");

        // ---- 4. Solver sintetik ----
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

        // ---- 5. WorldGen ----
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
                    && ("Acak Hozin #" + (i + 1)).equals(back.title);
            if (r.status == Solver.Status.OK && boundsOk && bots == 1 && wins == 1
                    && maxFloorY <= WorldGen.MAX_HEIGHT && rt) {
                genOk++;
            } else {
                System.out.println("  info world #" + (i + 1) + " bermasalah: solve=" + r.status
                        + " bounds=" + boundsOk + " bot=" + bots + " win=" + wins
                        + " maxY=" + maxFloorY + " roundtrip=" + rt);
            }
        }
        check(genOk == 20, "WorldGen: 20/20 world solvable + dalam batas + roundtrip (" + genOk + "/20)");

        if (failures > 0) {
            System.out.println("SolverTest GAGAL: " + failures + " pemeriksaan");
            System.exit(1);
        }
        System.out.println("SolverTest LULUS semua pemeriksaan");
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
