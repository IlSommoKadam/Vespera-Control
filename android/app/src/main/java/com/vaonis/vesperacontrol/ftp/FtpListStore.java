package com.vaonis.vesperacontrol.ftp;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.vaonis.vesperacontrol.DevicePrefs;
import com.vaonis.vesperacontrol.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Elenco FTP condiviso: sopravvive al cambio tab, alla rotazione e al passaggio in background.
 * Non annulla il lavoro in corso quando l'UI si stacca.
 */
public final class FtpListStore {

    /** Avvio / ripresa: silenzioso. Pulsante: toast. Tab Anteprima: notifica se ci sono novità. */
    public static final int MODE_SILENT = 0;
    public static final int MODE_BUTTON = 1;
    public static final int MODE_TAB = 2;

    private static final String CHANNEL_ID = "vespera_preview";
    private static final int NOTIFY_LIST = 4101;

    public interface Listener {
        void onFtpListChanged(@NonNull State state);
    }

    public static final class State {
        public final boolean listing;
        public final List<FtpPreview.Item> items;
        public final String statusText;
        public final String loadedKey;
        public final int lastMode;
        public final int lastAdded;
        public final int lastUpdated;

        State(boolean listing, List<FtpPreview.Item> items, String statusText,
              String loadedKey, int lastMode, int lastAdded, int lastUpdated) {
            this.listing = listing;
            this.items = items;
            this.statusText = statusText == null ? "" : statusText;
            this.loadedKey = loadedKey == null ? "" : loadedKey;
            this.lastMode = lastMode;
            this.lastAdded = lastAdded;
            this.lastUpdated = lastUpdated;
        }
    }

    private static final FtpListStore INSTANCE = new FtpListStore();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> loadedSignatures = new HashMap<>();

    private Context app;
    private boolean listing;
    private final List<FtpPreview.Item> items = new ArrayList<>();
    private String statusText = "";
    private String loadedKey = "";
    private int lastMode = MODE_SILENT;
    private int lastAdded;
    private int lastUpdated;

    private FtpListStore() {}

    public static FtpListStore get() {
        return INSTANCE;
    }

    public synchronized void init(@NonNull Context context) {
        if (app == null) {
            app = context.getApplicationContext();
            ensureNotificationChannel();
        }
    }

    public void addListener(@NonNull Listener listener) {
        listeners.add(listener);
        listener.onFtpListChanged(snapshot());
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    @NonNull
    public State snapshot() {
        synchronized (this) {
            return new State(listing, new ArrayList<>(items), statusText, loadedKey,
                    lastMode, lastAdded, lastUpdated);
        }
    }

    /** Avvio app o ripresa da background: aggiorna se c'è un IP e non è già in corso. */
    public void refreshIfNeeded(@NonNull Context context) {
        init(context);
        String host = DevicePrefs.getIp(app);
        if (host == null || host.isEmpty()) return;
        refresh(context, MODE_SILENT);
    }

    public void refresh(@NonNull Context context, int mode) {
        init(context);
        final String host = DevicePrefs.getIp(app);
        if (host == null || host.isEmpty()) {
            if (mode == MODE_BUTTON) {
                Toast.makeText(app, R.string.label_device_ip_missing, Toast.LENGTH_LONG).show();
            }
            return;
        }
        synchronized (this) {
            if (listing) return;
            listing = true;
            lastMode = mode;
            statusText = app.getString(R.string.preview_list_updating);
        }
        notifyListeners();
        if (mode == MODE_BUTTON) {
            Toast.makeText(app, R.string.preview_listing, Toast.LENGTH_SHORT).show();
        }

        final int port = DevicePrefs.getFtpPort(app);
        final String key = host + ":" + port;
        final boolean allRoots = !DevicePrefs.SOURCE_VESP.equals(DevicePrefs.getFtpSource(app));
        final int requestMode = mode;

        executor.execute(() -> {
            try {
                List<FtpPreview.Item> found = FtpPreview.listObjects(host, port, allRoots);
                applySuccess(found, key, requestMode);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "FTP errore" : e.getMessage();
                applyError(msg, requestMode);
            }
        });
    }

    private void applySuccess(List<FtpPreview.Item> found, String key, int mode) {
        int added = 0;
        int updated = 0;
        String summary;
        synchronized (this) {
            boolean hasBaseline = key.equals(loadedKey) && !loadedSignatures.isEmpty();
            for (FtpPreview.Item item : found) {
                String before = loadedSignatures.get(item.folder);
                if (!hasBaseline) {
                    item.mark = "";
                } else if (before == null) {
                    item.mark = app.getString(R.string.preview_mark_new);
                    added++;
                } else if (!before.equals(item.signature)) {
                    item.mark = app.getString(R.string.preview_mark_updating);
                    updated++;
                } else {
                    item.mark = "";
                }
            }
            loadedSignatures.clear();
            for (FtpPreview.Item item : found) {
                loadedSignatures.put(item.folder, item.signature);
            }
            loadedKey = key;
            items.clear();
            items.addAll(found);
            listing = false;
            lastAdded = added;
            lastUpdated = updated;
            lastMode = mode;
            summary = listSummary(found.size(), added, updated, hasBaseline);
            statusText = summary;
        }
        final int finalAdded = added;
        final int finalUpdated = updated;
        final String finalSummary = summary;
        main.post(() -> {
            notifyListeners();
            if (mode == MODE_BUTTON) {
                Toast.makeText(app, finalSummary, Toast.LENGTH_SHORT).show();
            } else if (mode == MODE_TAB && (finalAdded > 0 || finalUpdated > 0)) {
                notifyListUpdated(finalAdded, finalUpdated);
            }
        });
    }

    private void applyError(String msg, int mode) {
        synchronized (this) {
            listing = false;
            statusText = msg;
            lastMode = mode;
            lastAdded = 0;
            lastUpdated = 0;
        }
        main.post(() -> {
            notifyListeners();
            if (mode == MODE_BUTTON) {
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private String listSummary(int count, int added, int updated, boolean hasBaseline) {
        String base = app.getString(R.string.preview_list_count, count);
        if (!hasBaseline) return base;
        if (added == 0 && updated == 0) {
            return base + " \u00b7 " + app.getString(R.string.preview_no_changes);
        }
        return base + " \u00b7 " + changesText(added, updated);
    }

    private String changesText(int added, int updated) {
        StringBuilder sb = new StringBuilder();
        if (added > 0) sb.append(app.getString(R.string.preview_changes_new, added));
        if (updated > 0) {
            if (sb.length() > 0) sb.append(" \u00b7 ");
            sb.append(app.getString(R.string.preview_changes_updating, updated));
        }
        return sb.toString();
    }

    private void notifyListeners() {
        State state = snapshot();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (Listener l : listeners) l.onFtpListChanged(state);
        } else {
            main.post(() -> {
                for (Listener l : listeners) l.onFtpListChanged(state);
            });
        }
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26 || app == null) return;
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                app.getString(R.string.preview_notify_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(channel);
    }

    private void notifyListUpdated(int added, int updated) {
        if (app == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.vespera_launcher_icon)
                .setContentTitle(app.getString(R.string.preview_notify_title))
                .setContentText(changesText(added, updated))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFY_LIST, builder.build());
    }
}
