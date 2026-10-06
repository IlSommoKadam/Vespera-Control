package com.vaonis.vesperacontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Parsing di {@code remote.state.json} allineato a Windows / Helper. */
public final class RemoteState {

    public final String wifiLine;
    public final String telLine;
    /** Righe "Etichetta: valore" del pannello Stato dell'Helper (memoria %, temperatura, fuoco…). */
    public final String telDetails;
    public final String hdLine;
    public final String tgLine;
    public final String appVersion;
    public final JSONObject system;
    public final JSONObject wifi;
    /** null se l'Helper non riporta lo stato Singularity. */
    public final JSONObject singularity;
    /** Sync foto e coda file (Helper ≥ 0.8.37); null con Helper più vecchi. */
    public final JSONObject sync;
    public final String raw;

    private RemoteState(
            String wifiLine, String telLine, String telDetails, String hdLine, String tgLine,
            String appVersion, JSONObject system, JSONObject wifi, JSONObject singularity, JSONObject sync,
            String raw) {
        this.wifiLine = wifiLine;
        this.telLine = telLine;
        this.telDetails = telDetails;
        this.hdLine = hdLine;
        this.tgLine = tgLine;
        this.appVersion = appVersion;
        this.system = system;
        this.wifi = wifi;
        this.singularity = singularity;
        this.sync = sync;
        this.raw = raw;
    }

    public static RemoteState parse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return empty("—");
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONObject wifi = root.optJSONObject("wifi");
            JSONObject tel = root.optJSONObject("telescope");
            JSONObject hd = root.optJSONObject("hd");
            JSONObject tg = root.optJSONObject("telegram");
            JSONObject sys = root.optJSONObject("system");

            String wifiLine = "—";
            if (wifi != null) {
                wifiLine = nullToDash(wifi.optString("status", "—"))
                        + "  ·  " + nullToDash(wifi.optString("ssid", "n/d"))
                        + "  ·  " + wifi.optString("bssid", "");
            }

            String telLine;
            String telDetailsText = "—";
            if (tel != null && tel.optBoolean("reachable", false)) {
                telLine = nullToDash(tel.optString("model", "Vespera"))
                        + "  ·  " + nullToDash(tel.optString("state", "—"))
                        + "  ·  " + nullToDash(tel.optString("observationStatus", "—"))
                        + "  ·  target " + nullToDash(tel.optString("targetName", "—"))
                        + "  ·  stack " + tel.optInt("stackingCount", 0)
                        + "  ·  batt " + tel.optInt("batteryPercent", -1) + "%";
                String details = telDetails(tel);
                if (!details.isEmpty()) {
                    // Le righe dettagliate contengono già modello, stato, target, stack e batteria.
                    telDetailsText = details;
                    telLine = "";
                }
            } else if (tel != null) {
                telLine = "Non raggiungibile (" + nullToDash(tel.optString("error", "—")) + ")";
            } else {
                telLine = "—";
            }

            String hdLine = "—";
            if (hd != null) {
                String space = hd.optString("spaceLabel", "");
                if (space.isEmpty() && hd.optBoolean("spaceKnown", false)) {
                    space = hd.opt("spacePercent") + "%";
                }
                String label = hd.optString("label", "");
                if (label.isEmpty()) label = hd.optString("uuid", "—");
                hdLine = (hd.optBoolean("mounted", false) ? "Montato" : "Spento/smontato")
                        + "  ·  " + nullToDash(label)
                        + "  ·  " + space;
            }

            String tgLine = "—";
            if (tg != null) {
                String conf = tg.optBoolean("configured", false) ? "Configurato" : "Non configurato";
                tgLine = conf
                        + "  ·  chat " + nullToDash(tg.optString("chatId", "—"))
                        + "  ·  err " + nullToDash(tg.optString("lastError", "—"));
            }

            return new RemoteState(
                    wifiLine.trim(),
                    telLine,
                    telDetailsText,
                    hdLine.trim(),
                    tgLine,
                    root.optString("appVersion", "?"),
                    sys == null ? new JSONObject() : sys,
                    wifi,
                    root.optJSONObject("singularity"),
                    root.optJSONObject("sync"),
                    body);
        } catch (Exception e) {
            return empty(body);
        }
    }

    /**
     * Righe "Etichetta: valore" come nel pannello Stato dell'Helper ({@code details}, Helper ≥ 0.8.36);
     * per Helper più vecchi le ricava dai singoli campi.
     */
    private static String telDetails(JSONObject tel) {
        StringBuilder sb = new StringBuilder();
        JSONArray rows = tel.optJSONArray("details");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null) addRow(sb, row.optString("label"), row.optString("value"));
            }
            return sb.toString();
        }
        addRow(sb, "Modello", tel.optString("model"));
        addRow(sb, "Stato", tel.optString("state"));
        addRow(sb, "Osservazione", tel.optString("observationStatus"));
        addRow(sb, "Operazione", tel.optString("operationType"));
        addRow(sb, "Passo", tel.optString("step"));
        addRow(sb, "Tracking", tel.optString("tracking"));
        addRow(sb, "Motori", tel.optString("motors"));
        addRow(sb, "Fuoco", tel.optString("focus"));
        addRow(sb, "Bersaglio", tel.optString("targetName"));
        addRow(sb, "Coordinate", tel.optString("coordinates"));
        addRow(sb, "Posizione", tel.optString("location"));
        int stack = tel.optInt("stackingCount", 0);
        if (stack > 0) addRow(sb, "Stack", String.valueOf(stack));
        long exp = tel.optLong("exposureMicroSec", 0);
        if (exp > 0) addRow(sb, "Esposizione", String.format(Locale.US, "%.1f s", exp / 1_000_000.0));
        int gain = tel.optInt("gain", 0);
        if (gain > 0) addRow(sb, "Gain", String.valueOf(gain));
        addRow(sb, "Filtro", tel.optString("filter"));
        addRow(sb, "Temperatura", tel.optString("temperature"));
        addRow(sb, "Firmware", tel.optString("firmware"));
        String storage = tel.optString("storage");
        int storagePct = tel.optInt("storageUsedPercent", -1);
        if (storage.isEmpty() && storagePct >= 0) storage = storagePct + "%";
        addRow(sb, "Foto interne", storage);
        int batt = tel.optInt("batteryPercent", -1);
        if (batt >= 0) {
            String status = tel.optString("batteryStatus");
            addRow(sb, "Batteria", batt + "%" + (status.isEmpty() ? "" : " (" + status + ")"));
        }
        return sb.toString();
    }

    private static void addRow(StringBuilder sb, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(label).append(": ").append(value.trim());
    }

    private static RemoteState empty(String raw) {
        return new RemoteState("—", "—", "—", "—", "—", "?", new JSONObject(), null, null, null, raw == null ? "" : raw);
    }

    private static String nullToDash(String v) {
        return v == null || v.isEmpty() ? "—" : v;
    }
}
