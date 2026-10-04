package com.vaonis.vesperacontrol.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.vaonis.vesperacontrol.BuildConfig;
import com.vaonis.vesperacontrol.R;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controllo versione sul link pubblico, all'avvio e da Impostazioni.
 * Nella cartella cerca {@code vesperacontrol-version.json} e l'APK indicato lÃ¬.
 */
public final class AppUpdates {
    public static final String VERSION_FILE = "vesperacontrol-version.json";
    public static final String APK_NAME = "VesperaControl.apk";

    private static final String PREFS = "vesperacontrol_updates";
    private static final String KEY_URL = "mega_version_url";
    private static final String KEY_APK_URL = "mega_apk_url";
    private static final String KEY_SKIP = "update_skip_version";
    private static final String KEY_LAST = "update_last_check";
    private static final String KEY_LAST_AT = "update_last_check_at";
    private static final long VISIBLE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    private static volatile boolean promptOpen;

    private AppUpdates() {}

    public static final class RemoteUpdate {
        public final String version;
        public final int versionCode;
        public final String apkName;
        public final String apkMegaUrl;

        RemoteUpdate(String version, int versionCode, String apkName, String apkMegaUrl) {
            this.version = version;
            this.versionCode = versionCode;
            this.apkName = apkName == null || apkName.isBlank() ? APK_NAME : apkName;
            this.apkMegaUrl = apkMegaUrl == null ? "" : apkMegaUrl;
        }
    }

    public static String versionUrl(Context context) {
        String saved = prefs(context).getString(KEY_URL, "").trim();
        if (MegaPublic.isPublicLink(saved)) return saved;
        String bundled = context.getString(R.string.mega_version_url).trim();
        if (MegaPublic.isPublicLink(bundled)) {
            prefs(context).edit().putString(KEY_URL, bundled).apply();
            return bundled;
        }
        return saved;
    }

    public static void saveLinks(Context context, String versionUrl, String apkUrl) {
        prefs(context).edit()
                .putString(KEY_URL, versionUrl == null ? "" : versionUrl.trim())
                .putString(KEY_APK_URL, apkUrl == null ? "" : apkUrl.trim())
                .apply();
    }

    public static String savedApkUrl(Context context) {
        return prefs(context).getString(KEY_APK_URL, "").trim();
    }

    public static String lastCheck(Context context) {
        String line = prefs(context).getString(KEY_LAST, "");
        return line == null || line.isBlank() ? "Nessun controllo ancora" : line;
    }

    /** Come ESA Meter: all'avvio si controlla sempre; se l'app torna visibile, solo dopo 24 ore. */
    public static void checkWhenVisible(Activity activity) {
        if (activity == null) return;
        long last = prefs(activity).getLong(KEY_LAST_AT, 0L);
        long now = System.currentTimeMillis();
        if (last <= 0L || now < last || now - last < VISIBLE_CHECK_INTERVAL_MS) return;
        check(activity, true, null);
    }

    public static void check(Activity activity, boolean silent, TextView note) {
        if (activity == null || activity.isFinishing()) return;
        if (!CHECKING.compareAndSet(false, true)) return;
        String url = versionUrl(activity);
        WORK.execute(() -> {
            RemoteUpdate remote = null;
            Exception error = null;
            try {
                if (!MegaPublic.isPublicLink(url)) {
                    throw new IllegalStateException("Manca il link degli aggiornamenti.");
                }
                remote = fetch(url);
            } catch (Exception e) {
                error = e;
            }
            RemoteUpdate found = remote;
            Exception failed = error;
            activity.runOnUiThread(() -> deliver(activity, silent, note, found, failed));
        });
    }

    public static void install(Activity activity, RemoteUpdate remote, TextView note) {
        if (activity == null || remote == null) return;
        WORK.execute(() -> {
            try {
                File target = new File(activity.getCacheDir(), "updates/" + APK_NAME);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("Impossibile preparare il download");
                }
                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("Impossibile sostituire il file scaricato");
                }
                byte[] data = downloadApk(activity, remote);
                if (data.length < 1024 || data[0] != 'P' || data[1] != 'K') {
                    throw new IllegalStateException("Il file scaricato non Ã¨ un APK");
                }
                java.nio.file.Files.write(target.toPath(), data);
                activity.runOnUiThread(() -> {
                    try {
                        installApk(activity, target);
                        prefs(activity).edit().remove(KEY_SKIP).apply();
                        remember(activity, note, "Installazione " + remote.version);
                    } catch (Exception e) {
                        showInfo(activity, "Aggiornamento", MegaPublic.facing(e.getMessage()));
                    }
                });
            } catch (Exception e) {
                activity.runOnUiThread(() ->
                        showInfo(activity, "Aggiornamento", MegaPublic.facing(e.getMessage())));
            }
        });
    }

    private static void deliver(
            Activity activity, boolean silent, TextView note, RemoteUpdate found, Exception error) {
        CHECKING.set(false);
        if (activity.isFinishing()) return;
        if (error != null || found == null) {
            String message = MegaPublic.facing(error == null ? "" : error.getMessage());
            remember(activity, note, message);
            if (!silent) {
                showInfo(activity, "Aggiornamenti", "Impossibile controllare gli aggiornamenti.\n\n" + message);
            }
            return;
        }
        if (!isNewer(found, activity)) {
            remember(activity, note, "Sei aggiornato (" + found.version + ")");
            if (!silent) {
                showInfo(activity, "Aggiornamenti",
                        "Nessun aggiornamento: la versione disponibile Ã¨ la " + found.version
                                + ", uguale o meno recente di quella installata.");
            }
            return;
        }
        String skip = prefs(activity).getString(KEY_SKIP, "");
        if (silent && found.version.equals(skip)) {
            remember(activity, note, "Disponibile " + found.version + " (rimandata)");
            return;
        }
        remember(activity, note, "Disponibile " + found.version);
        ask(activity, found, note);
    }

    private static void ask(Activity activity, RemoteUpdate remote, TextView note) {
        if (promptOpen || activity.isFinishing()) return;
        promptOpen = true;
        String local = BuildConfig.VERSION_NAME;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Aggiornamento disponibile")
                .setMessage("Ãˆ disponibile Vespera Control " + remote.version + " (ora hai " + local + ").\n\n"
                        + "Vuoi aggiornare l'app?")
                .setPositiveButton("Aggiorna", (d, w) -> install(activity, remote, note))
                .setNegativeButton("Dopo", (d, w) -> {
                    prefs(activity).edit().putString(KEY_SKIP, remote.version).apply();
                    remember(activity, note, "Aggiornamento " + remote.version + " rimandato");
                })
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> promptOpen = false);
        dialog.show();
    }

    static RemoteUpdate fetch(String versionUrl) throws Exception {
        JSONObject payload;
        if (MegaPublic.isPublicFolderUrl(versionUrl)) {
            byte[] raw = MegaPublic.downloadFolderFile(versionUrl, VERSION_FILE);
            payload = new JSONObject(new String(raw, StandardCharsets.UTF_8).replace("\uFEFF", "").trim());
        } else {
            payload = MegaPublic.downloadJson(versionUrl);
        }
        String version = payload.optString("version").trim();
        if (version.isEmpty()) throw new IllegalStateException("File versione non valido");
        String apk = payload.optString("apk", payload.optString("apkVersioned")).trim();
        String apkUrl = payload.optString("apkMegaUrl", payload.optString("apk_mega_url")).trim();
        return new RemoteUpdate(version, payload.optInt("versionCode", 0), apk, apkUrl);
    }

    private static byte[] downloadApk(Context context, RemoteUpdate remote) throws Exception {
        LinkedHashSet<String> folders = new LinkedHashSet<>();
        String versionUrl = versionUrl(context);
        if (MegaPublic.isPublicFolderUrl(versionUrl)) folders.add(versionUrl);
        String bundled = context.getString(R.string.mega_version_url).trim();
        if (MegaPublic.isPublicFolderUrl(bundled)) folders.add(bundled);
        Exception last = null;
        for (String folder : folders) {
            try {
                return MegaPublic.downloadFolderFile(folder, remote.apkName);
            } catch (Exception e) {
                last = e;
            }
        }
        String apkUrl = savedApkUrl(context);
        if (!MegaPublic.isMegaFileUrl(apkUrl)) apkUrl = remote.apkMegaUrl;
        if (MegaPublic.isMegaFileUrl(apkUrl)) return MegaPublic.downloadBytes(apkUrl);
        if (last != null) throw last;
        throw new IllegalStateException("Manca il link pubblico dell'APK.");
    }

    private static boolean isNewer(RemoteUpdate remote, Context context) {
        int byName = compare(remote.version, BuildConfig.VERSION_NAME);
        if (byName != 0) return byName > 0;
        return remote.versionCode > BuildConfig.VERSION_CODE;
    }

    private static int compare(String left, String right) {
        String[] a = (left == null ? "" : left.trim()).split("\\.");
        String[] b = (right == null ? "" : right.trim()).split("\\.");
        int size = Math.max(3, Math.max(a.length, b.length));
        for (int i = 0; i < size; i++) {
            int av = part(a, i);
            int bv = part(b, i);
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int part(String[] values, int index) {
        if (index >= values.length || values[index].isEmpty()) return 0;
        try {
            return Integer.parseInt(values[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void installApk(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            throw new IllegalStateException("Abilita l'installazione da questa app e riprova");
        }
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    private static void showInfo(Activity activity, String title, String message) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private static void remember(Context context, TextView note, String text) {
        String stamp = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(new Date());
        String line = stamp + " Â· " + text;
        prefs(context).edit()
                .putString(KEY_LAST, line)
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .apply();
        if (note != null) note.setText(line);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

