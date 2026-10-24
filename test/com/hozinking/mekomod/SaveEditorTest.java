package com.hozinking.mekomod;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Test host untuk SaveEditor — dijalankan di CI (dan lokal) terhadap file
 * ASLI dari APK game: argumen [0] = assets/db (template save),
 * argumen [1] = assets/level_infos.json.
 *
 * Yang dibuktikan:
 *  1. Template save bisa didekode & struktur dasarnya sesuai temuan REA.
 *  2. Roundtrip parse->serialize->parse menghasilkan pohon yang sama.
 *  3. Setelah unlockAll: SEMUA level story punya entri dengan s =
 *     STARS_PER_LEVEL dan total bintang >= 150 (ambang "su" tertinggi).
 *  4. Entri non-story dan field lain tidak berubah.
 *  5. Hasil enkode bisa didekode ulang & unlock bersifat idempoten.
 */
public final class SaveEditorTest {
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

    private static String readText(File f) throws Exception {
        return new String(readAll(f), "UTF-8");
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("pakai: SaveEditorTest <assets/db> <level_infos.json>");
            System.exit(2);
        }
        byte[] dbBytes = readAll(new File(args[0]));
        Map<String, Object> infos = (Map<String, Object>) SaveEditor.parseJson(readText(new File(args[1])));
        List<String> story = SaveEditor.storyNamesFromLevelInfos(infos);
        check(story.size() == 80, "level_infos berisi 80 level story (dapat " + story.size() + ")");

        Map<String, Object> root = SaveEditor.decodeSave(dbBytes);
        check(root.get("lvs") instanceof List, "template save terdekode & punya \"lvs\"");
        long before = SaveEditor.totalStars(root, story);
        System.out.println("  info total bintang story sebelum unlock: " + before);

        // roundtrip tanpa perubahan
        Map<String, Object> rt = SaveEditor.decodeSave(SaveEditor.encodeSave(root));
        check(SaveEditor.toJson(rt).equals(SaveEditor.toJson(root)),
                "roundtrip parse->serialize->parse identik");

        // snapshot entri non-story + field scalar
        String danBefore = String.valueOf(root.get("dan"));
        Object pwywBefore = root.get("pwyw");
        int lvCountBefore = ((List<Object>) root.get("lvs")).size();

        long total = SaveEditor.unlockAll(root, story);
        check(total >= 150, "total bintang story setelah unlock >= 150 (dapat " + total + ")");

        // semua story punya entri dengan s == STARS_PER_LEVEL
        boolean allOk = true;
        java.util.Map<String, Map<String, Object>> byName = new java.util.HashMap<String, Map<String, Object>>();
        for (Object o : (List<Object>) root.get("lvs")) {
            Map<String, Object> e = (Map<String, Object>) o;
            byName.put(String.valueOf(e.get("n")), e);
        }
        for (String n : story) {
            Map<String, Object> e = byName.get(n);
            if (e == null || !(e.get("s") instanceof Number)
                    || ((Number) e.get("s")).longValue() != SaveEditor.STARS_PER_LEVEL) {
                allOk = false;
                System.out.println("  info entri bermasalah: " + n);
            }
        }
        check(allOk, "semua 80 entri story ada & s=" + SaveEditor.STARS_PER_LEVEL);

        check(String.valueOf(root.get("dan")).equals(danBefore), "field lain (dan) tidak berubah");
        check(root.get("pwyw") == pwywBefore, "field pwyw tidak disentuh");
        int lvCountAfter = ((List<Object>) root.get("lvs")).size();
        check(lvCountAfter >= lvCountBefore, "jumlah entri lvs tidak berkurang (" + lvCountBefore + " -> " + lvCountAfter + ")");

        // enkode -> dekode ulang -> idempoten
        Map<String, Object> again = SaveEditor.decodeSave(SaveEditor.encodeSave(root));
        String once = SaveEditor.toJson(again);
        long total2 = SaveEditor.unlockAll(again, story);
        check(total2 == total && SaveEditor.toJson(again).equals(once), "unlock idempoten setelah roundtrip zlib");

        if (failures > 0) {
            System.out.println("SaveEditorTest GAGAL: " + failures + " pemeriksaan");
            System.exit(1);
        }
        System.out.println("SaveEditorTest LULUS semua pemeriksaan");
    }
}
