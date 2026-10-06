package com.vaonis.vesperacontrol;

import org.json.JSONObject;

import java.util.Map;

/** Flag eventi Telegram, gli stessi di Vespera Helper (stesso ordine del client Windows). */
public final class TelegramFlags {

    public static final String[] KEYS = {
            "initialized",
            "shutdown",
            "connected",
            "lost",
            "obsStarted",
            "obsStopped",
            "obsFinished",
            "error",
            "batteryLow",
            "batteryOffMains",
            "hdHigh",
            "storageInternalHigh",
            "sunTooHigh",
            "rainForecast"
    };

    /** Etichette, allineate a {@link #KEYS}. */
    public static final int[] LABELS = {
            R.string.tg_ev_initialized,
            R.string.tg_ev_shutdown,
            R.string.tg_ev_connected,
            R.string.tg_ev_lost,
            R.string.tg_ev_obs_started,
            R.string.tg_ev_obs_stopped,
            R.string.tg_ev_obs_finished,
            R.string.tg_ev_error,
            R.string.tg_ev_battery_low,
            R.string.tg_ev_battery_off_mains,
            R.string.tg_ev_hd_high,
            R.string.tg_ev_storage_internal_high,
            R.string.tg_ev_sun_too_high,
            R.string.tg_ev_rain_forecast
    };

    private TelegramFlags() {
    }

    /** Tutti gli eventi allo stesso valore. */
    public static String payload(boolean enabled) {
        try {
            JSONObject body = new JSONObject();
            body.put("enabled", enabled);
            for (String key : KEYS) {
                body.put(key, enabled);
            }
            return body.toString();
        } catch (Exception ignored) {
            return enabled ? "{\"enabled\":true}" : "{\"enabled\":false}";
        }
    }

    /** Generale + singoli eventi. */
    public static String payload(boolean enabled, Map<String, Boolean> events) {
        try {
            JSONObject body = new JSONObject();
            body.put("enabled", enabled);
            for (String key : KEYS) {
                Boolean v = events.get(key);
                body.put(key, v != null && v);
            }
            return body.toString();
        } catch (Exception ignored) {
            return payload(enabled);
        }
    }

    /** True se il Helper ha almeno un evento attivo, o il flag enabled. */
    public static boolean isEnabled(JSONObject telegram) {
        if (telegram == null) return false;
        if (telegram.has("enabled")) return telegram.optBoolean("enabled");
        for (String key : KEYS) {
            if (telegram.has(key) && telegram.optBoolean(key)) return true;
        }
        return false;
    }

    /** Valore del singolo evento; se il Helper non lo riporta segue il generale. */
    public static boolean isEventEnabled(JSONObject telegram, String key) {
        if (telegram == null) return false;
        if (telegram.has(key)) return telegram.optBoolean(key);
        return isEnabled(telegram);
    }
}
