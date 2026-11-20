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

    /** Satu aksi dalam rencana Auto Play (v1.2): jalan / geser blok. */
    public static final class Step {
        /** Jenis aksi. */
        public enum Kind { WALK, DRAG, SLIDE }
        public final Kind kind;
        /** WALK: sel tujuan robot. DRAG/SLIDE: sel asal blok. */
        public final Cell from;
        /** DRAG/SLIDE: sel tujuan blok. WALK: sama dengan from. */
        public final Cell to;
        /** DRAG/SLIDE: tipe blok yang digeser. */
        public final int type;

        private Step(Kind kind, Cell from, Cell to, int type) {
            this.kind = kind;
            this.from = from;
            this.to = to;
            this.type = type;
        }

        public static Step walk(Cell to) { return new Step(Kind.WALK, to, to, NONE); }
        public static Step drag(Cell from, Cell to, int type) {
            return new Step(Kind.DRAG, from, to, type);
        }
        public static Step slide(Cell from, Cell to) {
            return new Step(Kind.SLIDE, from, to, SLIDER);
        }

        @Override public String toString() {
            switch (kind) {
                case WALK: return "jalan " + to;
                case DRAG: return "geser blok tipe " + type + " " + from + " -> " + to;
                case SLIDE: return "geser slider " + from + " -> " + to;
                default: return "?";
            }
        }
    }

    public static final class Result {
        public final Status status;
        /** Jalan dari sel awal ke sel goal (inklusif), null bila gagal. */
        public final List<Cell> path;
        /**
         * Rencana aksi lengkap (jalan + geser blok) yang dijalankan
         * Auto Play — null bila status gagal atau jalan murni jalan kaki
         * (di situ path sudah cukup). Bila ada, Auto Play menjalankan
         * actions, bukan path.
         */
        public final List<Step> actions;
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
            this(status, path, null, reason, start, goal, nearTesla, dynamics);
        }

        public Result(Status status, List<Cell> path, List<Step> actions, String reason,
                      Cell start, Cell goal, boolean nearTesla, String dynamics) {
            this.status = status;
            this.path = path;
            this.actions = actions;
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
        return solve(grid, null);
    }

    /**
     * Solve dengan byte rot per sel (voxels_rot game) — dibutuhkan untuk
     * menentukan sumbu slider (tipe 41). Bila rot null, semua dianggap 0.
     */
    public static Result solve(byte[] grid, byte[] rot) {
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
            // Fase 3 (v1.2): level punya blok yang bisa digeser — coba
            // dulu perencana konstruktif (cepat, terarah: isi pijakan
            // yang putus dengan blok drag), lalu A* blok-geser bila
            // masih gagal. Bila berhasil, hasilnya berisi rencana aksi.
            if (hasMovables(grid)) {
                Result r3 = constructiveDragSolve(grid, rot, start, isGoal,
                        goalList(goals), firstGoal, dynamics);
                if (r3.status == Status.OK) return r3;
                Result r4 = dragSolve(grid, rot, start, isGoal,
                        goalList(goals), firstGoal, dynamics);
                if (r4.status == Status.OK) return r4;
                if (r4.status == Status.TIMEOUT) return r4;
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
    // A* dengan blok geser (v1.2) — state = (robot + posisi blok geser)
    //
    // Aturan gerak blok (dibuktikan REA libmekorama.so 1.7.2):
    //  - Slider (tipe 41): sumbu dari byte rot-nya (block_slider_dir
    //    0x1397d8 / tabel rots_face_nums 0x9d5c1): rot 16..23 -> Y,
    //    rot genap -> X, rot ganjil -> Z. Rel = run RAIL (33) berurutan
    //    sepanjang sumbu melewati sel slider. Slider bergerak +-1 sel
    //    sepanjang rel; sel yang ditinggal jadi RAIL lagi. Robot bisa
    //    berdiri di atas slider (ikut "naik" bila slider bergerak).
    //  - Drag (tipe 17 DRAG_COPPER, 36 CYLINDER_DRAGGABLE): pemain
    //    menggeser blok ke sel kosong dekat robot (jarak Chebyshev
    //    <= 2 dari kaki robot), lalu blok jatuh (gravitasi) sampai
    //    bertumpu pada blok padat / lantai (y=0). Blok tidak boleh
    //    dijatuhkan ke robot, ke sel finish, atau keluar dunia.
    //  - Eksekusi di game memakai fungsi game sendiri voxel_remove +
    //    voxel_add (payload native) — bukan suntik posisi robot.
    //  - SOKOBAN (13, kotak dorong) belum dimodelkan — robot mendorong
    //    kotak itu mekanik lain; level yang butuh itu tetap dilaporkan
    //    jujur sebagai NEEDS_DRAG.
    // ------------------------------------------------------------------

    /** true bila level punya slider / blok drag yang bisa digeser. */
    public static boolean hasMovables(byte[] grid) {
        return LevelFormat.countType(grid, SLIDER) > 0
                || LevelFormat.countType(grid, DRAG_COPPER) > 0
                || LevelFormat.countType(grid, CYLINDER_DRAGGABLE) > 0;
    }

    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;

    /**
     * Sumbu gerak slider dari byte rot-nya (block_slider_dir/2):
     * rot 16..23 -> Y (vertikal); rot genap -> X; rot ganjil -> Z.
     */
    public static int sliderAxis(int rot) {
        if (rot >= 16) return AXIS_Y;
        return (rot % 2 == 0) ? AXIS_X : AXIS_Z;
    }

    /** Satu blok yang bisa digeser (slider atau drag). */
    private static final class Movable {
        final int type;
        final int axis;        // slider saja (AXIS_X/Y/Z), -1 untuk drag
        final boolean[] track; // slider saja: sel-sel relnya
        final int startCell;
        Movable(int type, int axis, boolean[] track, int startCell) {
            this.type = type;
            this.axis = axis;
            this.track = track;
            this.startCell = startCell;
        }
    }

    /** Satu state A* blok-geser: sel robot + posisi tiap blok geser. */
    private static final class DState {
        final int robot;
        final int[] pos;
        DState(int robot, int[] pos) { this.robot = robot; this.pos = pos; }
        String key() {
            StringBuilder sb = new StringBuilder(32);
            sb.append(robot);
            for (int p : pos) sb.append(',').append(p);
            return sb.toString();
        }
    }

    /** Rel slider = run RAIL berurutan sepanjang sumbu + sel slider. */
    private static boolean[] sliderTrack(byte[] grid, int sliderCell, int axis) {
        boolean[] track = new boolean[LevelFormat.GRID_SIZE];
        track[sliderCell] = true;
        int sx = LevelFormat.xOf(sliderCell);
        int sy = LevelFormat.yOf(sliderCell);
        int sz = LevelFormat.zOf(sliderCell);
        int dx = axis == AXIS_X ? 1 : 0;
        int dy = axis == AXIS_Y ? 1 : 0;
        int dz = axis == AXIS_Z ? 1 : 0;
        for (int dir = -1; dir <= 1; dir += 2) {
            for (int k = 1; k < LevelFormat.DIM; k++) {
                int nx = sx + dx * dir * k;
                int ny = sy + dy * dir * k;
                int nz = sz + dz * dir * k;
                if (nx < 0 || nx >= LevelFormat.DIM || ny < 0 || ny >= LevelFormat.DIM
                        || nz < 0 || nz >= LevelFormat.DIM) break;
                int t = grid[LevelFormat.index(nx, ny, nz)] & 0xFF;
                if (t != RAIL) break;
                track[LevelFormat.index(nx, ny, nz)] = true;
            }
        }
        return track;
    }

    /** Tipe blok efektif di sel, dengan posisi blok geser saat ini. */
    private static int typeAt(byte[] grid, List<Movable> movs, int[] pos,
                              boolean[] trackMask, int cell) {
        for (int k = 0; k < movs.size(); k++) {
            if (pos[k] == cell) return movs.get(k).type;
        }
        if (trackMask[cell]) {
            int base = grid[cell] & 0xFF;
            if (base == SLIDER || base == RAIL) return RAIL;
        }
        // Sel asal blok geser yang sudah pindah = kosong. Tanpa ini,
        // grid dasar (yang masih menyimpan blok di posisi awalnya)
        // membuat solver mengira blok itu masih di sana — blok hantu
        // yang memblokir jalan dan mengacaukan jembatan.
        for (int k = 0; k < movs.size(); k++) {
            if (movs.get(k).startCell == cell && pos[k] != cell) {
                return NONE;
            }
        }
        return grid[cell] & 0xFF;
    }

    /** walkable() versi state blok-geser (tipe dibaca lewat typeAt). */
    private static boolean walkableState(byte[] grid, List<Movable> movs, int[] pos,
                                         boolean[] trackMask, int x, int y, int z) {
        if (x < 0 || x >= LevelFormat.DIM || y < 0 || y >= LevelFormat.DIM
                || z < 0 || z >= LevelFormat.DIM) return false;
        int below = (y == 0) ? 2 : typeAt(grid, movs, pos, trackMask,
                LevelFormat.index(x, y - 1, z));
        if (!solidGround(below)) return false;
        if (!passable(typeAt(grid, movs, pos, trackMask, LevelFormat.index(x, y, z)))) {
            return false;
        }
        if (y + 1 < LevelFormat.DIM
                && !passable(typeAt(grid, movs, pos, trackMask,
                        LevelFormat.index(x, y + 1, z)))) {
            return false;
        }
        return true;
    }

    /** fallLand() versi state blok-geser. */
    private static Cell fallLandState(byte[] grid, List<Movable> movs, int[] pos,
                                      boolean[] trackMask, int x, int y, int z) {
        for (int yy = y; yy >= 0; yy--) {
            if (walkableState(grid, movs, pos, trackMask, x, yy, z)) {
                return new Cell(x, yy, z);
            }
            int t = typeAt(grid, movs, pos, trackMask, LevelFormat.index(x, yy, z));
            if (!passable(t)) return null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Perencana konstruktif (v1.2) — cepat & terarah, sebelum A* blok-geser
    //
    // Ulangi: (1) coba jalan kaki saja ke goal; (2) bila putus, cari
    // jalur geometris (Dijkstra longgar — pijakan diabaikan, sel harus
    // kosong + kepala bebas, biaya +5 untuk sel tanpa pijakan) dan isi
    // sel pertama yang putus pijakannya dengan satu blok drag. Robot
    // berjalan sepanjang jalur geometris selagi bisa dipijak, lalu
    // re-planning dari posisi sebenarnya — jadi penyimpangan (mis.
    // jatuh mendarat di sel lain) ditangani, bukan diasumsikan.
    // ------------------------------------------------------------------

    /** Wujudkan state blok-geser menjadi grid 4096 byte (untuk A* jalan kaki). */
    private static byte[] materializeGrid(byte[] grid, List<Movable> movs, int[] pos,
                                          boolean[] trackMask) {
        byte[] g = grid.clone();
        for (int k = 0; k < movs.size(); k++) {
            int s = movs.get(k).startCell;
            if (pos[k] == s) continue;
            boolean occupied = false;
            for (int j = 0; j < movs.size(); j++) if (pos[j] == s) occupied = true;
            if (!occupied) g[s] = (byte) (trackMask[s] ? RAIL : NONE);
        }
        for (int k = 0; k < movs.size(); k++) {
            g[pos[k]] = (byte) movs.get(k).type;
        }
        return g;
    }

    /** Jalur geometris (Dijkstra longgar) dari robot ke goal. */
    private static List<Cell> geometricPath(byte[] grid, List<Movable> movs, int[] pos,
                                            boolean[] trackMask, Cell start,
                                            boolean[] isGoal) {
        final int[] dist = new int[LevelFormat.GRID_SIZE];
        final int[] parent = new int[LevelFormat.GRID_SIZE];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(parent, -1);
        int startIdx = start.index();
        dist[startIdx] = 0;
        PriorityQueue<int[]> pq = new PriorityQueue<int[]>(64, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                if (a[0] != b[0]) return a[0] - b[0];
                return a[1] - b[1];
            }
        });
        pq.add(new int[]{0, startIdx});
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int goalIdx = -1;
        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int d = cur[0], ci = cur[1];
            if (d != dist[ci]) continue;
            if (isGoal[ci]) { goalIdx = ci; break; }
            int cx = LevelFormat.xOf(ci), cy = LevelFormat.yOf(ci), cz = LevelFormat.zOf(ci);
            for (int[] dir : dirs) {
                int nx = cx + dir[0], nz = cz + dir[1];
                if (nx < 0 || nx >= LevelFormat.DIM || nz < 0 || nz >= LevelFormat.DIM) continue;
                for (int ny = cy; ny <= cy + 1 && ny < LevelFormat.DIM; ny++) {
                    relaxGeo(grid, movs, pos, trackMask, dist, parent, pq, ci, d, nx, ny, nz);
                }
                for (int ny = cy - 1; ny >= 0; ny--) {
                    int idx = LevelFormat.index(nx, ny, nz);
                    if (!passable(typeAt(grid, movs, pos, trackMask, idx))) break;
                    if (ny + 1 < LevelFormat.DIM
                            && !passable(typeAt(grid, movs, pos, trackMask,
                                    LevelFormat.index(nx, ny + 1, nz)))) break;
                    relaxGeo(grid, movs, pos, trackMask, dist, parent, pq, ci, d, nx, ny, nz);
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

    /** Relaksasi satu sel jalur geometris (biaya +5 bila butuh blok drag). */
    private static void relaxGeo(byte[] grid, List<Movable> movs, int[] pos,
                                 boolean[] trackMask, int[] dist, int[] parent,
                                 PriorityQueue<int[]> pq, int curIdx, int d,
                                 int nx, int ny, int nz) {
        int ni = LevelFormat.index(nx, ny, nz);
        if (!passable(typeAt(grid, movs, pos, trackMask, ni))) return;
        if (ny + 1 < LevelFormat.DIM
                && !passable(typeAt(grid, movs, pos, trackMask,
                        LevelFormat.index(nx, ny + 1, nz)))) return;
        int below = (ny == 0) ? 2 : typeAt(grid, movs, pos, trackMask,
                LevelFormat.index(nx, ny - 1, nz));
        int stepCost;
        if (solidGround(below)) {
            stepCost = 1; // sudah bisa dipijak, tanpa drag
        } else if (!passable(below)) {
            // Sel bawah adalah blok padat yang BUKAN pijakan valid
            // (mis. batu polos tipe 1) — sel ini TIDAK PERNAH bisa jadi
            // pijakan robot (blok bawah tidak bisa diganti). Buang.
            return;
        } else {
            // Sel bawah kosong: bisa diisi blok drag, asal ada tumpuan
            // di bawahnya lagi (atau lantai y=0).
            if (ny - 1 > 0) {
                int belowBelow = typeAt(grid, movs, pos, trackMask,
                        LevelFormat.index(nx, ny - 2, nz));
                if (passable(belowBelow)) return; // blok akan jatuh -> buntu
            }
            stepCost = 6; // 1 jalan + 5 biaya satu blok drag
        }
        int nd = d + stepCost;
        if (nd < dist[ni]) {
            dist[ni] = nd;
            parent[ni] = curIdx;
            pq.add(new int[]{nd, ni});
        }
    }

    /** Perencana konstruktif: isi pijakan yang putus dengan blok drag. */
    private static Result constructiveDragSolve(byte[] grid, byte[] rot, Cell start,
                                                boolean[] isGoal, List<Cell> goalCells,
                                                Cell firstGoal, String dynamics) {
        List<Movable> movs = new ArrayList<Movable>();
        boolean[] trackMask = new boolean[LevelFormat.GRID_SIZE];
        for (int i = 0; i < LevelFormat.GRID_SIZE; i++) {
            int t = grid[i] & 0xFF;
            if (t == SLIDER) {
                int r = (rot != null) ? (rot[i] & 0xFF) : 0;
                int axis = sliderAxis(r);
                boolean[] track = sliderTrack(grid, i, axis);
                for (int j = 0; j < LevelFormat.GRID_SIZE; j++) {
                    if (track[j]) trackMask[j] = true;
                }
                movs.add(new Movable(SLIDER, axis, track, i));
            } else if (t == DRAG_COPPER || t == CYLINDER_DRAGGABLE) {
                movs.add(new Movable(t, -1, null, i));
            }
        }
        final int n = movs.size();
        int[] pos = new int[n];
        for (int k = 0; k < n; k++) pos[k] = movs.get(k).startCell;
        List<Step> actions = new ArrayList<Step>();
        List<Cell> path = new ArrayList<Cell>();
        Cell robot = start;
        path.add(robot);

        for (int iter = 0; iter < 64; iter++) {
            // (1) Coba jalan kaki saja dari posisi sekarang ke goal.
            byte[] g = materializeGrid(grid, movs, pos, trackMask);
            Result walk = astar(g, robot, isGoal, goalCells, true, dynamics);
            if (walk.status == Status.OK) {
                for (int i = 1; i < walk.path.size(); i++) {
                    actions.add(Step.walk(walk.path.get(i)));
                    path.add(walk.path.get(i));
                }
                Cell goal = walk.path.get(walk.path.size() - 1);
                int drags = 0;
                for (Step s : actions) if (s.kind != Step.Kind.WALK) drags++;
                return new Result(Status.OK, path, actions,
                        "jalan ditemukan (" + (path.size() - 1) + " langkah jalan + "
                        + drags + " geser blok)",
                        start, goal, false, dynamics);
            }
            // (2) Jalur geometris (longgar) dari robot ke goal.
            List<Cell> geo = geometricPath(grid, movs, pos, trackMask, robot, isGoal);
            if (geo == null || geo.size() < 2) {
                return new Result(Status.UNSOLVABLE, null,
                        "tidak ada jalur geometris ke finish",
                        start, firstGoal, false, dynamics);
            }
            // (3) Berjalan sepanjang jalur geometris selagi bisa dipijak.
            int gi = 1;
            while (gi < geo.size()
                    && walkableState(grid, movs, pos, trackMask,
                            geo.get(gi).x, geo.get(gi).y, geo.get(gi).z)) {
                actions.add(Step.walk(geo.get(gi)));
                path.add(geo.get(gi));
                robot = geo.get(gi);
                gi++;
            }
            if (isGoal[robot.index()]) {
                int drags = 0;
                for (Step s : actions) if (s.kind != Step.Kind.WALK) drags++;
                return new Result(Status.OK, path, actions,
                        "jalan ditemukan (" + (path.size() - 1) + " langkah jalan + "
                        + drags + " geser blok)",
                        start, robot, false, dynamics);
            }
            if (gi >= geo.size()) {
                return new Result(Status.UNSOLVABLE, null,
                        "jalur geometris habis tapi robot belum sampai finish",
                        start, firstGoal, false, dynamics);
            }
            Cell gap = geo.get(gi);
            // Sel gap harus kosong + kepala bebas.
            if (!passable(typeAt(grid, movs, pos, trackMask, gap.index()))) {
                return new Result(Status.UNSOLVABLE, null,
                        "jalur terhalang blok padat di " + gap,
                        start, firstGoal, false, dynamics);
            }
            if (gap.y + 1 < LevelFormat.DIM
                    && !passable(typeAt(grid, movs, pos, trackMask,
                            LevelFormat.index(gap.x, gap.y + 1, gap.z)))) {
                return new Result(Status.UNSOLVABLE, null,
                        "jalur terhalang blok di atas " + gap,
                        start, firstGoal, false, dynamics);
            }
            if (gap.y == 0) {
                return new Result(Status.UNSOLVABLE, null,
                        "sel gap di lantai tapi tetap tidak bisa dipijak: " + gap,
                        start, firstGoal, false, dynamics);
            }
            // (4) Isi pijakan di bawah sel gap dengan satu blok drag.
            Cell target = new Cell(gap.x, gap.y - 1, gap.z);
            int ti = target.index();
            if (isGoal[ti]) {
                return new Result(Status.UNSOLVABLE, null,
                        "pijakan yang dibutuhkan menutupi finish",
                        start, firstGoal, false, dynamics);
            }
            if (!passable(typeAt(grid, movs, pos, trackMask, ti))) {
                return new Result(Status.UNSOLVABLE, null,
                        "sel pijakan " + target + " sudah terisi",
                        start, firstGoal, false, dynamics);
            }
            int belowT = (target.y == 0) ? 2 : typeAt(grid, movs, pos, trackMask,
                    LevelFormat.index(target.x, target.y - 1, target.z));
            if (passable(belowT)) {
                return new Result(Status.UNSOLVABLE, null,
                        "pijakan " + target + " tidak punya tumpuan (blok akan jatuh)",
                        start, firstGoal, false, dynamics);
            }
            // Pilih blok drag terdekat (bukan yang sedang dipijak robot).
            int bestK = -1, bestDist = Integer.MAX_VALUE;
            for (int k = 0; k < n; k++) {
                Movable m = movs.get(k);
                if (m.type != DRAG_COPPER && m.type != CYLINDER_DRAGGABLE) continue;
                int sc = pos[k];
                if (robot.index() == LevelFormat.index(
                        LevelFormat.xOf(sc), LevelFormat.yOf(sc) + 1, LevelFormat.zOf(sc))) {
                    continue;
                }
                int d = Math.abs(LevelFormat.xOf(sc) - target.x)
                        + Math.abs(LevelFormat.yOf(sc) - target.y)
                        + Math.abs(LevelFormat.zOf(sc) - target.z);
                if (d < bestDist) { bestDist = d; bestK = k; }
            }
            if (bestK < 0) {
                return new Result(Status.UNSOLVABLE, null,
                        "tidak ada blok drag yang bisa digeser ke " + target,
                        start, firstGoal, false, dynamics);
            }
            int from = pos[bestK];
            pos[bestK] = ti;
            actions.add(Step.drag(
                    new Cell(LevelFormat.xOf(from), LevelFormat.yOf(from), LevelFormat.zOf(from)),
                    target, movs.get(bestK).type));
        }
        return new Result(Status.UNSOLVABLE, null,
                "perencana konstruktif kehabisan iterasi (64)",
                start, firstGoal, false, dynamics);
    }

    /**
     * A* di atas state (robot + posisi blok geser). Mengembalikan rencana
     * aksi lengkap (jalan + geser) bila ada, atau UNSOLVABLE/TIMEOUT.
     * Zapper diperlakukan seperti fase 1 jalan kaki: sel di dekat tesla
     * diblokir (timing zapper belum dimodelkan — fail-closed).
     */
    private static Result dragSolve(byte[] grid, byte[] rot, Cell start,
                                    boolean[] isGoal, List<Cell> goalCells,
                                    Cell firstGoal, String dynamics) {
        final long deadline = System.currentTimeMillis() + MAX_MS;
        List<Movable> movs = new ArrayList<Movable>();
        boolean[] trackMask = new boolean[LevelFormat.GRID_SIZE];
        for (int i = 0; i < LevelFormat.GRID_SIZE; i++) {
            int t = grid[i] & 0xFF;
            if (t == SLIDER) {
                int r = (rot != null) ? (rot[i] & 0xFF) : 0;
                int axis = sliderAxis(r);
                boolean[] track = sliderTrack(grid, i, axis);
                for (int j = 0; j < LevelFormat.GRID_SIZE; j++) {
                    if (track[j]) trackMask[j] = true;
                }
                movs.add(new Movable(SLIDER, axis, track, i));
            } else if (t == DRAG_COPPER || t == CYLINDER_DRAGGABLE) {
                movs.add(new Movable(t, -1, null, i));
            }
        }
        final int n = movs.size();
        if (n == 0) {
            return new Result(Status.UNSOLVABLE, null, "tidak ada blok geser",
                    start, firstGoal, false, dynamics);
        }
        int[] startPos = new int[n];
        for (int k = 0; k < n; k++) startPos[k] = movs.get(k).startCell;

        final List<DState> nodes = new ArrayList<DState>();
        final List<Integer> parents = new ArrayList<Integer>();
        final List<Step> via = new ArrayList<Step>();
        final List<Integer> gList = new ArrayList<Integer>();
        final java.util.HashMap<String, Integer> best =
                new java.util.HashMap<String, Integer>();
        DState s0 = new DState(start.index(), startPos);
        nodes.add(s0);
        parents.add(-1);
        via.add(null);
        gList.add(0);
        best.put(s0.key(), 0);

        // Antrian prioritas atas indeks node: f = g + h (Manhattan ke goal).
        PriorityQueue<Integer> open = new PriorityQueue<Integer>(64,
                new Comparator<Integer>() {
                    @Override public int compare(Integer a, Integer b) {
                        int fa = gList.get(a) + heuristicNode(nodes.get(a), goalCells);
                        int fb = gList.get(b) + heuristicNode(nodes.get(b), goalCells);
                        if (fa != fb) return fa - fb;
                        return a - b;
                    }
                });
        open.add(0);

        int expanded = 0;
        int goalNode = -1;
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            int cur = open.poll();
            DState st = nodes.get(cur);
            int g = gList.get(cur);
            Integer bg = best.get(st.key());
            if (bg != null && bg.intValue() < g) continue; // entri basi
            if (++expanded > MAX_NODES
                    || ((expanded & 1023) == 0 && System.currentTimeMillis() > deadline)) {
                return new Result(Status.TIMEOUT, null,
                        "batas A* blok-geser tercapai (" + MAX_NODES + " node)",
                        start, firstGoal, false, dynamics);
            }
            if (isGoal[st.robot]) { goalNode = cur; break; }
            int cx = LevelFormat.xOf(st.robot);
            int cy = LevelFormat.yOf(st.robot);
            int cz = LevelFormat.zOf(st.robot);

            // --- (1) Aksi jalan (datar / naik 1 / jatuh) ---
            for (int[] d : dirs) {
                int nx = cx + d[0], nz = cz + d[1];
                if (nx < 0 || nx >= LevelFormat.DIM || nz < 0 || nz >= LevelFormat.DIM) {
                    continue;
                }
                // datar
                addWalkSucc(grid, movs, st, trackMask, goalCells, nodes, parents, via,
                        gList, best, open, cur, g, nx, cy, nz);
                // naik 1
                if (cy + 1 < LevelFormat.DIM) {
                    addWalkSucc(grid, movs, st, trackMask, goalCells, nodes, parents, via,
                            gList, best, open, cur, g, nx, cy + 1, nz);
                }
                // jatuh
                int tHere = typeAt(grid, movs, st.pos, trackMask,
                        LevelFormat.index(nx, cy, nz));
                int tAbove = (cy + 1 < LevelFormat.DIM)
                        ? typeAt(grid, movs, st.pos, trackMask,
                                LevelFormat.index(nx, cy + 1, nz)) : NONE;
                if (passable(tHere) && passable(tAbove)) {
                    Cell land = fallLandState(grid, movs, st.pos, trackMask, nx, cy, nz);
                    if (land != null && land.index() != st.robot) {
                        addWalkSucc(grid, movs, st, trackMask, goalCells, nodes, parents,
                                via, gList, best, open, cur, g, land.x, land.y, land.z);
                    }
                }
            }

            // Sel yang bisa dijangkau jalan kaki dari robot (state ini)
            // + sel-sel yang berguna sebagai pijakan BARU: sel di atas
            // blok hasil geser harus bersebelahan dengan daerah yang
            // sudah terjangkau — kalau tidak, geseran itu tidak
            // memperluas jangkauan robot (prune percabangan sia-sia).
            boolean[] usefulAbove = usefulAboveCells(grid, movs, st.pos, trackMask, st.robot);

            // --- (2) Aksi geser slider (+-1 sepanjang rel) ---
            for (int k = 0; k < n; k++) {
                Movable m = movs.get(k);
                if (m.type != SLIDER) continue;
                int sc = st.pos[k];
                int sx = LevelFormat.xOf(sc), sy = LevelFormat.yOf(sc), sz = LevelFormat.zOf(sc);
                // Slider jauh dari robot tidak usah digerakkan dulu —
                // robot berjalan mendekat dulu, baru slider itu relevan.
                if (Math.max(Math.abs(sx - cx),
                        Math.max(Math.abs(sy - cy), Math.abs(sz - cz))) > 3) continue;
                int dx = m.axis == AXIS_X ? 1 : 0;
                int dy = m.axis == AXIS_Y ? 1 : 0;
                int dz = m.axis == AXIS_Z ? 1 : 0;
                for (int dir = -1; dir <= 1; dir += 2) {
                    int nx = sx + dx * dir, ny = sy + dy * dir, nz = sz + dz * dir;
                    if (nx < 0 || nx >= LevelFormat.DIM || ny < 0 || ny >= LevelFormat.DIM
                            || nz < 0 || nz >= LevelFormat.DIM) continue;
                    int ni = LevelFormat.index(nx, ny, nz);
                    if (!m.track[ni]) continue; // bukan sel rel
                    // Sel tujuan harus rel kosong saat ini.
                    if (typeAt(grid, movs, st.pos, trackMask, ni) != RAIL) continue;
                    int[] np = st.pos.clone();
                    np[k] = ni;
                    int nRobot = st.robot;
                    // Robot ikut naik bila sedang berdiri di atas slider ini.
                    if (st.robot == LevelFormat.index(sx, sy + 1, sz)) {
                        if (ny + 1 >= LevelFormat.DIM) continue;
                        int nr = LevelFormat.index(nx, ny + 1, nz);
                        if (!walkableState(grid, movs, np, trackMask, nx, ny + 1, nz)) {
                            continue;
                        }
                        nRobot = nr;
                    }
                    int cost = 3;
                    addState(nodes, parents, via, gList, best, open, cur, g + cost,
                            new DState(nRobot, np),
                            Step.slide(new Cell(sx, sy, sz), new Cell(nx, ny, nz)));
                }
            }

            // --- (3) Aksi drag blok ke sel kosong dekat robot (+gravitasi) ---
            for (int k = 0; k < n; k++) {
                Movable m = movs.get(k);
                if (m.type != DRAG_COPPER && m.type != CYLINDER_DRAGGABLE) continue;
                int sc = st.pos[k];
                int sx = LevelFormat.xOf(sc), sy = LevelFormat.yOf(sc), sz = LevelFormat.zOf(sc);
                // Jangan geser blok yang sedang dipijak robot.
                if (st.robot == LevelFormat.index(sx, sy + 1, sz)) continue;
                // Target: sel kosong di sekitar robot (Chebyshev <= 1).
                for (int tx = cx - 1; tx <= cx + 1; tx++) {
                    for (int ty = cy - 1; ty <= cy + 1; ty++) {
                        for (int tz = cz - 1; tz <= cz + 1; tz++) {
                            if (tx < 0 || tx >= LevelFormat.DIM || ty < 0 || ty >= LevelFormat.DIM
                                    || tz < 0 || tz >= LevelFormat.DIM) continue;
                            int ti = LevelFormat.index(tx, ty, tz);
                            if (ti == sc) continue;
                            if (isGoal[ti]) continue; // jangan tutupi finish
                            // Jangan jatuhkan ke kolom robot.
                            if (tx == cx && tz == cz) continue;
                            // Selama blok di udara, sel asalnya kosong —
                            // abaikan blok yang sedang digeser saat cek
                            // tabrakan & gravitasi (kalau tidak, blok
                            // "menabrak" posisinya sendiri).
                            int[] posFree = st.pos.clone();
                            posFree[k] = -1;
                            if (!passable(typeAt(grid, movs, posFree, trackMask, ti))) continue;
                            // Gravitasi: blok jatuh sampai bertumpu.
                            int settleY = settleDrag(grid, movs, posFree, trackMask, tx, ty, tz);
                            if (settleY < 0) continue;
                            int si = LevelFormat.index(tx, settleY, tz);
                            if (si == sc) continue;
                            if (isGoal[si]) continue;
                            int[] np = st.pos.clone();
                            np[k] = si;
                            // Prune: geser hanya berguna bila sel DI ATAS
                            // blok hasil geser bisa dipijak DAN bersebelahan
                            // dengan daerah yang sudah terjangkau robot
                            // (jembatan / pijakan baru). Kalau tidak, buang
                            // percabangan sia-sia.
                            if (settleY + 1 >= LevelFormat.DIM) continue;
                            int ai = LevelFormat.index(tx, settleY + 1, tz);
                            if (!usefulAbove[ai]) continue;
                            if (!walkableState(grid, movs, np, trackMask,
                                    tx, settleY + 1, tz)) {
                                continue;
                            }
                            int dist = Math.abs(sx - tx) + Math.abs(sy - settleY)
                                    + Math.abs(sz - tz);
                            int cost = 4 + dist / 8;
                            addState(nodes, parents, via, gList, best, open, cur, g + cost,
                                    new DState(st.robot, np),
                                    Step.drag(new Cell(sx, sy, sz),
                                            new Cell(tx, settleY, tz), m.type));
                        }
                    }
                }
            }
        }

        if (goalNode < 0) {
            return new Result(Status.UNSOLVABLE, null,
                    "tidak ada jalan walau dengan geser blok — level ini butuh "
                    + "mekanik lain (motor / bola / kotak dorong / logika)",
                    start, firstGoal, false, dynamics);
        }
        // Rekonstruksi rencana aksi + jalur kaki robot.
        List<Step> actions = new ArrayList<Step>();
        List<Cell> path = new ArrayList<Cell>();
        int node = goalNode;
        java.util.ArrayDeque<Integer> chain = new java.util.ArrayDeque<Integer>();
        while (node != -1) { chain.addFirst(node); node = parents.get(node); }
        int prevRobot = -1;
        for (int nd : chain) {
            DState st = nodes.get(nd);
            if (st.robot != prevRobot) {
                path.add(new Cell(LevelFormat.xOf(st.robot), LevelFormat.yOf(st.robot),
                        LevelFormat.zOf(st.robot)));
                prevRobot = st.robot;
            }
            Step s = via.get(nd);
            if (s != null) actions.add(s);
        }
        int drags = 0;
        for (Step s : actions) if (s.kind != Step.Kind.WALK) drags++;
        Cell goal = path.get(path.size() - 1);
        return new Result(Status.OK, path, actions,
                "jalan ditemukan (" + (path.size() - 1) + " langkah jalan + "
                + drags + " geser blok)",
                start, goal, false, dynamics);
    }

    /** Heuristik node blok-geser: Manhattan robot -> goal terdekat. */
    private static int heuristicNode(DState st, List<Cell> goalCells) {
        int cx = LevelFormat.xOf(st.robot);
        int cy = LevelFormat.yOf(st.robot);
        int cz = LevelFormat.zOf(st.robot);
        int best = Integer.MAX_VALUE;
        for (Cell g : goalCells) {
            int d = Math.abs(g.x - cx) + Math.abs(g.y - cy) + Math.abs(g.z - cz);
            if (d < best) best = d;
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    /** Tambah suksesor jalan (bila walkable + tidak dekat zapper). */
    private static void addWalkSucc(byte[] grid, List<Movable> movs, DState st,
                                    boolean[] trackMask, List<Cell> goalCells,
                                    List<DState> nodes, List<Integer> parents,
                                    List<Step> via, List<Integer> gList,
                                    java.util.HashMap<String, Integer> best,
                                    PriorityQueue<Integer> open,
                                    int cur, int g, int nx, int ny, int nz) {
        if (!walkableState(grid, movs, st.pos, trackMask, nx, ny, nz)) return;
        if (nearTesla(grid, nx, ny, nz)) return; // fail-closed: timing zapper
        int ni = LevelFormat.index(nx, ny, nz);
        addState(nodes, parents, via, gList, best, open, cur, g + 1,
                new DState(ni, st.pos), Step.walk(new Cell(nx, ny, nz)));
    }

    /** Daftarkan state baru (bila g lebih baik dari yang tercatat). */
    private static void addState(List<DState> nodes, List<Integer> parents,
                                 List<Step> via, List<Integer> gList,
                                 java.util.HashMap<String, Integer> best,
                                 PriorityQueue<Integer> open,
                                 int cur, int ng, DState ns, Step step) {
        String key = ns.key();
        Integer old = best.get(key);
        if (old != null && old.intValue() <= ng) return;
        best.put(key, ng);
        int idx = nodes.size();
        nodes.add(ns);
        parents.add(cur);
        via.add(step);
        gList.add(ng);
        open.add(idx);
    }

    /**
     * Sel-sel yang berguna sebagai pijakan BARU hasil geser blok: untuk
     * tiap sel kaki yang terjangkau jalan kaki dari robot, tandai 4
     * tetangga horizontalnya pada tinggi yang sama, 1 lebih tinggi
     * (step-up), dan semua tinggi di bawahnya (jatuh). Geser blok yang
     * pijakan barunya tidak ada di himpunan ini tidak memperluas
     * jangkauan robot — dibuang (prune).
     */
    private static boolean[] usefulAboveCells(byte[] grid, List<Movable> movs, int[] pos,
                                              boolean[] trackMask, int robotIdx) {
        boolean[] reach = walkReachable(grid, movs, pos, trackMask, robotIdx);
        boolean[] useful = new boolean[LevelFormat.GRID_SIZE];
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int ri = 0; ri < LevelFormat.GRID_SIZE; ri++) {
            if (!reach[ri]) continue;
            int rx = LevelFormat.xOf(ri), ry = LevelFormat.yOf(ri), rz = LevelFormat.zOf(ri);
            for (int[] d : dirs) {
                int nx = rx + d[0], nz = rz + d[1];
                if (nx < 0 || nx >= LevelFormat.DIM || nz < 0 || nz >= LevelFormat.DIM) continue;
                if (ry + 1 < LevelFormat.DIM) {
                    useful[LevelFormat.index(nx, ry + 1, nz)] = true;
                }
                useful[LevelFormat.index(nx, ry, nz)] = true;
                for (int yy = ry - 1; yy >= 0; yy--) {
                    useful[LevelFormat.index(nx, yy, nz)] = true;
                }
            }
        }
        return useful;
    }

    /** Sel-sel kaki yang terjangkau jalan kaki saja dari robot (state ini). */
    private static boolean[] walkReachable(byte[] grid, List<Movable> movs, int[] pos,
                                           boolean[] trackMask, int robotIdx) {
        boolean[] seen = new boolean[LevelFormat.GRID_SIZE];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<Integer>();
        q.add(robotIdx);
        seen[robotIdx] = true;
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int cur = q.poll();
            int cx = LevelFormat.xOf(cur), cy = LevelFormat.yOf(cur), cz = LevelFormat.zOf(cur);
            for (int[] d : dirs) {
                int nx = cx + d[0], nz = cz + d[1];
                if (nx < 0 || nx >= LevelFormat.DIM || nz < 0 || nz >= LevelFormat.DIM) continue;
                addReach(grid, movs, pos, trackMask, seen, q, nx, cy, nz);
                if (cy + 1 < LevelFormat.DIM) {
                    addReach(grid, movs, pos, trackMask, seen, q, nx, cy + 1, nz);
                }
                int tHere = typeAt(grid, movs, pos, trackMask,
                        LevelFormat.index(nx, cy, nz));
                int tAbove = (cy + 1 < LevelFormat.DIM)
                        ? typeAt(grid, movs, pos, trackMask,
                                LevelFormat.index(nx, cy + 1, nz)) : NONE;
                if (passable(tHere) && passable(tAbove)) {
                    Cell land = fallLandState(grid, movs, pos, trackMask, nx, cy, nz);
                    if (land != null && !seen[land.index()]) {
                        seen[land.index()] = true;
                        q.add(land.index());
                    }
                }
            }
        }
        return seen;
    }

    private static void addReach(byte[] grid, List<Movable> movs, int[] pos,
                                 boolean[] trackMask, boolean[] seen,
                                 java.util.ArrayDeque<Integer> q, int nx, int ny, int nz) {
        if (!walkableState(grid, movs, pos, trackMask, nx, ny, nz)) return;
        if (nearTesla(grid, nx, ny, nz)) return;
        int ni = LevelFormat.index(nx, ny, nz);
        if (!seen[ni]) { seen[ni] = true; q.add(ni); }
    }

    /**
     * Gravitasi blok drag: dari sel target (tx,ty,tz), blok jatuh sampai
     * sel kosong pertama yang bertumpu pada blok padat / lantai (y=0).
     * Mengembalikan y tempat blok berhenti, atau -1 bila jatuh keluar
     * dunia (tidak ada tumpuan).
     */
    private static int settleDrag(byte[] grid, List<Movable> movs, int[] pos,
                                  boolean[] trackMask, int tx, int ty, int tz) {
        for (int yy = ty; yy >= 0; yy--) {
            int idx = LevelFormat.index(tx, yy, tz);
            if (!passable(typeAt(grid, movs, pos, trackMask, idx))) {
                return -1; // terhalang — tidak bisa lewat sini
            }
            if (yy == 0) return 0;
            int belowIdx = LevelFormat.index(tx, yy - 1, tz);
            if (!passable(typeAt(grid, movs, pos, trackMask, belowIdx))) {
                return yy; // bertumpu pada blok di bawah
            }
        }
        return -1;
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
