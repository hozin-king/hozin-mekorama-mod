package com.hozinking.mekomod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * WorldGen — generator world acak untuk "Buat World Acak".
 * Murni Java (tanpa import Android) supaya bisa diuji di host/CI.
 *
 * Cara kerja:
 *  - Jalan acak (random walk) di tapak kecil (<= 8x8, tinggi lantai
 *    <= 4): tiap langkah pindah ke sel tetangga dan naik/turun maks 1
 *    tingkat — persis gerakan yang bisa dijalani robot — sehingga
 *    world yang dihasilkan TERHUBUNG oleh konstruksi.
 *  - Blok lantai pertama = BOT_YELLOW (start robot), blok lantai
 *    terakhir = WIN (finish), satu STAR di tengah jalan, beberapa
 *    dekorasi yang dijamin tidak menutup jalan / headroom-nya.
 *  - Setiap world DIVERIFIKASI dengan Solver yang sama dengan Auto Play
 *    (harus OK, 3..60 langkah); gagal -> regenerasi (maks 50x), lalu
 *    lapor gagal jujur (bukan world asal jadi).
 *  - World yang dihasilkan MURNI bisa jalan kaki: tanpa air, zapper,
 *    motor, bola, slider — supaya Auto Play bisa langsung memainkannya.
 */
public final class WorldGen {
    private WorldGen() {}

    /** Maksimal percobaan regenerasi sebelum menyerah. */
    public static final int MAX_TRIES = 50;
    /** Tapak maksimal (lebar x dalam). */
    public static final int MAX_FOOTPRINT = 8;
    /** Tinggi lantai maksimal. */
    public static final int MAX_HEIGHT = 4;

    /** Satu world acak yang sudah terverifikasi. */
    public static final class World {
        /** Grid 4096 byte. */
        public final byte[] grid;
        /** Nama world, mis. "Acak Hozin #3". */
        public final String title;
        /** File .bin siap tulis (header v2 + zlib). */
        public final byte[] binBytes;
        /** Panjang jalan solver (langkah). */
        public final int pathSteps;

        public World(byte[] grid, String title, byte[] binBytes, int pathSteps) {
            this.grid = grid;
            this.title = title;
            this.binBytes = binBytes;
            this.pathSteps = pathSteps;
        }
    }

    /**
     * Generate satu world acak. Mengembalikan null bila 50x percobaan
     * tidak menghasilkan world yang lolos solver (sangat jarang).
     */
    public static World generate(Random rnd, int number) {
        for (int attempt = 0; attempt < MAX_TRIES; attempt++) {
            byte[] grid = tryBuild(rnd);
            if (grid == null) continue;
            Solver.Result r = Solver.solve(grid);
            if (r.status == Solver.Status.OK && r.steps() >= 3 && r.steps() <= 60) {
                String title = "Acak Hozin #" + number;
                try {
                    byte[] bin = LevelFormat.encode(title, "Hozin", grid);
                    return new World(grid, title, bin, r.steps());
                } catch (IOException e) {
                    return null; // encode gagal = bug, jangan diam-diam lanjut
                }
            }
        }
        return null;
    }

    /** Satu percobaan membangun grid (null bila jalan buntu terlalu cepat). */
    private static byte[] tryBuild(Random rnd) {
        int w = 4 + rnd.nextInt(MAX_FOOTPRINT - 3); // 4..8
        int d = 4 + rnd.nextInt(MAX_FOOTPRINT - 3); // 4..8
        int hMax = 1 + rnd.nextInt(MAX_HEIGHT);     // 1..4
        int baseType = pickBaseType(rnd);

        byte[] grid = new byte[LevelFormat.GRID_SIZE];
        // Jalan acak: daftar sel kaki (x, y+1, z) yang dilalui robot.
        List<int[]> feet = new ArrayList<int[]>();
        Set<Integer> floorCells = new HashSet<Integer>();

        int x = rnd.nextInt(w), z = rnd.nextInt(d), y = 0;
        grid[LevelFormat.index(x, y, z)] = (byte) baseType;
        floorCells.add(LevelFormat.index(x, y, z));
        feet.add(new int[]{x, y + 1, z});

        int walkLen = 6 + rnd.nextInt(19); // 6..24 langkah
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int step = 0; step < walkLen; step++) {
            // Acak urutan arah tiap langkah.
            for (int i = dirs.length - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int[] tmp = dirs[i]; dirs[i] = dirs[j]; dirs[j] = tmp;
            }
            boolean moved = false;
            for (int[] dir : dirs) {
                int nx = x + dir[0], nz = z + dir[1];
                if (nx < 0 || nx >= w || nz < 0 || nz >= d) continue;
                int ny = y + rnd.nextInt(3) - 1; // -1 / 0 / +1
                if (ny < 0) ny = 0;
                if (ny > hMax) ny = hMax;
                int fi = LevelFormat.index(nx, ny, nz);
                if (floorCells.contains(fi)) continue; // jangan menumpuk lantai
                grid[fi] = (byte) baseType;
                floorCells.add(fi);
                x = nx; z = nz; y = ny;
                feet.add(new int[]{x, y + 1, z});
                moved = true;
                break;
            }
            if (!moved) break; // jalan buntu — selesai di sini
        }
        if (feet.size() < 4) return null; // terlalu pendek

        // Start = lantai pertama -> BOT_YELLOW; goal = lantai terakhir -> WIN.
        int[] first = feet.get(0);
        int[] last = feet.get(feet.size() - 1);
        grid[LevelFormat.index(first[0], first[1] - 1, first[2])] = (byte) Solver.BOT_YELLOW;
        grid[LevelFormat.index(last[0], last[1] - 1, last[2])] = (byte) Solver.WIN;

        // Satu bintang di tengah jalan (di sel kaki, dilewati robot).
        int[] mid = feet.get(feet.size() / 2);
        grid[LevelFormat.index(mid[0], mid[1], mid[2])] = (byte) Solver.STAR;

        // Dekorasi: tidak boleh menempati sel kaki jalan & bukan headroom-nya.
        Set<Integer> blocked = new HashSet<Integer>();
        for (int[] f : feet) {
            blocked.add(LevelFormat.index(f[0], f[1], f[2]));
            if (f[1] + 1 < LevelFormat.DIM) {
                blocked.add(LevelFormat.index(f[0], f[1] + 1, f[2]));
            }
        }
        int[] decoTypes = {Solver.FENCE_COPPER, Solver.CUBE_BRICK, Solver.WEDGE_STONE};
        int decoCount = rnd.nextInt(4); // 0..3 dekorasi
        for (int i = 0; i < decoCount; i++) {
            int dx = rnd.nextInt(w), dz = rnd.nextInt(d);
            int groundIdx = LevelFormat.index(dx, 0, dz);
            int decoIdx = LevelFormat.index(dx, 1, dz);
            if (grid[groundIdx] != 0) continue;
            if (blocked.contains(decoIdx)) continue;
            if (grid[decoIdx] != 0) continue;
            grid[groundIdx] = (byte) baseType;
            grid[decoIdx] = (byte) decoTypes[rnd.nextInt(decoTypes.length)];
        }
        // Beberapa pulau lantai (y=0) untuk rupa — di sel kosong saja.
        int islands = rnd.nextInt(4);
        for (int i = 0; i < islands; i++) {
            int ix = rnd.nextInt(w), iz = rnd.nextInt(d);
            int idx = LevelFormat.index(ix, 0, iz);
            if (grid[idx] == 0) grid[idx] = (byte) baseType;
        }
        return grid;
    }

    private static int pickBaseType(Random rnd) {
        int[] types = {Solver.CUBE_DIRT, Solver.CUBE_GRASS, Solver.CUBE_BRICK,
                Solver.CUBE_STEEL, Solver.CUBE_WOOD};
        return types[rnd.nextInt(types.length)];
    }
}
