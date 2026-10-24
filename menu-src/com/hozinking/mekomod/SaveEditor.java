package com.hozinking.mekomod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * SaveEditor — pembaca/penulis save profile Mekorama.
 *
 * Format save (terbukti dari REA assets/db):
 *   file = stream zlib berisi satu objek JSON kompak:
 *   {"v":1,"fl":-1,...,"lvs":[{"n":"x_crash_course.bin","t":0,"s":1,"sb":0}, ...]}
 *   - "lvs" = progres per level: n=nama file level, t=tipe, s=bintang, sb=best steps.
 *   - Level Story terbuka bila TOTAL bintang (field "s" milik level story)
 *     mencapai ambang "su" di assets/level_infos.json (0..150, tertinggi 150).
 *
 * Kelas ini MURNI Java (tanpa import Android) supaya logika yang sama persis
 * bisa diuji di host/CI (lihat test/SaveEditorTest.java).
 *
 * Transformasi unlock yang diterapkan (deterministik):
 *   - Setiap level Story (daftar nama dari level_infos.json) WAJIB punya
 *     entri di "lvs"; yang belum ada ditambahkan {n, t:0, s:2, sb:0}.
 *   - Field "s" setiap entri Story diset 2 (maksimum per level menurut pola
 *     data game: ambang "su" naik ~2 per level; 80 level x 2 = 160 >= 150).
 *   - Entri non-Story (Featured, level user) dan semua field lain TIDAK diubah.
 *   - Flag premium/donasi (ad/up/pwyw) TIDAK disentuh — bukan fitur mod ini.
 */
public final class SaveEditor {
    private SaveEditor() {}

    /** Bintang per level story setelah unlock. */
    public static final long STARS_PER_LEVEL = 2L;

    // ------------------------------------------------------------------
    // zlib <-> JSON
    // ------------------------------------------------------------------

    /** Dekode file save (zlib -> JSON) menjadi pohon objek. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> decodeSave(byte[] zlibBytes) throws IOException {
        String json = inflateToString(zlibBytes);
        Object v = parseJson(json);
        if (!(v instanceof Map)) {
            throw new IOException("save bukan objek JSON");
        }
        return (Map<String, Object>) v;
    }

    /** Enkode pohon objek menjadi file save (JSON kompak -> zlib). */
    public static byte[] encodeSave(Map<String, Object> root) throws IOException {
        String json = toJson(root);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DeflaterOutputStream def = new DeflaterOutputStream(raw);
        def.write(json.getBytes("UTF-8"));
        def.close();
        return raw.toByteArray();
    }

    /** Coba dekode byte sebagai save Mekorama; true bila valid & punya "lvs". */
    public static boolean looksLikeSave(byte[] bytes) {
        try {
            Map<String, Object> root = decodeSave(bytes);
            return root.get("lvs") instanceof List;
        } catch (Exception e) {
            return false;
        }
    }

    private static String inflateToString(byte[] zlibBytes) throws IOException {
        InflaterInputStream inf = new InflaterInputStream(new ByteArrayInputStream(zlibBytes));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = inf.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        inf.close();
        return out.toString("UTF-8");
    }

    // ------------------------------------------------------------------
    // Logika unlock
    // ------------------------------------------------------------------

    /** Daftar nama level story dari pohon level_infos.json ("story")[]."n". */
    @SuppressWarnings("unchecked")
    public static List<String> storyNamesFromLevelInfos(Map<String, Object> levelInfos) {
        List<String> names = new ArrayList<String>();
        Object story = levelInfos.get("story");
        if (story instanceof List) {
            for (Object o : (List<Object>) story) {
                if (o instanceof Map) {
                    Object n = ((Map<String, Object>) o).get("n");
                    if (n instanceof String) names.add((String) n);
                }
            }
        }
        return names;
    }

    /** Total bintang dari entri-entri story di "lvs". */
    @SuppressWarnings("unchecked")
    public static long totalStars(Map<String, Object> root, List<String> storyNames) {
        java.util.Set<String> story = new java.util.HashSet<String>(storyNames);
        long total = 0;
        Object lvs = root.get("lvs");
        if (lvs instanceof List) {
            for (Object o : (List<Object>) lvs) {
                if (o instanceof Map) {
                    Map<String, Object> e = (Map<String, Object>) o;
                    Object n = e.get("n");
                    if (n instanceof String && story.contains(n)) {
                        Object s = e.get("s");
                        if (s instanceof Number) total += ((Number) s).longValue();
                    }
                }
            }
        }
        return total;
    }

    /**
     * Terapkan unlock: semua level story dapat entri dengan s=STARS_PER_LEVEL.
     * @return total bintang story setelah transformasi.
     */
    @SuppressWarnings("unchecked")
    public static long unlockAll(Map<String, Object> root, List<String> storyNames) {
        Object lvsObj = root.get("lvs");
        if (!(lvsObj instanceof List)) {
            throw new IllegalArgumentException("save tidak punya array \"lvs\"");
        }
        List<Object> lvs = (List<Object>) lvsObj;
        Map<String, Map<String, Object>> byName = new LinkedHashMap<String, Map<String, Object>>();
        for (Object o : lvs) {
            if (o instanceof Map) {
                Map<String, Object> e = (Map<String, Object>) o;
                Object n = e.get("n");
                if (n instanceof String) byName.put((String) n, e);
            }
        }
        for (String name : storyNames) {
            Map<String, Object> e = byName.get(name);
            if (e == null) {
                e = new LinkedHashMap<String, Object>();
                e.put("n", name);
                e.put("t", 0L);
                e.put("s", STARS_PER_LEVEL);
                e.put("sb", 0L);
                lvs.add(e);
                byName.put(name, e);
            } else {
                e.put("s", STARS_PER_LEVEL);
            }
        }
        return totalStars(root, storyNames);
    }

    // ------------------------------------------------------------------
    // JSON minimal (parser + serializer) — cukup untuk struktur save game:
    // objek, array, string, angka, boolean, null. Urutan key dipertahankan
    // (LinkedHashMap) agar field yang tidak disentuh tetap sama persis.
    // ------------------------------------------------------------------

    public static Object parseJson(String s) {
        int[] p = {0};
        Object v = parseValue(s, p);
        skipWs(s, p);
        if (p[0] != s.length()) {
            throw new IllegalArgumentException("sisa karakter JSON di posisi " + p[0]);
        }
        return v;
    }

    private static void skipWs(String s, int[] p) {
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p[0]++;
            else break;
        }
    }

    private static Object parseValue(String s, int[] p) {
        skipWs(s, p);
        if (p[0] >= s.length()) throw new IllegalArgumentException("JSON terpotong");
        char c = s.charAt(p[0]);
        if (c == '{') return parseObject(s, p);
        if (c == '[') return parseArray(s, p);
        if (c == '"') return parseString(s, p);
        if (c == 't') { expect(s, p, "true"); return Boolean.TRUE; }
        if (c == 'f') { expect(s, p, "false"); return Boolean.FALSE; }
        if (c == 'n') { expect(s, p, "null"); return null; }
        return parseNumber(s, p);
    }

    private static void expect(String s, int[] p, String lit) {
        if (!s.startsWith(lit, p[0])) {
            throw new IllegalArgumentException("token JSON tidak dikenal di posisi " + p[0]);
        }
        p[0] += lit.length();
    }

    private static Map<String, Object> parseObject(String s, int[] p) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        p[0]++; // {
        skipWs(s, p);
        if (p[0] < s.length() && s.charAt(p[0]) == '}') { p[0]++; return map; }
        while (true) {
            skipWs(s, p);
            String key = parseString(s, p);
            skipWs(s, p);
            if (p[0] >= s.length() || s.charAt(p[0]) != ':') {
                throw new IllegalArgumentException("mengharapkan ':' di posisi " + p[0]);
            }
            p[0]++;
            map.put(key, parseValue(s, p));
            skipWs(s, p);
            if (p[0] >= s.length()) throw new IllegalArgumentException("objek terpotong");
            char c = s.charAt(p[0]++);
            if (c == '}') break;
            if (c != ',') throw new IllegalArgumentException("mengharapkan ',' di posisi " + (p[0] - 1));
        }
        return map;
    }

    private static List<Object> parseArray(String s, int[] p) {
        List<Object> list = new ArrayList<Object>();
        p[0]++; // [
        skipWs(s, p);
        if (p[0] < s.length() && s.charAt(p[0]) == ']') { p[0]++; return list; }
        while (true) {
            list.add(parseValue(s, p));
            skipWs(s, p);
            if (p[0] >= s.length()) throw new IllegalArgumentException("array terpotong");
            char c = s.charAt(p[0]++);
            if (c == ']') break;
            if (c != ',') throw new IllegalArgumentException("mengharapkan ',' di posisi " + (p[0] - 1));
        }
        return list;
    }

    private static String parseString(String s, int[] p) {
        if (s.charAt(p[0]) != '"') {
            throw new IllegalArgumentException("mengharapkan string di posisi " + p[0]);
        }
        p[0]++;
        StringBuilder sb = new StringBuilder();
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (p[0] >= s.length()) break;
                char e = s.charAt(p[0]++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
                        p[0] += 4;
                        break;
                    default: throw new IllegalArgumentException("escape tidak dikenal: \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("string terpotong");
    }

    private static Number parseNumber(String s, int[] p) {
        int start = p[0];
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') p[0]++;
            else break;
        }
        String tok = s.substring(start, p[0]);
        if (tok.length() == 0) throw new IllegalArgumentException("angka kosong di posisi " + start);
        try {
            return Long.parseLong(tok);
        } catch (NumberFormatException nfe) {
            return Double.parseDouble(tok);
        }
    }

    public static String toJson(Object v) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { writeJsonString(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v.toString()); return; }
        if (v instanceof Number) {
            if (v instanceof Double || v instanceof Float) {
                double d = ((Number) v).doubleValue();
                if (d == Math.rint(d) && Math.abs(d) < 9.0e15) sb.append(Long.toString((long) d));
                else sb.append(Double.toString(d));
            } else {
                sb.append(v.toString());
            }
            return;
        }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeJsonString(sb, e.getKey());
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, o);
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("tipe tidak didukung: " + v.getClass());
    }

    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
