package com.hozinking.mekomod;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Menu mod melayang DI DALAM proses game Mekorama (bukan overlay sistem,
 * bukan root): tombol "MOD" + panel yang ditempel ke DecorView activity
 * game. Titik masuk dipanggil dari onCreate activity game yang di-patch
 * (lihat tools/patch_smali.py):
 *
 *   invoke-static {p0}, Lcom/hozinking/mekomod/Menu;->attach(Landroid/app/Activity;)V
 *
 * Fitur:
 *  1. Unlock Semua Level — edit file save profil (format zlib JSON,
 *     lihat SaveEditor). Backup otomatis "<save>.hozibak" sebelum edit
 *     pertama. Setelah menulis, game DITUTUP PAKSA (killProcess) supaya
 *     save di memori game tidak menimpa hasil edit saat game keluar
 *     secara normal — user membuka lagi game-nya dan semua level Story
 *     sudah terbuka.
 *  2. Pulihkan Save — salin balik backup di atas.
 *  3. Teleport ke Finish — EKSPERIMENTAL (payload native, menunggu uji
 *     HP). Status hasil ditampilkan apa adanya.
 *
 * Semua jalur dibungkus try/catch: menu tidak boleh membuat game crash.
 */
public final class Menu {
    private Menu() {}

    private static final String TAG = "MEKOMOD";
    public static final String VERSION = "1.0";

    private static Activity activity;
    private static TextView statusView;
    private static LinearLayout panel;
    private static boolean nativeInitCalled = false;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------

    public static void attach(final Activity a) {
        try {
            if (a == null) return;
            activity = a;
            a.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        buildUi(a);
                    } catch (Throwable t) {
                        Log.e(TAG, "buildUi gagal", t);
                    }
                }
            });
            if (NativeBridge.loaded && !nativeInitCalled) {
                nativeInitCalled = true;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { NativeBridge.nativeInit(); } catch (Throwable t) {
                            Log.e(TAG, "nativeInit gagal", t);
                        }
                    }
                }, "mekomod-init").start();
            }
        } catch (Throwable t) {
            Log.e(TAG, "attach gagal", t);
        }
    }

    private static int dp(Activity a, float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void buildUi(final Activity a) {
        FrameLayout decor = (FrameLayout) a.getWindow().getDecorView();
        if (decor.findViewWithTag("mekomod_button") != null) return;

        // ---- panel (dibangun dulu, awalnya GONE) ----
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag("mekomod_panel");
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xE61B1B26);
        panelBg.setCornerRadius(dp(a, 14));
        panel.setBackground(panelBg);
        int pad = dp(a, 14);
        panel.setPadding(pad, pad, pad, pad);
        int panelW = Math.min(dp(a, 320),
                a.getResources().getDisplayMetrics().widthPixels - dp(a, 24));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                panelW, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelLp.topMargin = dp(a, 84);
        panel.setLayoutParams(panelLp);
        panel.setVisibility(View.GONE);

        TextView title = new TextView(a);
        title.setText("Hozin Mekorama Mod v" + VERSION);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(title);

        TextView sub = new TextView(a);
        sub.setText("Mod menu non-root untuk Mekorama 1.7.2");
        sub.setTextColor(0xFFB0BEC5);
        sub.setTextSize(11);
        panel.addView(sub);

        Button unlockBtn = menuButton(a, "🔓  Unlock Semua Level");
        unlockBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doUnlock(a); }
        });
        panel.addView(unlockBtn);

        Button restoreBtn = menuButton(a, "↩  Pulihkan Save Asli");
        restoreBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doRestore(a); }
        });
        panel.addView(restoreBtn);

        Button tpBtn = menuButton(a, "🌀  Teleport ke Finish — EKSPERIMENTAL");
        tpBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doTeleport(); }
        });
        panel.addView(tpBtn);

        statusView = new TextView(a);
        statusView.setTextColor(0xFFFFCC80);
        statusView.setTextSize(11);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(a, 8);
        statusView.setLayoutParams(stLp);
        statusView.setText("Siap. Teleport masih eksperimental — menunggu uji HP.");
        panel.addView(statusView);

        Button closeBtn = menuButton(a, "Tutup Menu");
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { panel.setVisibility(View.GONE); }
        });
        panel.addView(closeBtn);

        TextView footer = new TextView(a);
        footer.setText("by hozinking.my.id");
        footer.setTextColor(0xFF78909C);
        footer.setTextSize(10);
        footer.setGravity(Gravity.END);
        panel.addView(footer);

        decor.addView(panel);

        // ---- tombol melayang ----
        final TextView btn = new TextView(a);
        btn.setTag("mekomod_button");
        btn.setText("MOD");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13);
        btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xD9E65100);
        btnBg.setCornerRadius(dp(a, 12));
        btn.setBackground(btnBg);
        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(dp(a, 54), dp(a, 54));
        btnLp.gravity = Gravity.TOP | Gravity.START;
        btnLp.leftMargin = dp(a, 14);
        btnLp.topMargin = dp(a, 110);
        btn.setLayoutParams(btnLp);
        btn.setOnTouchListener(new DragListener(a, btn));
        decor.addView(btn);
    }

    private static Button menuButton(Activity a, String text) {
        Button b = new Button(a);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 8);
        b.setLayoutParams(lp);
        return b;
    }

    /** Seret untuk memindah tombol; ketuk (tanpa geser) untuk buka/tutup panel. */
    private static final class DragListener implements View.OnTouchListener {
        private final Activity a;
        private final View v;
        private float downRawX, downRawY;
        private int startLeft, startTop;
        private boolean dragged;

        DragListener(Activity a, View v) { this.a = a; this.v = v; }

        @Override public boolean onTouch(View view, MotionEvent e) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startLeft = lp.leftMargin;
                    startTop = lp.topMargin;
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - downRawX);
                    int dy = (int) (e.getRawY() - downRawY);
                    if (Math.abs(dx) > dp(a, 6) || Math.abs(dy) > dp(a, 6)) dragged = true;
                    if (dragged) {
                        lp.leftMargin = Math.max(0, startLeft + dx);
                        lp.topMargin = Math.max(0, startTop + dy);
                        v.setLayoutParams(lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged && panel != null) {
                        boolean show = panel.getVisibility() != View.VISIBLE;
                        panel.setVisibility(show ? View.VISIBLE : View.GONE);
                        if (show) refreshNativeStatus();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    // ------------------------------------------------------------------
    // Aksi
    // ------------------------------------------------------------------

    private static void setStatus(final String s) {
        UI.post(new Runnable() {
            @Override public void run() {
                if (statusView != null) statusView.setText(s);
            }
        });
    }

    private static void refreshNativeStatus() {
        if (!NativeBridge.loaded) {
            setStatus("Payload native (libmekomod.so) tidak termuat di build ini.");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    setStatus(NativeBridge.nativeStatus());
                } catch (Throwable t) {
                    setStatus("Status payload gagal dibaca: " + t.getMessage());
                }
            }
        }, "mekomod-status").start();
    }

    private static void doUnlock(final Activity a) {
        setStatus("Memproses unlock…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File save = SaveFiles.findSave(a);
                    if (save == null) {
                        setStatus("Save tidak ditemukan. Main dulu sebentar sampai "
                                + "game membuat save, lalu coba lagi.");
                        return;
                    }
                    File backup = SaveFiles.backupOf(save);
                    if (!backup.exists()) {
                        SaveFiles.copy(save, backup);
                    }
                    List<String> story = SaveFiles.storyNames(a);
                    Map<String, Object> root = SaveEditor.decodeSave(SaveFiles.read(save));
                    long total = SaveEditor.unlockAll(root, story);
                    SaveFiles.write(save, SaveEditor.encodeSave(root));

                    // verifikasi baca-balik dari disk
                    Map<String, Object> check = SaveEditor.decodeSave(SaveFiles.read(save));
                    long verify = SaveEditor.totalStars(check, story);
                    if (verify < 150) {
                        setStatus("GAGAL verifikasi: total bintang hanya " + verify
                                + ". Save asli aman di backup.");
                        return;
                    }
                    setStatus("Unlock BERHASIL: " + story.size() + " level Story terbuka "
                            + "(total bintang " + total + ").\nFile: " + save.getName()
                            + "\nGame ditutup paksa ±2 detik agar save tidak tertimpa — "
                            + "buka lagi Mekorama-nya.");
                    scheduleKill();
                } catch (Throwable t) {
                    Log.e(TAG, "unlock gagal", t);
                    setStatus("Unlock GAGAL: " + t.getMessage());
                }
            }
        }, "mekomod-unlock").start();
    }

    private static void doRestore(final Activity a) {
        setStatus("Memulihkan save…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File save = SaveFiles.findSave(a);
                    if (save == null) {
                        setStatus("Save tidak ditemukan — tidak ada yang dipulihkan.");
                        return;
                    }
                    File backup = SaveFiles.backupOf(save);
                    if (!backup.exists()) {
                        setStatus("Belum ada backup (" + backup.getName()
                                + "). Backup dibuat otomatis saat Unlock pertama kali.");
                        return;
                    }
                    SaveFiles.copy(backup, save);
                    setStatus("Save asli dipulihkan dari backup.\nGame ditutup paksa "
                            + "±2 detik — buka lagi Mekorama-nya.");
                    scheduleKill();
                } catch (Throwable t) {
                    Log.e(TAG, "restore gagal", t);
                    setStatus("Pulihkan GAGAL: " + t.getMessage());
                }
            }
        }, "mekomod-restore").start();
    }

    private static void doTeleport() {
        if (!NativeBridge.loaded) {
            setStatus("Teleport tidak tersedia: payload native tidak termuat.");
            return;
        }
        setStatus("Mencoba teleport (eksperimental)…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    setStatus(NativeBridge.nativeTeleport());
                } catch (Throwable t) {
                    setStatus("Teleport GAGAL: " + t.getMessage());
                }
            }
        }, "mekomod-teleport").start();
    }

    /**
     * Tutup paksa proses game. Perlu karena game menyimpan profil di
     * memori dan menulisnya saat keluar normal — tanpa ini hasil edit
     * save akan tertimpa save lama.
     */
    private static void scheduleKill() {
        UI.postDelayed(new Runnable() {
            @Override public void run() {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 2200);
    }
}
