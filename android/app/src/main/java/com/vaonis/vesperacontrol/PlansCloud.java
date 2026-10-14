package com.vaonis.vesperacontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.vaonis.vesperacontrol.adb.AdbBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Piani salvati condivisi con Vespera Control Windows, in due copie: shared.plans.json sul Pi
 * (via ADB) e un documento fissato nella chat del bot Telegram dell'Helper, per lavorare anche
 * a Pi spento. Il documento si legge con getChat → pinned_message → getFile e si riscrive
 * sostituendo il file dello stesso messaggio (editMessageMedia); se non si può si manda e si
 * fissa. L'unione è {@link Favorites#mergeShared}. Come plans_share.py su Windows.
 */
public final class PlansCloud {

    private static final String TAG = "VesperaPlans";
    private static final String PREFS = "vespera_device";
    private static final String KEY_TOKEN = "plans_tg_token";
    private static final String KEY_CHAT = "plans_tg_chat";
    private static final String KEY_STATUS = "plans_sync_status";
    private static final String FILE = "vespera-plans.json";
    private static final String API = "https://api.telegram.org";
    private static final String CAPTION = "Piani di Vespera Control (non cancellare e non togliere dai fissati)";

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static boolean askedHelper;
    private static boolean running;

    private PlansCloud() {
    }

    public static String token(Context c) {
        return prefs(c).getString(KEY_TOKEN, "");
    }

    public static String chat(Context c) {
        return prefs(c).getString(KEY_CHAT, "");
    }

    public static String status(Context c) {
        return prefs(c).getString(KEY_STATUS, "Piani condivisi: non ancora allineati");
    }

    public static void setBot(Context c, String token, String chat) {
        prefs(c).edit().putString(KEY_TOKEN, token == null ? "" : token.trim())
                .putString(KEY_CHAT, chat == null ? "" : chat.trim()).apply();
    }

    /** Legge token e chat id del bot dall'Helper ({@code get|telegram}); null se non c'è. */
    public static void fetchFromHelper(Context context, AdbBridge adb, AdbBridge.ResultCallback done) {
        Context app = context.getApplicationContext();
        adb.sendCommand("get|telegram", 20_000L, (ok, msg) -> {
            String prefix = "OK|telegram|";
            if (!ok || msg == null || !msg.startsWith(prefix)) {
                done.onResult(false, msg);
                return;
            }
            try {
                JSONObject o = new JSONObject(msg.substring(prefix.length()));
                String token = o.optString("token", "");
                if (token.isEmpty() || "***".equals(token)) {
                    done.onResult(false, "L'Helper non ha il bot Telegram impostato");
                    return;
                }
                setBot(app, token, o.optString("chatId", ""));
                done.onResult(true, "Bot preso dall'Helper");
            } catch (Exception e) {
                done.onResult(false, String.valueOf(e.getMessage()));
            }
        });
    }

    /**
     * Allinea i piani: prima col Pi (se ADB è connesso), poi col bot Telegram (se impostato).
     * {@code done} arriva su un thread di lavoro a fine giro.
     */
    public static void sync(Context context, AdbBridge adb, Runnable done) {
        Context app = context.getApplicationContext();
        synchronized (PlansCloud.class) {
            if (running) return;
            running = true;
        }
        List<String> ok = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Runnable telegram = () -> NET.execute(() -> {
            try {
                if (token(app).isEmpty() || chat(app).isEmpty()) {
                    errors.add("Telegram: bot non impostato");
                } else {
                    syncTelegram(app);
                    ok.add("Telegram");
                }
            } catch (Exception e) {
                errors.add("Telegram: " + e.getMessage());
            }
            String time = new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date());
            String text = ok.isEmpty()
                    ? "Piani non allineati (" + time + "): " + String.join("; ", errors)
                    : "Piani allineati alle " + time + " con: " + String.join(", ", ok)
                    + (errors.isEmpty() ? "" : "  ·  non riuscito: " + String.join("; ", errors));
            prefs(app).edit().putString(KEY_STATUS, text).apply();
            if (!errors.isEmpty()) Log.w(TAG, text);
            synchronized (PlansCloud.class) {
                running = false;
            }
            if (done != null) done.run();
        });
        if (adb == null || !adb.isConnected()) {
            errors.add("Pi: non connesso");
            telegram.run();
            return;
        }
        adb.mergeRemote(AdbBridge.REMOTE_SHARED_PLANS, remote -> Favorites.mergeShared(app, remote),
                (piOk, msg) -> {
                    if (piOk) ok.add("Pi");
                    else errors.add("Pi: " + msg);
                    boolean ask;
                    synchronized (PlansCloud.class) {
                        ask = piOk && token(app).isEmpty() && !askedHelper;
                        askedHelper = askedHelper || ask;
                    }
                    if (ask) { // prima volta: il bot lo prende dall'Helper
                        fetchFromHelper(app, adb, (got, why) -> telegram.run());
                    } else {
                        telegram.run();
                    }
                });
    }

    private static void syncTelegram(Context app) throws Exception {
        String token = token(app);
        String chat = chat(app);
        JSONObject info = call(token, "getChat", new String[][]{{"chat_id", chat}}, null, null);
        JSONObject pinned = info.optJSONObject("pinned_message");
        JSONObject doc = pinned == null ? null : pinned.optJSONObject("document");
        String remote = "";
        long messageId = -1;
        if (doc != null && FILE.equals(doc.optString("file_name"))) {
            messageId = pinned.optLong("message_id");
            JSONObject file = call(token, "getFile", new String[][]{{"file_id", doc.optString("file_id")}}, null, null);
            remote = new String(read(open(API + "/file/bot" + token + "/" + file.optString("file_path"))),
                    StandardCharsets.UTF_8);
        }
        String merged = Favorites.mergeShared(app, remote);
        if (messageId >= 0 && sameContent(remote, merged)) return; // niente di nuovo
        byte[] data = merged.getBytes(StandardCharsets.UTF_8);
        if (messageId >= 0) {
            try {
                String media = new JSONObject().put("type", "document").put("media", "attach://plans")
                        .put("caption", CAPTION).toString();
                call(token, "editMessageMedia", new String[][]{{"chat_id", chat},
                        {"message_id", String.valueOf(messageId)}, {"media", media}}, "plans", data);
                return;
            } catch (IOException notEditable) {
                Log.i(TAG, "editMessageMedia: " + notEditable.getMessage() + ", ne mando uno nuovo");
            }
        }
        JSONObject sent = call(token, "sendDocument", new String[][]{{"chat_id", chat}, {"caption", CAPTION},
                {"disable_notification", "true"}}, "document", data);
        call(token, "pinChatMessage", new String[][]{{"chat_id", chat},
                {"message_id", String.valueOf(sent.optLong("message_id"))}, {"disable_notification", "true"}},
                null, null);
    }

    /** Stessi piani e stesse cancellazioni (l'ordine delle chiavi JSON non conta). */
    private static boolean sameContent(String remote, String merged) {
        try {
            JSONObject a = new JSONObject(remote);
            JSONObject b = new JSONObject(merged);
            return canonical(a.optJSONArray("plans")).equals(canonical(b.optJSONArray("plans")))
                    && canonical(a.optJSONArray("deleted")).equals(canonical(b.optJSONArray("deleted")));
        } catch (Exception e) {
            return false;
        }
    }

    private static String canonical(Object value) {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
            java.util.Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (String k : keys) sb.append(JSONObject.quote(k)).append(':').append(canonical(o.opt(k))).append(',');
            return sb.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) sb.append(canonical(a.opt(i))).append(',');
            return sb.append(']').toString();
        }
        if (value instanceof Number) return String.valueOf(((Number) value).doubleValue());
        return value == null ? "null" : JSONObject.quote(String.valueOf(value));
    }

    /** Chiamata alla Bot API; con {@code fileField} manda anche il file dei piani (multipart). */
    private static JSONObject call(String token, String method, String[][] fields,
                                   String fileField, byte[] data) throws IOException {
        HttpURLConnection conn = open(API + "/bot" + token + "/" + method);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        byte[] body;
        if (fileField == null) {
            StringBuilder sb = new StringBuilder();
            for (String[] f : fields) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(f[0], "UTF-8")).append('=').append(URLEncoder.encode(f[1], "UTF-8"));
            }
            body = sb.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        } else {
            String boundary = UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (String[] f : fields) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n"
                        + f[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + FILE + "\"\r\nContent-Type: application/json\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            body = out.toByteArray();
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        conn.disconnect();
        JSONObject res;
        try {
            res = new JSONObject(text);
        } catch (Exception e) {
            throw new IOException("Telegram " + method + ": HTTP " + code);
        }
        if (!res.optBoolean("ok")) {
            throw new IOException("Telegram " + method + ": " + res.optString("description", "errore"));
        }
        JSONObject result = res.optJSONObject("result");
        return result == null ? new JSONObject() : result;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(25_000);
        return conn;
    }

    private static byte[] read(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream()) {
            return readAll(in);
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
