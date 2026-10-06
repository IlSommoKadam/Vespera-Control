package com.vaonis.vesperacontrol.ui.foto;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vaonis.vesperacontrol.AdbBridgeHolder;
import com.vaonis.vesperacontrol.R;
import com.vaonis.vesperacontrol.RemoteState;
import com.vaonis.vesperacontrol.adb.AdbBridge;
import com.vaonis.vesperacontrol.ui.TabRefreshable;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FotoFragment extends Fragment implements TabRefreshable {

    /** Il file di stato dell'Helper viene riscritto ogni 2,5 s. */
    private static final long AUTO_REFRESH_MS = 3_000L;

    private TextView textHdStatus;
    private TextView textLog;
    private TextView textSyncSummary;
    private TextView textSyncCurrent;
    private TextView textSyncQueueEmpty;
    private ProgressBar progressSync;
    private AdbBridge adb;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean refreshing;
    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (!isResumed() || isHidden()) return;
            refreshHd();
            handler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_foto, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adb = AdbBridgeHolder.get(requireContext());
        textHdStatus = view.findViewById(R.id.textHdStatus);
        textLog = view.findViewById(R.id.textFotoLog);
        textSyncSummary = view.findViewById(R.id.textSyncSummary);
        textSyncCurrent = view.findViewById(R.id.textSyncCurrent);
        textSyncQueueEmpty = view.findViewById(R.id.textSyncQueueEmpty);
        progressSync = view.findViewById(R.id.progressSync);

        view.<Button>findViewById(R.id.btnHdList)
                .setOnClickListener(v -> send("cmd|hd|list"));
        view.<Button>findViewById(R.id.btnHdMount)
                .setOnClickListener(v -> send("cmd|hd|mount|"));
        view.<Button>findViewById(R.id.btnHdEject)
                .setOnClickListener(v -> send("cmd|hd|eject|"));
        view.<Button>findViewById(R.id.btnHdWake)
                .setOnClickListener(v -> send("cmd|hd|wake"));
        view.<Button>findViewById(R.id.btnSyncNow)
                .setOnClickListener(v -> send("cmd|sync|now"));
        view.<Button>findViewById(R.id.btnSyncPause)
                .setOnClickListener(v -> send("cmd|sync|pause"));
        view.<Button>findViewById(R.id.btnSyncResume)
                .setOnClickListener(v -> send("cmd|sync|resume"));
    }

    @Override
    public void onTabSelected() {
        startAutoRefresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adb != null && !isHidden()) {
            startAutoRefresh();
        }
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) handler.removeCallbacks(autoRefresh);
        else startAutoRefresh();
    }

    private void startAutoRefresh() {
        handler.removeCallbacks(autoRefresh);
        handler.post(autoRefresh);
    }

    private void refreshHd() {
        if (adb == null || !adb.isConnected()) {
            if (textHdStatus != null) {
                textHdStatus.setText(R.string.conn_status_fail);
            }
            return;
        }
        if (refreshing) return;
        refreshing = true;
        File dest = new File(requireContext().getCacheDir(), "remote.state.foto.json");
        adb.pullState(dest, (ok, msg) -> {
            refreshing = false;
            postUi(() -> renderState(ok, msg, dest));
        });
    }

    private void renderState(boolean ok, String msg, File dest) {
        if (!ok) {
            textHdStatus.setText(msg);
            return;
        }
        RemoteState state = RemoteState.parse(adb.readPulledText(dest));
        textHdStatus.setText(state.hdLine);
        renderSync(state.sync);
    }

    private void renderSync(@Nullable JSONObject sync) {
        if (sync == null) {
            textSyncSummary.setText(R.string.sync_queue_old_helper);
            textSyncCurrent.setText("");
            progressSync.setVisibility(View.GONE);
            textSyncQueueEmpty.setVisibility(View.GONE);
            return;
        }
        boolean running = sync.optBoolean("running", false);
        boolean paused = sync.optBoolean("paused", false);
        int total = sync.optInt("queueTotal", 0);
        int pending = sync.optInt("queuePending", 0);

        StringBuilder head = new StringBuilder();
        if (running) {
            head.append("In corso · ").append(phaseLabel(sync.optString("phase", "")));
        } else if (paused) {
            head.append("In pausa");
        } else {
            head.append("Inattiva");
        }
        if (total > 0) {
            head.append("\nCoda: ").append(total).append(" file · in attesa ").append(pending)
                    .append(" (").append(formatBytes(sync.optLong("queuePendingBytes", 0))).append(")")
                    .append(" · copiati ").append(sync.optInt("queueCopied", 0))
                    .append(" · già presenti ").append(sync.optInt("queueSkipped", 0));
            int failed = sync.optInt("queueFailed", 0);
            if (failed > 0) head.append(" · errori ").append(failed);
        }
        long next = sync.optLong("nextAutoAt", 0);
        if (!running && next > 0) {
            head.append("\nProssima automatica: ").append(formatTime(next));
        }
        String last = sync.optString("lastSync", "");
        if (!last.isEmpty()) head.append("\nUltima: ").append(last);
        textSyncSummary.setText(head);

        if (running) {
            progressSync.setVisibility(View.VISIBLE);
            progressSync.setProgress(sync.optInt("permille", 0));
            StringBuilder cur = new StringBuilder();
            String name = sync.optString("fileName", "");
            int idx = sync.optInt("fileIndex", 0);
            int tot = sync.optInt("fileTotal", 0);
            if (!name.isEmpty() && tot > 0) {
                cur.append(idx).append("/").append(tot).append("  ").append(name);
                long fileSize = sync.optLong("fileSize", 0);
                if (fileSize > 0) {
                    cur.append("\n").append(formatBytes(sync.optLong("fileBytes", 0)))
                            .append(" / ").append(formatBytes(fileSize));
                }
            } else {
                cur.append(sync.optString("detail", ""));
            }
            long totalBytes = sync.optLong("totalBytes", 0);
            if (totalBytes > 0) {
                cur.append("\nTotale ").append(formatBytes(sync.optLong("doneBytes", 0)))
                        .append(" / ").append(formatBytes(totalBytes));
            }
            long speed = sync.optLong("speedBps", 0);
            if (speed > 0) cur.append(" · ").append(formatBytes(speed)).append("/s");
            long eta = sync.optLong("etaMs", -1);
            if (eta > 0) cur.append(" · fine tra ").append(formatEta(eta));
            textSyncCurrent.setText(cur);
        } else {
            progressSync.setVisibility(View.GONE);
            String detail = sync.optString("detail", "");
            textSyncCurrent.setText(detail.equals(last) ? "" : detail);
        }

        textSyncQueueEmpty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
    }

    private static String phaseLabel(String phase) {
        switch (phase) {
            case "download": return "download";
            case "disk": return "scrittura su HD";
            case "verify": return "verifica";
            case "delete": return "cancellazione dal Vespera";
            case "list": return "lettura elenco";
            default: return phase;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ITALY, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }

    private static String formatEta(long ms) {
        long sec = Math.max(0, (ms + 500) / 1000);
        if (sec < 60) return sec + " s";
        long min = sec / 60;
        sec %= 60;
        if (min < 60) return min + " min " + sec + " s";
        return (min / 60) + " h " + (min % 60) + " min";
    }

    private static String formatTime(long ms) {
        return new SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(new Date(ms));
    }

    private void send(String line) {
        adb.sendLine(line, (ok, msg) -> postUi(() -> {
            textLog.setText(line + " → " + msg);
            Toast.makeText(requireContext(),
                    ok ? "Comando inviato" : "Errore",
                    Toast.LENGTH_SHORT).show();
            if (ok) {
                refreshHd();
            }
        }));
    }

    private void postUi(Runnable r) {
        if (!isAdded()) {
            return;
        }
        requireActivity().runOnUiThread(r);
    }
}
