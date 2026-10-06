package com.vaonis.vesperacontrol;

import android.content.Context;
import android.content.SharedPreferences;

import com.vaonis.vesperacontrol.catalog.NightSky;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Oggetti preferiti (nome, tipo, RA, Dec) salvati sul telefono. Come "favorites" in settings.json su Windows. */
public final class Favorites {

    private static final String PREFS = "vespera_device";
    private static final String KEY = "favorites";
    private static final String PLANS = "plans";

    private Favorites() {
    }

    public static List<NightSky.Item> load(Context context) {
        List<NightSky.Item> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(context).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                double ra = o.optDouble("ra", Double.NaN);
                double dec = o.optDouble("dec", Double.NaN);
                if (name.isEmpty() || Double.isNaN(ra) || Double.isNaN(dec)) continue;
                out.add(new NightSky.Item(name, o.optString("type", ""), ra, dec));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static boolean contains(Context context, String name) {
        String key = key(name);
        for (NightSky.Item item : load(context)) {
            if (key(item.name).equals(key)) return true;
        }
        return false;
    }

    /** Aggiunge o toglie; ritorna true se ora è nei preferiti. */
    public static boolean toggle(Context context, String name, String type, double ra, double dec) {
        List<NightSky.Item> items = load(context);
        String key = key(name);
        for (int i = 0; i < items.size(); i++) {
            if (key(items.get(i).name).equals(key)) {
                items.remove(i);
                save(context, items);
                return false;
            }
        }
        items.add(new NightSky.Item(name, type, ra, dec));
        save(context, items);
        return true;
    }

    private static void save(Context context, List<NightSky.Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (NightSky.Item item : items) {
                JSONObject o = new JSONObject();
                o.put("name", item.name);
                o.put("type", item.typeCode);
                o.put("ra", item.raDeg);
                o.put("dec", item.decDeg);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply();
    }

    // ------------------------------------------------------------------ Piani salvati

    /** Piani salvati, il più recente per primo. */
    public static List<JSONObject> plans(Context context) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(context).getString(PLANS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optJSONArray("steps") != null) out.add(o);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Salva (o sostituisce, stesso "created") un piano in testa alla lista; max 30. */
    public static void savePlan(Context context, JSONObject plan) {
        List<JSONObject> list = plans(context);
        long created = plan.optLong("created");
        JSONArray arr = new JSONArray();
        arr.put(plan);
        for (JSONObject o : list) {
            if (o.optLong("created") == created) continue;
            if (arr.length() >= 30) break;
            arr.put(o);
        }
        prefs(context).edit().putString(PLANS, arr.toString()).apply();
    }

    public static void deletePlan(Context context, long created) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : plans(context)) {
            if (o.optLong("created") != created) arr.put(o);
        }
        prefs(context).edit().putString(PLANS, arr.toString()).apply();
    }

    public static String keyOf(String name) {
        return key(name);
    }

    private static String key(String name) {
        return name == null ? "" : name.replaceAll("[\\s_-]", "").toUpperCase(Locale.US);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
