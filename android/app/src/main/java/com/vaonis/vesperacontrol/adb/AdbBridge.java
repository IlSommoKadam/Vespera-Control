package com.vaonis.vesperacontrol.adb;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Ponte ADB verso Vespera Helper (RemoteBridge).
 * Path remoti sotto:
 * /sdcard/Android/data/com.vaonis.vesperahelper/files/
 *
 * Il binario adb può essere:
 * - un path assoluto configurabile
 * - oppure un placeholder in assets/adb/ (da estrarre a filesDir)
 */
public final class AdbBridge {

    public static final String REMOTE_FILES_DIR =
            "/sdcard/Android/data/com.vaonis.vesperahelper/files/";
    public static final String REMOTE_REQ = REMOTE_FILES_DIR + "remote.req";
    public static final String REMOTE_STATE = REMOTE_FILES_DIR + "remote.state.json";
    public static final String REMOTE_ACK = REMOTE_FILES_DIR + "remote.ack";

    private static final String TAG = "AdbBridge";
    private static final String ASSETS_ADB_PLACEHOLDER = "adb/README.txt";

    public interface ResultCallback {
        void onResult(boolean ok, String message);
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String adbPath;
    private String deviceSerial; // host:port dopo connect

    public AdbBridge(Context context) {
        this.appContext = context.getApplicationContext();
        this.adbPath = resolveDefaultAdbPath();
    }

    public void setAdbPath(String path) {
        if (!TextUtils.isEmpty(path)) {
            this.adbPath = path.trim();
        }
    }

    public String getAdbPath() {
        return adbPath;
    }

    public String getDeviceSerial() {
        return deviceSerial;
    }

    /** connect host:port via adb connect */
    public void connect(String host, int port, ResultCallback callback) {
        executor.execute(() -> {
            String serial = host + ":" + port;
            ExecResult result = runAdb("connect", serial);
            boolean ok = result.exitCode == 0
                    && (result.stdout.toLowerCase().contains("connected")
                    || result.stdout.toLowerCase().contains("already"));
            if (ok) {
                deviceSerial = serial;
            }
            deliver(callback, ok, result.combined());
        });
    }

    public void disconnect(ResultCallback callback) {
        executor.execute(() -> {
            ExecResult result;
            if (!TextUtils.isEmpty(deviceSerial)) {
                result = runAdb("disconnect", deviceSerial);
            } else {
                result = runAdb("disconnect");
            }
            deviceSerial = null;
            deliver(callback, result.exitCode == 0, result.combined());
        });
    }

    /**
     * Scrive una riga protocollo RemoteBridge su remote.req
     * (es. {@code cmd|telescope|park}) e prova a leggere remote.ack.
     */
    public void sendCommand(String line, ResultCallback callback) {
        executor.execute(() -> {
            if (TextUtils.isEmpty(deviceSerial)) {
                deliver(callback, false, "Device non connesso. Usa Connetti prima.");
                return;
            }
            String cmd = line == null ? "" : line.trim();
            if (cmd.isEmpty()) {
                deliver(callback, false, "Comando vuoto");
                return;
            }
            runAdb("-s", deviceSerial, "shell", "rm", "-f", REMOTE_ACK);
            String escaped = cmd.replace("'", "'\\''");
            String shellCmd = "printf '%s\\n' '" + escaped + "' > " + REMOTE_REQ;
            ExecResult write = runAdb("-s", deviceSerial, "shell", shellCmd);
            if (write.exitCode != 0) {
                deliver(callback, false, write.combined());
                return;
            }
            File ackFile = new File(appContext.getCacheDir(), "remote.ack");
            String ack = "";
            for (int i = 0; i < 40; i++) {
                try {
                    Thread.sleep(350);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                ExecResult pull = runAdb(
                        "-s", deviceSerial,
                        "pull",
                        REMOTE_ACK,
                        ackFile.getAbsolutePath()
                );
                if (pull.exitCode == 0 && ackFile.isFile()) {
                    ack = readPulledText(ackFile).trim();
                    if (!ack.isEmpty()) {
                        boolean ok = ack.startsWith("OK|");
                        deliver(callback, ok, ack);
                        return;
                    }
                }
            }
            deliver(callback, true, "Comando inviato (nessun ack ancora). Helper ≥ 0.6.95?");
        });
    }

    /** Alias: invia la riga protocollo così com'è. */
    public void sendLine(String line, ResultCallback callback) {
        sendCommand(line, callback);
    }

    /** @deprecated usare {@link #sendLine(String, ResultCallback)} con protocollo pipe. */
    public void sendNamedCommand(String cmd, ResultCallback callback) {
        sendLine(cmd, callback);
    }

    public void pullState(File localDest, ResultCallback callback) {
        pullRemote(REMOTE_STATE, localDest, callback);
    }

    public void pullAck(File localDest, ResultCallback callback) {
        pullRemote(REMOTE_ACK, localDest, callback);
    }

    public void pullRemote(String remotePath, File localDest, ResultCallback callback) {
        executor.execute(() -> {
            if (TextUtils.isEmpty(deviceSerial)) {
                deliver(callback, false, "Device non connesso.");
                return;
            }
            File parent = localDest.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            ExecResult result = runAdb(
                    "-s", deviceSerial,
                    "pull",
                    remotePath,
                    localDest.getAbsolutePath()
            );
            deliver(callback, result.exitCode == 0, result.combined());
        });
    }

    public String readPulledText(File localFile) {
        if (localFile == null || !localFile.exists()) {
            return "";
        }
        try (InputStream in = new java.io.FileInputStream(localFile);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            Log.w(TAG, "readPulledText", e);
            return "";
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private String resolveDefaultAdbPath() {
        // Preferenza: binario già estratto in filesDir/adb/adb
        File extracted = new File(appContext.getFilesDir(), "adb/adb");
        if (extracted.exists() && extracted.canExecute()) {
            return extracted.getAbsolutePath();
        }
        // Placeholder assets: documenta dove mettere il binario
        ensureAssetsPlaceholder();
        // Fallback path tipici (device host / emulatore con adb nel PATH di Runtime)
        return "adb";
    }

    private void ensureAssetsPlaceholder() {
        File marker = new File(appContext.getFilesDir(), "adb/PLACEHOLDER");
        if (marker.exists()) {
            return;
        }
        try {
            File dir = marker.getParentFile();
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            try (InputStream in = appContext.getAssets().open(ASSETS_ADB_PLACEHOLDER)) {
                // solo verifica presenza asset; il binario va fornito a parte
                in.read();
            }
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
        } catch (IOException e) {
            Log.i(TAG, "Asset adb placeholder assente o non leggibile: " + e.getMessage());
        }
    }

    private ExecResult runAdb(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(adbPath);
        for (String arg : args) {
            cmd.add(arg);
        }
        Log.d(TAG, "exec: " + cmd);
        Process process = null;
        try {
            // Runtime.exec sul binario adb (path configurabile / assets placeholder)
            process = Runtime.getRuntime().exec(cmd.toArray(new String[0]));
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            int code = finished ? process.exitValue() : -1;
            if (!finished) {
                process.destroyForcibly();
                stderr = (stderr + "\ntimeout").trim();
            }
            return new ExecResult(code, stdout, stderr);
        } catch (Exception e) {
            Log.e(TAG, "runAdb failed", e);
            return new ExecResult(-1, "", e.getMessage() == null ? "error" : e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static void deliver(ResultCallback callback, boolean ok, String message) {
        if (callback != null) {
            callback.onResult(ok, message);
        }
    }

    private static final class ExecResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        ExecResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        String combined() {
            if (stderr.isEmpty()) {
                return stdout;
            }
            if (stdout.isEmpty()) {
                return stderr;
            }
            return stdout + "\n" + stderr;
        }
    }
}
