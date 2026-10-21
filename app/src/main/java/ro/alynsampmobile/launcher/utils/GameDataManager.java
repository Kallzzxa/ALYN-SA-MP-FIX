package ro.alynsampmobile.launcher.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ALYN-SA-MP-FIX: in-game data downloader (ported from SA-MP Mobile Launcher pattern).
 *
 * Downloads ONE complete data package (files.zip via MediaFire, URL from app.json
 * "data_url") and extracts it to the game directory. Completion is version-pinned
 * via "data_version" + SAMP/.data_ready marker + sentinel file.
 */
public final class GameDataManager {
    private static final String TAG = "GameDataManager";
    private static final String APP_CONFIG = "https://raw.githubusercontent.com/Kallzzxa/ALYN-SA-MP-FIX/main/app.json";
    private static final String FALLBACK_DATA_PAGE = "https://www.mediafire.com/file/rhkel93l3frrgtn/files.zip/file";
    private static final String FALLBACK_DATA_VERSION = "1";

    private static final String PREFS = "alyn_game_data";
    private static final String KEY_DATA_VERSION = "data_version";
    private static final String MARKER = "SAMP/.data_ready";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public interface ReadyCallback { void onReady(); }

    private static final class DataInfo {
        String url;
        String version;
        long size;
        String sentinel;
    }

    private GameDataManager() {}

    public static void checkAndPrepare(Activity activity, ReadyCallback callback) {
        showLoading(activity, "Memeriksa Data Game", "Menghubungkan ke server...");
        IO.execute(() -> {
            try {
                JSONObject config = getJson(APP_CONFIG);
                DataInfo info = new DataInfo();
                info.url = config.optString("data_url", FALLBACK_DATA_PAGE);
                if (info.url.isEmpty()) info.url = FALLBACK_DATA_PAGE;
                info.version = config.optString("data_version", FALLBACK_DATA_VERSION);
                if (info.version.isEmpty()) info.version = FALLBACK_DATA_VERSION;
                info.size = config.optLong("data_size", 0);
                info.sentinel = config.optString("data_sentinel", "SAMP/SAMP.ide");

                String installed = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                        .getString(KEY_DATA_VERSION, "");
                if (info.version.equals(installed) && isInstalled(activity, info)) {
                    MAIN.post(() -> { dismissLoading(); if (!activity.isFinishing()) callback.onReady(); });
                    return;
                }
                final DataInfo fi = info;
                MAIN.post(() -> showDownloadOffer(activity, fi, callback));
            } catch (Exception e) {
                Log.e(TAG, "Config check failed", e);
                MAIN.post(() -> showNetworkError(activity, e.getMessage(), callback));
            }
        });
    }

    private static boolean isInstalled(Activity a, DataInfo info) {
        try {
            File root = a.getExternalFilesDir(null);
            if (root == null) return false;
            File marker = new File(root, MARKER);
            if (!marker.isFile()) return false;
            if (info.sentinel != null && !info.sentinel.isEmpty()) {
                if (!new File(root, info.sentinel).exists()) return false;
            }
            String[] list = root.list();
            return list != null && list.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void markInstalled(Activity a, DataInfo info) {
        try {
            File root = a.getExternalFilesDir(null);
            if (root == null) return;
            File marker = new File(root, MARKER);
            File parent = marker.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (OutputStream os = new FileOutputStream(marker)) {
                os.write(info.version.getBytes("UTF-8"));
            }
            a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .edit().putString(KEY_DATA_VERSION, info.version).apply();
        } catch (Exception e) {
            Log.e(TAG, "markInstalled failed", e);
        }
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "ALYN-SA-MP-FIX");
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    private static String getText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String resolveDownloadUrl(String url) throws Exception {
        if (url.contains("mediafire.com/file/")) {
            String html = getText(url);
            Matcher m = Pattern.compile("https://download\\d*\\.mediafire\\.com/[^\\s\"'<>]+").matcher(html);
            if (m.find()) return m.group(0);
            throw new Exception("Tidak bisa menemukan link download langsung dari MediaFire.");
        }
        return url;
    }

    // ---- UI ----
    private static volatile AlertDialog loadingDialog;

    private static void showLoading(Activity a, String title, String message) {
        if (a.isFinishing()) return;
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 44, 48, 44);
        TextView h = new TextView(a); h.setText(title); h.setTextSize(20);
        TextView m = new TextView(a); m.setText(message); m.setTextSize(14);
        ProgressBar p = new ProgressBar(a); p.setIndeterminate(true);
        root.addView(h); root.addView(m); root.addView(p);
        AlertDialog d = new AlertDialog.Builder(a).setView(root).setCancelable(false).create();
        loadingDialog = d; d.show();
    }

    private static void dismissLoading() {
        try {
            if (loadingDialog != null && loadingDialog.isShowing()) loadingDialog.dismiss();
        } catch (Exception ignored) {}
        loadingDialog = null;
    }

    private static void showNetworkError(Activity a, String detail, ReadyCallback cb) {
        if (a.isFinishing()) return;
        dismissLoading();
        new AlertDialog.Builder(a).setTitle("Gagal memuat data game")
                .setMessage("Tidak bisa membaca app.json. Periksa internet lalu coba lagi.\n\n" + (detail == null ? "" : detail))
                .setCancelable(false)
                .setPositiveButton("COBA LAGI", (d, w) -> checkAndPrepare(a, cb))
                .setNegativeButton("KELUAR", (d, w) -> a.finishAffinity())
                .show();
    }

    private static void showDownloadOffer(Activity a, DataInfo info, ReadyCallback cb) {
        if (a.isFinishing()) return;
        dismissLoading();
        String sizeTxt = info.size > 0 ? " (" + fmtBytes(info.size) + ")" : "";
        new AlertDialog.Builder(a).setTitle("Download Game Data")
                .setMessage("Paket data lengkap" + sizeTxt + "\nVersi " + info.version + " — cukup download sekali.")
                .setCancelable(false)
                .setPositiveButton("DOWNLOAD", (d, w) -> startInstall(a, info, cb))
                .setNegativeButton("KELUAR", (d, w) -> a.finishAffinity())
                .show();
    }

    private static final class ProgressUi {
        AlertDialog dialog;
        TextView phase, detail;
        ProgressBar bar;
    }

    private static ProgressUi showProgress(Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 44, 48, 40);
        TextView title = new TextView(a); title.setText("Menyiapkan Game Data"); title.setTextSize(20);
        TextView phase = new TextView(a); phase.setText("Menghubungkan..."); phase.setTextSize(14);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000); bar.setProgress(0);
        TextView detail = new TextView(a); detail.setText(""); detail.setTextSize(13);
        root.addView(title); root.addView(phase); root.addView(bar); root.addView(detail);
        AlertDialog d = new AlertDialog.Builder(a).setView(root).setCancelable(false).create();
        d.show();
        ProgressUi ui = new ProgressUi();
        ui.dialog = d; ui.phase = phase; ui.detail = detail; ui.bar = bar;
        return ui;
    }

    private static void postPhase(ProgressUi ui, String phase, String detail) {
        MAIN.post(() -> {
            if (ui.dialog.isShowing()) {
                ui.phase.setText(phase);
                ui.detail.setText(detail);
            }
        });
    }

    private static void startInstall(Activity a, DataInfo info, ReadyCallback cb) {
        if (a.isFinishing()) return;
        ProgressUi ui = showProgress(a);
        IO.execute(() -> {
            File zipFile = null;
            try {
                postPhase(ui, "Mencari link download...", "");
                String directUrl = resolveDownloadUrl(info.url);
                Log.i(TAG, "direct url resolved");

                File cacheDir = a.getExternalCacheDir();
                if (cacheDir == null) cacheDir = a.getCacheDir();
                zipFile = new File(cacheDir, "game_data_" + info.version + ".zip");
                if (zipFile.exists()) zipFile.delete();

                downloadWithProgress(directUrl, zipFile, info.size, ui);
                if (info.size > 0 && zipFile.length() != info.size)
                    throw new Exception("Ukuran file tidak sesuai, download mungkin corrupt. Coba lagi.");

                postPhase(ui, "Mengekstrak data...", "");
                extractWithProgress(a, zipFile, ui);

                File root = a.getExternalFilesDir(null);
                if (root == null) throw new Exception("Penyimpanan tidak tersedia.");
                if (info.sentinel != null && !info.sentinel.isEmpty()
                        && !new File(root, info.sentinel).exists())
                    throw new Exception("Data tidak lengkap setelah ekstrak (sentinel hilang).");

                markInstalled(a, info);
                try { if (zipFile.exists()) zipFile.delete(); } catch (Exception ignored) {}

                MAIN.post(() -> {
                    if (ui.dialog.isShowing()) ui.dialog.dismiss();
                    Toast.makeText(a, "Game data siap dimainkan", Toast.LENGTH_SHORT).show();
                    if (!a.isFinishing()) cb.onReady();
                });
            } catch (Exception e) {
                Log.e(TAG, "Install failed", e);
                final File zf = zipFile;
                MAIN.post(() -> {
                    if (ui.dialog.isShowing()) ui.dialog.dismiss();
                    if (a.isFinishing()) return;
                    new AlertDialog.Builder(a).setTitle("Download gagal")
                            .setMessage(e.getMessage() == null ? "Silakan coba lagi." : e.getMessage())
                            .setCancelable(false)
                            .setPositiveButton("COBA LAGI", (x, w) -> startInstall(a, info, cb))
                            .setNegativeButton("KELUAR", (x, w) -> a.finishAffinity())
                            .show();
                    try { if (zf != null && zf.exists()) zf.delete(); } catch (Exception ignored) {}
                });
            }
        });
    }

    private static void downloadWithProgress(String url, File out, long expectedSize, ProgressUi ui) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "ALYN-SA-MP-FIX");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new Exception("Server menolak download (HTTP " + code + ").");
        long total = c.getContentLengthLong();
        if (total <= 0) total = expectedSize;

        final long totalF = total;
        postPhase(ui, "Mengunduh game data...", totalF > 0 ? "0 / " + fmtBytes(totalF) : "");
        long lastUi = 0;
        long startNs = System.nanoTime();
        byte[] buf = new byte[128 * 1024];
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream fos = new BufferedOutputStream(new FileOutputStream(out))) {
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 300) {
                    lastUi = now;
                    long elapsed = Math.max(1, (System.nanoTime() - startNs) / 1_000_000_000L);
                    long speed = done / elapsed;
                    final long d = done;
                    final int permille = totalF > 0 ? (int) Math.min(1000, (d * 1000) / totalF) : 0;
                    MAIN.post(() -> {
                        if (!ui.dialog.isShowing()) return;
                        ui.bar.setProgress(permille);
                        ui.detail.setText(fmtBytes(d) + (totalF > 0 ? " / " + fmtBytes(totalF) : "")
                                + "  (" + (permille / 10) + "%)  " + fmtBytes(speed) + "/dtk");
                    });
                }
            }
        } finally {
            c.disconnect();
        }
        MAIN.post(() -> {
            if (ui.dialog.isShowing()) {
                ui.bar.setProgress(1000);
                ui.detail.setText("Download selesai");
            }
        });
    }

    private static void extractWithProgress(Activity a, File zipFile, ProgressUi ui) throws Exception {
        File root = a.getExternalFilesDir(null);
        if (root == null) throw new Exception("Penyimpanan tidak tersedia.");
        final String rootCanon = root.getCanonicalPath() + File.separator;
        try (ZipFile zip = new ZipFile(zipFile)) {
            int totalEntries = zip.size();
            if (totalEntries <= 0) throw new Exception("File zip kosong / rusak.");
            ArrayList<String> names = new ArrayList<>(totalEntries);
            Enumeration<? extends ZipEntry> scan = zip.entries();
            while (scan.hasMoreElements()) names.add(scan.nextElement().getName());
            final String stripPrefix = detectStripPrefix(names);
            if (!stripPrefix.isEmpty()) Log.i(TAG, "stripping zip prefix: " + stripPrefix);

            postPhase(ui, "Mengekstrak data...", "0 / " + totalEntries + " file");
            Enumeration<? extends ZipEntry> en = zip.entries();
            int done = 0;
            long lastUi = 0;
            byte[] buf = new byte[64 * 1024];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!stripPrefix.isEmpty() && name.startsWith(stripPrefix))
                    name = name.substring(stripPrefix.length());
                if (name.isEmpty()) { done++; continue; }
                File out = new File(root, name);
                // zip-slip protection
                if (!out.getCanonicalPath().startsWith(rootCanon))
                    throw new Exception("Zip entry tidak aman: " + e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (InputStream in = new BufferedInputStream(zip.getInputStream(e));
                         OutputStream fos = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                }
                done++;
                long now = System.currentTimeMillis();
                if (now - lastUi > 500) {
                    lastUi = now;
                    final int d = done;
                    final int permille = (int) Math.min(1000, (d * 1000L) / totalEntries);
                    MAIN.post(() -> {
                        if (!ui.dialog.isShowing()) return;
                        ui.bar.setProgress(permille);
                        ui.detail.setText(d + " / " + totalEntries + " file");
                    });
                }
            }
        }
    }

    private static String detectStripPrefix(ArrayList<String> names) {
        if (names.isEmpty()) return "";
        String first = names.get(0);
        int slash = first.indexOf('/');
        if (slash <= 0) return "";
        String prefix = first.substring(0, slash + 1);
        for (String n : names) {
            if (!n.startsWith(prefix)) return "";
        }
        return prefix;
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }
}
