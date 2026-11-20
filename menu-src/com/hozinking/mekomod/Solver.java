package com.hozinking.mekomod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Solver — A* di atas grid voxel game (16x16x16) untuk Auto Play.
 * Murni Java (tanpa import Android) supaya bisa diuji di host/CI.
 *
 * Model gerak (disalin dari fungsi engine `walkable` 0xd4110 di
 * libmekorama.so, dibuktikan REA + diuji terhadap 144 level asli):
 *  - Robot berdiri di sel kaki (x,y,z): sel DI BAWAH (y-1) harus pijakan
 *    padat (tipe > 1, bukan air/tesla/bola/roda), sel kaki + sel di
 *    atasnya harus kosong (bintang & mata robot dianggap kosong).
 *  - Jalan datar ke 4 tetangga horizontal; naik 1 tingkat (step-up) ke
 *    tetangga yang pijakannya 1 lebih tinggi; jatuh: jalan ke tetangga
 *    yang kosong lalu jatuh sampai pijakan walkable pertama (mati bila
 *    jatuh ke air / keluar dunia / menabrak blok yang tak bisa dipijak).
 *  - Batu polos (tipe 1) BUKAN pijakan valid menurut engine — level
 *    dirancang demikian (solver yang menganggap batu bisa dipijak akan
 *    memberi jalan yang tidak bisa dijalani game).
 *
 * Heuristik A*: jarak Manhattan (|dx|+|dy|+|dz|) — sudah mencakup
 * komponen tinggi. Batas: maks 200.000 node, maks ~2 detik, supaya
 * tombol ON -> robot langsung jalan (tanpa loading lama).
 *
 * Elemen dinamis (motor / bola / slider / zapper):
 *  - Zapper (tesla): fase 1 A* memblokir sel yang berdekatan dengan
 *    tesla; bila hanya ada jalan lewat dekat zapper, fase 2 menemukannya
 *    tapi status HAS_ZAPPER (fail-closed: timing zapper belum bisa
 *    dibaca dari game, jadi tidak dijalani).
 *  - Motor/bola/slider: bila jalan buntu, solver menjalankan BFS
 *    "longgar" (elemen dinamis dianggap tembus) untuk menamai penyebab
 *    yang paling mungkin (motor / bola / butuh geser blok).
 *  - Level yang butuh MENGGESER blok (drag) tidak bisa di-solve oleh
 *    jalan kaki saja — dilaporkan jujur (NEEDS_DRAG), bukan dipaksa.
 */
public final class Solver {
    private Solver() {}

    // ---- Tipe blok (dari tabel block_type_names, libmekorama.so) ----
    public static final int NONE = 0;
    public static final int CUBE_STONE = 1;
    public static final int CUBE_BRICK = 2;
    public static final int LOGIC = 3;
    public static final int WIN = 4;
    public static final int STAIRS_STONE = 5;
    public static final int TRASH = 6;
    public static final int WEDGE_STONE = 7;
    public static final int WEDGE_DIRT = 8;
    public static final int BALL_POWER = 9;
    public static final int BUTTON = 10;
    public static final int WATER = 11;
    public static final int CUBE_GRASS = 12;
    public static final int SOKOBAN = 13;
    public static final int ROUND_CORNER_STONE = 14;
    public static final int BOT_YELLOW = 15;
    public static final int TESLA = 16;
    public static final int DRAG_COPPER = 17;
    public static final int CUBE_DIRT = 18;
    public static final int WHEEL = 19;
    public static final int STAIRS_STEEL = 20;
    public static final int ROUND_CORNER_STEEL = 21;
    public static final int MOTOR = 22;
    public static final int WIFI = 23;
    public static final int REMOVE2 = 24;
    public static final int CUBE_STEEL = 25;
    public static final int BOT_RED = 26;
    public static final int EYE = 27;
    public static final int INVISIBLE = 28;
    public static final int CUBE_WOOD = 29;
    public static final int RAIL_BEND = 30;
    public static final int CYLINDER_WOOD = 31;
    public static final int ROUNDED_STEEL = 32;
    public static final int RAIL = 33;
    public static final int ROUNDED_STONE = 34;
    public static final int CYLINDER_STONE = 35;
    public static final int CYLINDER_DRAGGABLE = 36;
    public static final int BALL_STEEL = 37;
    public static final int REMOVE4 = 38;
    public static final int CYLINDER_STEEL = 39;
    public static final int CONTROL = 40;
    public static final int SLIDER = 41;
    public static final int MUSIC_JAPAN = 42;
    public static final int FENCE_COPPER = 43;
    public static final int TIMER = 44;
    public static final int MUSIC_BELL = 45;
    public static final int MUSIC_PAD = 46;
    public static final int TAP = 47;
    public static final int RANDOM = 48;
    public static final int WEDGE_STEEL = 49;
    public static final int BOT_LEFT = 50;
    public static final int STAR = 51;
    public static final int CUBE_DIRT_PERM = 52;
    public static final int MANIPULATION = 53;
    // 54..67 = SIGNAL_* (gerbang logika)

    public static final int MAX_NODES = 200000;
    public static final long MAX_MS = 2000;
    /** Biaya tambahan per sel yang berdekatan dengan zapper (fase 2). */
    public static final int TESLA_NEAR_COST = 10;

    /** Satu sel grid (sel kaki robot). */
    public static final class Cell {
        public final int x, y, z;
        public Cell(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public int index() { return LevelFormat.index(x, y, z); }
        @Override public String toString() { return "(" + x + "," + y + "," + z + ")"; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Cell)) return false;
            Cell c = (Cell) o;
            return x == c.x && y == c.y && z == c.z;
        }
        @Override public int hashCode() { return index(); }
    }

    public enum Status {
        /** Ada jalan kaki yang valid. */
        OK,
        /** Tidak ada jalan kaki (struktur putus / butuh susun ulang). */
        UNSOLVABLE,
        /** Level butuh geser blok (drag) — jalan kaki saja tidak cukup. */
        NEEDS_DRAG,
        /** Ada motor di/near jalur yang mungkin — belum bisa dimodelkan. */
        HAS_MOTOR,
        /** Ada bola di/near jalur — butuh simulasi fisika penuh. */
        HAS_BALL,
        /** Satu-satunya jalan lewat dekat zapper — timing belum didukung. */
        HAS_ZAPPER,
        /** Tidak ada robot (bot kuning/merah/kiri) di level. */
        NO_BOT,
        /** Tidak ada blok finish (WIN) di level. */
        NO_GOAL,
        /** Batas node/waktu A* tercapai. */
        TIMEOUT
    }

    public static final class Result {
        public final Status status;
        /** Jalan dari sel awal ke sel goal (inklusif), null bila gagal. */
        public final List<Cell> path;
        /** Alasan yang bisa dibaca manusia (untuk status menu). */
        public final String reason;
        /** Sel awal robot (di atas blok bot), null bila tidak ada. */
        public final Cell start;
        /** Sel goal (di atas blok WIN), null bila tidak ada. */
        public final Cell goal;
        /** true bila jalan melewati dekat zapper (hanya di fase 2). */
        public final boolean nearTesla;
        /** Ringkasan elemen dinamis di level, mis. "motor:2 bola:1". */
        public final String dynamics;

        public Result(Status status, List<Cell> path, String reason,
                      Cell start, Cell goal, boolean nearTesla, String dynamics) {
            this.status = status;
            this.path = path;
            this.reason = reason;
            this.start = start;
            this.goal = goal;
            this.nearTesla = nearTesla;
            this.dynamics = dynamics;
        }

        /** Jumlah langkah (sel yang dilalui - 1). -1 bila tidak ada jalan. */
        public int steps() { return path == null ? -1 : path.size() - 1; }
    }

    // ------------------------------------------------------------------
    // API utama
    // ------------------------------------------------------------------

    public static Result solve(byte[] grid) {
        if (grid == null || grid.length != LevelFormat.GRID_SIZE) {
            return new Result(Status.UNSOLVABLE, null,
                    "grid level tidak valid (bukan 4096 byte)", null, null, false, "");
        }
        String dynamics = describeDynamics(grid);

        // Kumpulkan bot (coba kuning dulu, lalu merah, lalu kiri) & goal.
        List<Integer> bots = new ArrayList<Integer>();
        List<Integer> goals = new ArrayList<Integer>();
        for (int i = 0; i < LevelFormat.GRID_SIZE; i++) {
            int t = grid[i] & 0xFF;
            if (t == BOT_YELLOW || t == BOT_RED || t == BOT_LEFT) bots.add(i);
            else if (t == WIN) goals.add(i);
        }
        if (bots.isEmpty()) {
            return new Result(Status.NO_BOT, null,
                    "level ini tidak punya robot (mungkin level bola / tanpa bot)",
                    null, null, false, dynamics);
        }
        if (goals.isEmpty()) {
            return new Result(Status.NO_GOAL, null,
                    "level ini tidak punya blok finish (WIN)", null, null, false, dynamics);
        }
        // Sel goal = sel DI ATAS tiap blok WIN.
        boolean[] isGoal = new boolean[LevelFormat.GRID_SIZE];
        Cell firstGoal = null;
        for (int g : goals) {
            int gx = LevelFormat.xOf(g), gy = LevelFormat.yOf(g), gz = LevelFormat.zOf(g);
            if (gy + 1 < LevelFormat.DIM) {
                isGoal[LevelFormat.index(gx, gy + 1, gz)] = true;
                if (firstGoal == null) firstGoal = new Cell(gx, gy + 1, gz);
            }
        }
        if (firstGoal == null) {
            return new Result(Status.NO_GOAL, null,
                    "blok finish ada tapi tidak ada ruang di atasnya", null, null, false, dynamics);
        }

        // Coba tiap bot sebagai awal.
        for (int b : bots) {
            Cell start = new Cell(LevelFormat.xOf(b), LevelFormat.yOf(b) + 1, LevelFormat.zOf(b));
            if (LevelFormat.yOf(b) + 1 >= LevelFormat.DIM) continue;
            if (!walkable(grid, start.x, start.y, start.z)) continue;
            // Fase 1: hindari zapper sepenuhnya.
            Result r1 = astar(grid, start, isGoal, goalList(goals), true, dynamics);
            if (r1.status == Status.OK) return r1;
            if (r1.status == Status.TIMEOUT) {
                return new Result(Status.TIMEOUT, null,
                        "solver kehabisan waktu/node (" + MAX_NODES + " node)",
                        start, firstGoal, false, dynamics);
            }
            // Fase 2: bila ada tesla, coba jalan lewat dekat zapper (biaya tinggi).
            if (LevelFormat.countType(grid, TESLA) > 0) {
                Result r2 = astar(grid, start, isGoal, goalList(goals), false, dynamics);
                if (r2.status == Status.OK && r2.nearTesla) {
                    return new Result(Status.HAS_ZAPPER, null,
                            "satu-satunya jalan lewat dekat zapper (tesla) — "
                            + "timing zapper belum bisa dibaca dari game, jadi berhenti",
                            start, firstGoal, true, dynamics);
                }
                if (r2.status == Status.OK) return r2;
            }
            // Tidak ada jalan dari bot ini — diagnosa penyebabnya.
            return diagnose(grid, start, isGoal, firstGoal, dynamics);
        }
        return new Result(Status.UNSOLVABLE, null,
                "robot ada tapi sel awalnya tidak bisa dipijak (tertutup / tanpa pijakan)",
                null, firstGoal, false, dynamics);
    }

    // ------------------------------------------------------------------
    // Model gerak (cerminan walkable() engine)
    // ------------------------------------------------------------------

    /** Sel bisa dilewati robot (bintang & mata dianggap kosong). */
    public static boolean passable(int type) {
        return type == NONE || type == STAR || type == EYE;
    }

    /** Tipe blok yang bisa jadi pijakan di bawah kaki robot. */
    public static boolean solidGround(int type) {
        return type > 1 && type != WATER && type != TESLA
                && type != BALL_STEEL && type != BALL_POWER && type != WHEEL;
    }

    /** Apakah robot bisa BERDIRI di sel (x,y,z) (kaki di y, kepala di y+1). */
    public static boolean walkable(byte[] grid, int x, int y, int z) {
        if (x < 0 || x >= LevelFormat.DIM || y < 0 || y >= LevelFormat.DIM
                || z < 0 || z >= LevelFormat.DIM) {
            return false;
        }
        int below = (y == 0) ? 2 : (grid[LevelFormat.index(x, y - 1, z)] & 0xFF);
        if (!solidGround(below)) return false;
        if (!passable(grid[LevelFormat.index(x, y, z)] & 0xFF)) return false;
        if (y + 1 < LevelFormat.DIM
                && !passable(grid[LevelFormat.index(x, y + 1, z)] & 0xFF)) {
            return false;
        }
        return true;
    }

    /** true bila ada tesla di lingkungan 3x3x3 sel ini. */
    public static boolean nearTesla(byte[] grid, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (nx < 0 || nx >= LevelFormat.DIM || ny < 0 || ny >= LevelFormat.DIM
                            || nz < 0 || nz >= LevelFormat.DIM) continue;
                    if ((grid[LevelFormat.index(nx, ny, nz)] & 0xFF) == TESLA) return true;
                }
            }
        }
        return false;
    }

    /**
     * Dari sel udara (x,y,z): jatuh ke bawah sampai pijakan walkable
     * pertama. null bila mati (air / keluar dunia / menabrak blok yang
     * tak bisa dipijak).
     */
    public static Cell fallLand(byte[] grid, int x, int y, int z) {
        for (int yy = y; yy >= 0; yy--) {
            if (walkable(grid, x, yy, z)) return new Cell(x, yy, z);
            int t = grid[LevelFormat.index(x, yy, z)] & 0xFF;
            if (!passable(t)) return null; // menabrak blok yang tak bisa dipijak
        }
        return null; // jatuh keluar dunia
    }

    // ------------------------------------------------------------------
    // A*
    // ------------------------------------------------------------------

    private static Result astar(byte[] grid, Cell start, boolean[] isGoal,
                                List<Cell> goalCells, boolean avoidTesla, String dynamics) {
        final long deadline = System.currentTimeMillis() + MAX_MS;
        final int[] gScore = new int[LevelFormat.GRID_SIZE];
        final int[] parent = new int[LevelFormat.GRID_SIZE];
        final boolean[] closed = new boolean[LevelFormat.GRID_SIZE];
        Arrays.fill(gScore, Integer.MAX_VALUE);
        Arrays.fill(parent, -1);
        int startIdx = start.index();
        gScore[startIdx] = 0;

        // Antrian prioritas: (f = g + h, indeks sel). h = Manhattan.
        PriorityQueue<int[]> open = new PriorityQueue<int[]>(64, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                if (a[0] != b[0]) return a[0] - b[0];
                return a[1] - b[1];
            }
        });
        open.add(new int[]{heuristic(start, goalCells), startIdx});

        int expanded = 0;
        boolean foundTeslaNear = false;
        int goalIdx = -1;
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!open.isEmpty()) {
            int[] cur = open.poll();
            int curIdx = cur[1];
            if (closed[curIdx]) continue;
            closed[curIdx] = true;
            if (++expanded > MAX_NODES || (expanded & 1023) == 0
                    && System.currentTimeMillis() > deadline) {
                return new Result(Status.TIMEOUT, null, "batas A* tercapai",
                        start, null, false, dynamics);
            }
            if (isGoal[curIdx]) { goalIdx = curIdx; break; }
            int cx = LevelFormat.xOf(curIdx);
            int cy = LevelFormat.yOf(curIdx);
            int cz = LevelFormat.zOf(curIdx);
            int g = gScore[curIdx];

            for (int[] d : dirs) {
                int nx = cx + d[0], nz = cz + d[1];
                if (nx < 0 || nx >= LevelFormat.DIM || nz < 0 || nz >= LevelFormat.DIM) continue;
                // (a) jalan datar
                tryRelax(grid, goalCells, avoidTesla, open, gScore, parent, closed,
                        curIdx, g, nx, cy, nz);
                // (b) naik 1 tingkat
                if (cy + 1 < LevelFormat.DIM) {
                    tryRelax(grid, goalCells, avoidTesla, open, gScore, parent, closed,
                            curIdx, g, nx, cy + 1, nz);
                }
                // (c) jatuh: tetangga datar kosong -> jatuh ke bawah
                int tHere = grid[LevelFormat.index(nx, cy, nz)] & 0xFF;
                int tAbove = (cy + 1 < LevelFormat.DIM)
                        ? (grid[LevelFormat.index(nx, cy + 1, nz)] & 0xFF) : NONE;
                if (passable(tHere) && passable(tAbove)) {
                    Cell land = fallLand(grid, nx, cy, nz);
                    if (land != null && land.index() != curIdx) {
                        relax(grid, goalCells, avoidTesla, open, gScore, parent, closed,
                                curIdx, g, land);
                    }
                }
            }
        }
        if (goalIdx < 0) {
            return new Result(Status.UNSOLVABLE, null, "tidak ada jalan", start, null, false, dynamics);
        }
        // Rekonstruksi jalan.
        List<Cell> path = new ArrayList<Cell>();
        for (int n = goalIdx; n != -1; n = parent[n]) {
            Cell c = new Cell(LevelFormat.xOf(n), LevelFormat.yOf(n), LevelFormat.zOf(n));
            path.add(0, c);
            if (nearTesla(grid, c.x, c.y, c.z)) foundTeslaNear = true;
        }
        Cell goal = path.get(path.size() - 1);
        return new Result(Status.OK, path, "jalan ditemukan (" + (path.size() - 1) + " langkah)",
                start, goal, foundTeslaNear, dynamics);
    }

    private static void tryRelax(byte[] grid, List<Cell> goalCells, boolean avoidTesla,
                                 PriorityQueue<int[]> open, int[] gScore, int[] parent,
                                 boolean[] closed, int curIdx, int g,
                                 int nx, int ny, int nz) {
        if (!walkable(grid, nx, ny, nz)) return;
        relax(grid, goalCells, avoidTesla, open, gScore, parent, closed,
                curIdx, g, new Cell(nx, ny, nz));
    }

    private static void relax(byte[] grid, List<Cell> goalCells, boolean avoidTesla,
                              PriorityQueue<int[]> open, int[] gScore, int[] parent,
                              boolean[] closed, int curIdx, int g, Cell next) {
        int ni = next.index();
        if (closed[ni]) return;
        boolean nearT = nearTesla(grid, next.x, next.y, next.z);
        if (avoidTesla && nearT) return;
        int stepCost = 1 + (nearT ? TESLA_NEAR_COST : 0);
        int ng = g + stepCost;
        if (ng < gScore[ni]) {
            gScore[ni] = ng;
            parent[ni] = curIdx;
            open.add(new int[]{ng + heuristic(next, goalCells), ni});
        }
    }

    /** Manhattan ke sel goal terdekat (|dx|+|dy|+|dz|, sudah mencakup tinggi). */
    private static int heuristic(Cell c, List<Cell> goalCells) {
        int best = Integer.MAX_VALUE;
        for (Cell g : goalCells) {
            int d = Math.abs(g.x - c.x) + Math.abs(g.y - c.y) + Math.abs(g.z - c.z);
            if (d < best) best = d;
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    /** Daftar sel goal (di atas blok WIN) dari indeks-indeks blok WIN. */
    private static List<Cell> goalList(List<Integer> winIndices) {
        List<Cell> out = new ArrayList<Cell>();
        for (int g : winIndices) {
            int gy = LevelFormat.yOf(g);
            if (gy + 1 < LevelFormat.DIM) {
                out.add(new Cell(LevelFormat.xOf(g), gy + 1, LevelFormat.zOf(g)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Diagnosa kegagalan
    // ------------------------------------------------------------------

    private static Result diagnose(byte[] grid, Cell start, boolean[] isGoal,
                                   Cell firstGoal, String dynamics) {
        // BFS longgar: elemen dinamis (motor/bola/slider/draggable/roda)
        // dianggap tembus — bila ada jalan di model longgar, penyebabnya
        // adalah elemen itu; bila tetap tidak ada, jalurnya memang putus
        // (butuh geser blok / susun ulang).
        List<Cell> relaxed = relaxedBfs(grid, start, isGoal);
        if (relaxed == null) {
            return new Result(Status.NEEDS_DRAG, null,
                    "jalur putus — level ini kemungkinan butuh geser blok "
                    + "(drag) atau susun ulang, bukan sekadar jalan kaki",
                    start, firstGoal, false, dynamics);
        }
        // Sebutkan elemen dinamis yang dilewati jalan longgar itu.
        for (Cell c : relaxed) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = c.x + dx, ny = c.y + dy, nz = c.z + dz;
                        if (nx < 0 || nx >= LevelFormat.DIM || ny < 0 || ny >= LevelFormat.DIM
                                || nz < 0 || nz >= LevelFormat.DIM) continue;
                        int t = grid[LevelFormat.index(nx, ny, nz)] & 0xFF;
                        if (t == MOTOR) {
                            return new Result(Status.HAS_MOTOR, null,
                                    "jalur melewati motor (blok bergerak) — "
                                    + "gerak motor belum bisa dimodelkan dari state game",
                                    start, firstGoal, false, dynamics);
                        }
                        if (t == BALL_STEEL || t == BALL_POWER) {
                            return new Result(Status.HAS_BALL, null,
                                    "jalur melewati bola — butuh simulasi fisika penuh, "
                                    + "belum bisa di-solve",
                                    start, firstGoal, false, dynamics);
                        }
                        if (t == SLIDER || t == DRAG_COPPER || t == CYLINDER_DRAGGABLE
                                || t == SOKOBAN) {
                            return new Result(Status.NEEDS_DRAG, null,
                                    "jalur butuh geser blok (slider/draggable) — "
                                    + "belum bisa di-solve",
                                    start, firstGoal, false, dynamics);
                        }
                    }
                }
            }
        }
        return new Result(Status.UNSOLVABLE, null,
                "tidak ada jalan kaki — penyebab pastinya belum terpetakan "
                + "(dinamika: " + dynamics + ")", start, firstGoal, false, dynamics);
    }

    /** BFS dengan elemen dinamis dianggap tembus (untuk diagnosa saja). */
    private static List<Cell> relaxedBfs(byte[] grid, Cell start, boolean[] isGoal) {
        boolean[] seen = new boolean[LevelFormat.GRID_SIZE];
        int[] parent = new int[LevelFormat.GRID_SIZE];
        Arrays.fill(parent, -1);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<Integer>();
        q.add(start.index());
        seen[start.index()] = true;
        int goalIdx = -1;
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int cur = q.poll();
            if (isGoal[cur]) { goalIdx = cur; break; }
            int cx = LevelFormat.xOf(cur), cy = LevelFormat.yOf(cur), cz = LevelFormat.zOf(cur);
            for (int[] d : dirs) {
                int nx = cx + d[0], nz = cz + d[1];
                if (nx < 0 || nx >= LevelFormat.DIM || nz < 0 || nz >= LevelFormat.DIM) continue;
                for (int ny = cy - 1; ny <= cy + 1; ny++) {
                    if (ny < 0 || ny >= LevelFormat.DIM) continue;
                    int ni = LevelFormat.index(nx, ny, nz);
                    if (seen[ni]) continue;
                    if (relaxedWalkable(grid, nx, ny, nz)) {
                        seen[ni] = true;
                        parent[ni] = cur;
                        q.add(ni);
                    }
                }
            }
        }
        if (goalIdx < 0) return null;
        List<Cell> path = new ArrayList<Cell>();
        for (int n = goalIdx; n != -1; n = parent[n]) {
            path.add(0, new Cell(LevelFormat.xOf(n), LevelFormat.yOf(n), LevelFormat.zOf(n)));
        }
        return path;
    }

    /** Versi longgar walkable(): blok dinamis dianggap tembus/pijakan. */
    private static boolean relaxedWalkable(byte[] grid, int x, int y, int z) {
        if (x < 0 || x >= LevelFormat.DIM || y < 0 || y >= LevelFormat.DIM
                || z < 0 || z >= LevelFormat.DIM) return false;
        int below = (y == 0) ? 2 : (grid[LevelFormat.index(x, y - 1, z)] & 0xFF);
        if (below <= 1 || below == WATER) return false;
        int here = grid[LevelFormat.index(x, y, z)] & 0xFF;
        if (here != NONE && here != STAR && here != EYE && !isDynamicBlocker(here)) return false;
        if (y + 1 < LevelFormat.DIM) {
            int above = grid[LevelFormat.index(x, y + 1, z)] & 0xFF;
            if (above != NONE && above != STAR && above != EYE && !isDynamicBlocker(above)) return false;
        }
        return true;
    }

    private static boolean isDynamicBlocker(int t) {
        return t == MOTOR || t == BALL_STEEL || t == BALL_POWER || t == SLIDER
                || t == DRAG_COPPER || t == CYLINDER_DRAGGABLE || t == SOKOBAN
                || t == WHEEL || t == TESLA;
    }

    /** Ringkasan elemen dinamis di level, mis. "motor:2 bola:1 zapper:1". */
    public static String describeDynamics(byte[] grid) {
        int motor = LevelFormat.countType(grid, MOTOR);
        int ball = LevelFormat.countType(grid, BALL_STEEL) + LevelFormat.countType(grid, BALL_POWER);
        int tesla = LevelFormat.countType(grid, TESLA);
        int slider = LevelFormat.countType(grid, SLIDER);
        int drag = LevelFormat.countType(grid, DRAG_COPPER)
                + LevelFormat.countType(grid, CYLINDER_DRAGGABLE)
                + LevelFormat.countType(grid, SOKOBAN);
        int button = LevelFormat.countType(grid, BUTTON);
        int timer = LevelFormat.countType(grid, TIMER);
        int signal = 0;
        for (int t = 54; t <= 67; t++) signal += LevelFormat.countType(grid, t);
        StringBuilder sb = new StringBuilder();
        if (motor > 0) sb.append("motor:").append(motor).append(' ');
        if (ball > 0) sb.append("bola:").append(ball).append(' ');
        if (tesla > 0) sb.append("zapper:").append(tesla).append(' ');
        if (slider > 0) sb.append("slider:").append(slider).append(' ');
        if (drag > 0) sb.append("drag:").append(drag).append(' ');
        if (button > 0) sb.append("tombol:").append(button).append(' ');
        if (timer > 0) sb.append("timer:").append(timer).append(' ');
        if (signal > 0) sb.append("logika:").append(signal).append(' ');
        String s = sb.toString().trim();
        return s.isEmpty() ? "statis" : s;
    }
}
