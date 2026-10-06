package com.vaonis.vesperacontrol.adb;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.vaonis.vesperacontrol.DevicePrefs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ponte verso Vespera Helper (RemoteBridge) via ADB sulla porta TCP del Pi.
 * Path remoti sotto:
 * /sdcard/Android/data/com.vaonis.vesperahelper/files/
 *
 * Come il client Windows (che fa "adb connect" prima di ogni comando), ogni operazione
 * riapre da sola la connessione se è caduta (Wi-Fi in risparmio energetico, cambio rete,
 * socket chiuso dal Pi, timeout): l'utente non deve più premere Connetti dopo un cambio tab.
 */
public final class AdbBridge {

    public static final String REMOTE_FILES_DIR =
            "/sdcard/Android/data/com.vaonis.vesperahelper/files/";
    public static final String REMOTE_REQ = REMOTE_FILES_DIR + "remote.req";
    public static final String REMOTE_STATE = REMOTE_FILES_DIR + "remote.state.json";
    public static final String REMOTE_ACK = REMOTE_FILES_DIR + "remote.ack";

    private static final String TAG = "AdbBridge";

    public interface ResultCallback {
        void onResult(boolean ok, String message);
    }

    /** Notifica connessione aperta/chiusa (chiamata dal thread ADB). */
    public interface StateListener {
        void onAdbState(boolean linked, String target, String message);
    }

    private interface Op<T> {
        T run(AdbClient c) throws IOException;
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();

    private AdbClient client;
    private AdbKeys keys;
    private volatile String deviceSerial;
    private volatile String lastHost;
    private volatile int lastPort;
    private volatile boolean manualDisconnect;

    public AdbBridge(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Il telefono non usa un binario adb: la connessione è TCP verso il Pi. */
    public void setAdbPath(String path) {
    }

    public String getAdbPath() {
        return "";
    }

    public String getDeviceSerial() {
        return deviceSerial;
    }

    public void addStateListener(StateListener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeStateListener(StateListener l) {
        listeners.remove(l);
    }

    /**
     * Vero se si possono inviare comandi: connessione aperta oppure Pi noto
     * (riconnessione automatica alla prossima operazione).
     */
    public boolean isConnected() {
        return ready() || (!manualDisconnect && !TextUtils.isEmpty(targetHost()));
    }

    /** Vero solo se il socket ADB è aperto adesso. */
    public boolean isLinked() {
        return ready();
    }

    public void connect(String host, int port, ResultCallback callback) {
        executor.execute(() -> {
            manualDisconnect = false;
            lastHost = host;
            lastPort = port;
            try {
                openClient(host, port);
                deliver(callback, true, "Connesso a " + host + ":" + port);
            } catch (Exception e) {
                deliver(callback, false, connectError(host, port, e));
            }
        });
    }

    public void disconnect(ResultCallback callback) {
        executor.execute(() -> {
            manualDisconnect = true;
            closeClient("Disconnesso");
            deliver(callback, true, "Disconnesso");
        });
    }

    /** Riapre la connessione in background se è caduta (es. app tornata in primo piano). */
    public void ensureConnectedAsync(ResultCallback callback) {
        executor.execute(() -> {
            try {
                live();
                deliver(callback, true, deviceSerial);
            } catch (IOException e) {
                deliver(callback, false, e.getMessage());
            }
        });
    }

    /**
     * Scrive una riga protocollo RemoteBridge su remote.req
     * (es. {@code cmd|telescope|park}) e prova a leggere remote.ack.
     */
    public void sendCommand(String line, ResultCallback callback) {
        sendCommand(line, 14_000L, callback);
    }

    /**
     * Come {@link #sendCommand(String, ResultCallback)} ma attende l'ack fino a
     * {@code waitMs}: init/resume del telescopio possono durare minuti. Oltre i
     * 14 s un ack mancante è un errore (prima veniva dato come OK).
     */
    public void sendCommand(String line, long waitMs, ResultCallback callback) {
        final int rounds = (int) Math.max(1, waitMs / 350L);
        final boolean longWait = waitMs > 14_000L;
        executor.execute(() -> {
            String cmd = line == null ? "" : line.trim();
            if (cmd.isEmpty()) {
                deliver(callback, false, "Comando vuoto");
                return;
            }
            try {
                withClient(c -> {
                    try {
                        c.shell("rm -f '" + REMOTE_ACK + "'");
                    } catch (AdbClient.RemoteFail ignored) {
                    }
                    c.pushBytes(REMOTE_REQ, (cmd + "\n").getBytes(StandardCharsets.UTF_8));
                    return null;
                });
                String ack = "";
                for (int i = 0; i < rounds; i++) {
                    try {
                        Thread.sleep(350);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    byte[] raw;
                    try {
                        raw = withClient(c -> c.pullBytes(REMOTE_ACK));
                    } catch (AdbClient.RemoteFail notYet) {
                        continue;
                    }
                    ack = new String(raw, StandardCharsets.UTF_8).trim();
                    if (!ack.isEmpty()) {
                        deliver(callback, ack.startsWith("OK|"), ack);
                        return;
                    }
                }
                if (longWait && ack.isEmpty()) {
                    deliver(callback, false,
                            "Nessuna risposta dall'Helper sul Pi (è avviato?)");
                    return;
                }
                deliver(callback, true, ack.isEmpty()
                        ? "Comando inviato (nessun ack ancora). Helper ≥ 0.8.19?"
                        : ack);
            } catch (IOException e) {
                deliver(callback, false, failText(e, "Comando fallito"));
            }
        });
    }

    public void sendLine(String line, ResultCallback callback) {
        sendCommand(line, callback);
    }

    public void sendLine(String line, long waitMs, ResultCallback callback) {
        sendCommand(line, waitMs, callback);
    }

    /** @deprecated usare {@link #sendLine(String, ResultCallback)} con protocollo pipe. */
    @Deprecated
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
            try {
                byte[] data = withClient(c -> c.pullBytes(remotePath));
                File parent = localDest.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(localDest)) {
                    fos.write(data);
                }
                deliver(callback, true, remotePath);
            } catch (IOException e) {
                deliver(callback, false, failText(e, "Lettura fallita"));
            }
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
        closeClient(null);
    }

    // ---- interno (solo dal thread executor) ----

    /**
     * Esegue l'operazione sul client; se il trasporto cade (socket chiuso, timeout,
     * pacchetti fuori sequenza) riapre la connessione e riprova una volta.
     * Un FAIL del Pi (file mancante ecc.) non causa riconnessione.
     */
    private <T> T withClient(Op<T> op) throws IOException {
        AdbClient c = live();
        try {
            return op.run(c);
        } catch (AdbClient.RemoteFail e) {
            throw e;
        } catch (IOException first) {
            Log.w(TAG, "adb transport, riconnetto", first);
            closeClient(null);
            c = live();
            try {
                return op.run(c);
            } catch (AdbClient.RemoteFail e) {
                throw e;
            } catch (IOException second) {
                closeClient("Connessione ADB persa");
                throw new IOException("Connessione ADB persa: " + failText(second, "errore di rete"));
            }
        }
    }

    /** Client pronto; se serve riconnette a ultimo host o a IP/porta salvati. */
    private AdbClient live() throws IOException {
        if (ready()) return client;
        if (manualDisconnect) {
            throw new IOException("Disconnesso. Premi Connetti ADB.");
        }
        String host = targetHost();
        if (TextUtils.isEmpty(host)) {
            throw new IOException("IP del Pi mancante (Impostazioni).");
        }
        int port = targetPort();
        try {
            openClient(host, port);
        } catch (Exception e) {
            throw new IOException(connectError(host, port, e));
        }
        return client;
    }

    private void openClient(String host, int port) throws Exception {
        closeClient(null);
        AdbClient opened = new AdbClient();
        try {
            opened.connect(host, port, keys());
        } catch (Exception e) {
            opened.close();
            fire(false, host + ":" + port, connectError(host, port, e));
            throw e;
        }
        client = opened;
        deviceSerial = host + ":" + port;
        lastHost = host;
        lastPort = port;
        fire(true, deviceSerial, null);
    }

    private String targetHost() {
        if (!TextUtils.isEmpty(lastHost)) return lastHost;
        return DevicePrefs.getIp(appContext);
    }

    private int targetPort() {
        if (!TextUtils.isEmpty(lastHost) && lastPort > 0) return lastPort;
        return DevicePrefs.getAdbPort(appContext);
    }

    private boolean ready() {
        AdbClient c = client;
        return c != null && c.isConnected() && !TextUtils.isEmpty(deviceSerial);
    }

    private AdbKeys keys() throws Exception {
        if (keys == null) {
            keys = AdbKeys.load(appContext);
        }
        return keys;
    }

    private void closeClient(String reason) {
        boolean was = client != null;
        String target = deviceSerial;
        if (client != null) {
            client.close();
            client = null;
        }
        deviceSerial = null;
        if (was && reason != null) {
            fire(false, target, reason);
        }
    }

    private void fire(boolean linked, String target, String message) {
        for (StateListener l : listeners) {
            try {
                l.onAdbState(linked, target, message);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener", e);
            }
        }
    }

    private static String failText(IOException e, String fallback) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? fallback : m;
    }

    private static String connectError(String host, int port, Exception e) {
        Log.w(TAG, "connect", e);
        if (e instanceof ConnectException) {
            return "Porta " + port + " chiusa su " + host + ". Sul Pi serve ADB in rete (5555).";
        }
        if (e instanceof UnknownHostException) {
            return "IP non valido: " + host;
        }
        if (e instanceof SocketTimeoutException) {
            return "Nessuna risposta da " + host + ":" + port + ". Stessa rete del Pi?";
        }
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return "Connessione a " + host + ":" + port + " fallita";
        }
        String low = msg.toLowerCase();
        if (low.contains("connection refused") || low.contains("failed to connect")) {
            return "Porta " + port + " chiusa su " + host + ". Sul Pi serve ADB in rete (5555).";
        }
        if (low.contains("timed out") || low.contains("timeout")) {
            return "Nessuna risposta da " + host + ":" + port + ". Stessa rete del Pi?";
        }
        return msg;
    }

    private static void deliver(ResultCallback callback, boolean ok, String message) {
        if (callback != null) {
            callback.onResult(ok, message);
        }
    }
}
