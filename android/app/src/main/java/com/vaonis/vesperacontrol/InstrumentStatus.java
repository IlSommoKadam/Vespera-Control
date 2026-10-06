package com.vaonis.vesperacontrol;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Barre di stato Wi-Fi Vespera e Singularity, come nella UI di Vespera Helper
 * (stessi testi e colori; gemello Windows: instrument_status.py).
 */
public final class InstrumentStatus {

    public static final int STEEL = 0xFF8A97A3;
    public static final int AMBER = 0xFFC9A227;
    public static final int STEEL_BLUE = 0xFF5A7A92;
    public static final int GREEN = 0xFF3B7F55;

    public static final class Bar {
        public final String text;
        public final int color;

        Bar(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private InstrumentStatus() {
    }

    public static Bar vespera(JSONObject wifi) {
        if (wifi == null) return new Bar("—", STEEL);
        String status = wifi.optString("status", "");
        String model = nonEmpty(wifi.optString("model", ""), "Vespera");
        String ssid = wifi.optString("ssid", "");
        String bssid = wifi.optString("bssid", "");
        int freq = wifi.optInt("scanFreq", wifi.optInt("freq", 0));
        boolean connected = "CONNECTED".equals(status) && wifi.optBoolean("hasNetwork", true);
        if (status.startsWith("REQUESTING")) {
            return new Bar("Connessione…\nRichiesta rete " + model + " / " + ssid, STEEL_BLUE);
        }
        if (!wifi.optBoolean("configured", !ssid.isEmpty())) {
            return new Bar("○ Nessuno strumento salvato\nSceglilo dalla scansione sul Pi", STEEL);
        }
        String signal = "";
        if (wifi.has("level")) {
            signal = wifi.optInt("level") + " dBm (" + wifi.optInt("bars") + "/5) · ";
        }
        String tail = bssid + " · " + signal + (freq > 0 ? freq + " MHz" : "");
        tail = tail.replaceAll("[ ·]+$", "");
        if (connected) return new Bar("✓ " + model + "\n" + ssid + "\n" + tail, GREEN);
        if (wifi.optBoolean("online", false)) return new Bar("● " + model + "\n" + ssid + "\n" + tail, AMBER);
        return new Bar("○ " + model + "\n" + ssid + "\n" + bssid + " · non online (non in scansione)", STEEL);
    }

    /** Riga "Stato Singularity: …". */
    public static String singularityInfo(JSONObject sing) {
        if (sing == null) return "Stato Singularity: —";
        String code = sing.optString("status", "IDLE");
        String info;
        switch (code) {
            case "IDLE": info = "non verificabile (Wi‑Fi Vespera assente)"; break;
            case "CHECKING": info = "controllo in corso"; break;
            case "RECOVERING": info = "recupero in corso"; break;
            case "STARTING": info = "avvio in corso"; break;
            case "CONNECTED": info = "CONNESSO allo strumento"; break;
            case "NOT_RUNNING": info = "non in esecuzione"; break;
            case "API_DOWN": info = "API Vespera non raggiungibile"; break;
            case "NO_WIFI": info = "Wi‑Fi non associata al Vespera"; break;
            case "DAEMON_MISSING": info = "daemon vespera-netd assente"; break;
            case "DISCONNECTED":
            case "UNKNOWN": info = "non rileva lo strumento"; break;
            default: info = code;
        }
        return "Stato Singularity: " + info;
    }

    public static Bar singularity(JSONObject sing) {
        if (sing == null) return new Bar("○ Singularity\nStato non disponibile (Helper datato)", STEEL);
        switch (sing.optString("status", "IDLE")) {
            case "IDLE": return new Bar("○ Singularity\nConnetti al Vespera per verificare", STEEL);
            case "CHECKING": return new Bar("Controllo…\nRilevazione strumento in Singularity", STEEL_BLUE);
            case "RECOVERING": return new Bar("Recupero…\nAggiorno route / riavvio Singularity", STEEL_BLUE);
            case "STARTING": return new Bar("Avvio…\nApro Singularity", STEEL_BLUE);
            case "CONNECTED": return new Bar("✓ Singularity\nConnesso allo strumento", GREEN);
            case "NOT_RUNNING": return new Bar("● Singularity\nNon in esecuzione", AMBER);
            case "API_DOWN": return new Bar("● Singularity\nAPI Vespera non raggiungibile", AMBER);
            case "NO_WIFI": return new Bar("○ Singularity\nWi‑Fi non associata al Vespera", STEEL);
            case "DAEMON_MISSING": return new Bar("○ Singularity\nDaemon vespera-netd assente", STEEL);
            default: return new Bar("● Singularity\nNon rileva lo strumento", AMBER);
        }
    }

    public static void paint(TextView view, Bar bar) {
        if (view == null || bar == null) return;
        float d = view.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bar.color);
        bg.setCornerRadius(6 * d);
        bg.setStroke(Math.max(1, Math.round(d)), darker(bar.color));
        view.setBackground(bg);
        view.setGravity(Gravity.CENTER);
        view.setText(bar.text);
        view.setTextColor(textOn(bar.color));
        int h = Math.round(12 * d);
        int v = Math.round(8 * d);
        view.setPadding(h, v, h, v);
    }

    public static int textOn(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return lum > 0.62 ? 0xFF212121 : 0xFFFFFFFF;
    }

    private static int darker(int c) {
        int r = (int) (((c >> 16) & 0xFF) * 0.8);
        int g = (int) (((c >> 8) & 0xFF) * 0.8);
        int b = (int) ((c & 0xFF) * 0.8);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String nonEmpty(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }
}
