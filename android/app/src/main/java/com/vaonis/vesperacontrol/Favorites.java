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
    /** Cancellazioni [{created, at}] da propagare a Windows (shared.plans.json sul Pi). */
    private static final String PLANS_DELETED = "plans_deleted";
    private static final int MAX_PLANS = 30;
    private static final long KEEP_DELETED_MS = 90L * 24 * 3600 * 1000;

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
        try {
            plan.put("modified", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
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
        JSONArray gone = deleted(context);
        try {
            gone.put(new JSONObject().put("created", created).put("at", System.currentTimeMillis()));
        } catch (Exception ignored) {
        }
        prefs(context).edit().putString(PLANS, arr.toString()).putString(PLANS_DELETED, gone.toString()).apply();
    }

    private static JSONArray deleted(Context context) {
        try {
            return new JSONArray(prefs(context).getString(PLANS_DELETED, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static long stamp(JSONObject plan) {
        long modified = plan.optLong("modified", 0);
        return modified > 0 ? modified : plan.optLong("created");
    }

    /**
     * Unisce i piani del telefono con quelli di shared.plans.json (testo letto dal Pi, anche
     * vuoto): per lo stesso "created" vince il "modified" più recente, un piano cancellato sparisce
     * ovunque a meno che non sia risalvato dopo. Salva il risultato sul telefono e ritorna il
     * testo da riscrivere sul Pi. Come plans_share.merge su Windows.
     */
    public static synchronized String mergeShared(Context context, String remoteText) throws Exception {
        JSONObject remote = new JSONObject();
        if (remoteText != null && !remoteText.trim().isEmpty()) {
            try {
                remote = new JSONObject(remoteText);
            } catch (Exception ignored) {
                // file rovinato: lo si riscrive da capo
            }
        }
        java.util.Map<Long, JSONObject> byId = new java.util.HashMap<>();
        java.util.Map<Long, Long> gone = new java.util.TreeMap<>();
        JSONArray[] deletedLists = {deleted(context), remote.optJSONArray("deleted")};
        for (JSONArray list : deletedLists) {
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject d = list.optJSONObject(i);
                if (d == null || !d.has("created")) continue;
                long c = d.optLong("created");
                gone.put(c, Math.max(gone.containsKey(c) ? gone.get(c) : 0L, d.optLong("at")));
            }
        }
        List<JSONObject> all = new ArrayList<>(plans(context));
        JSONArray remotePlans = remote.optJSONArray("plans");
        for (int i = 0; remotePlans != null && i < remotePlans.length(); i++) {
            JSONObject p = remotePlans.optJSONObject(i);
            if (p != null && p.optJSONArray("steps") != null) all.add(p);
        }
        for (JSONObject p : all) {
            long c = p.optLong("created");
            if (c == 0) continue;
            JSONObject old = byId.get(c);
            if (old == null || stamp(p) > stamp(old)) byId.put(c, p);
        }
        long cutoff = System.currentTimeMillis() - KEEP_DELETED_MS;
        gone.values().removeIf(at -> at < cutoff);
        List<JSONObject> alive = new ArrayList<>();
        for (JSONObject p : byId.values()) {
            Long at = gone.get(p.optLong("created"));
            if (at == null || stamp(p) > at) alive.add(p);
        }
        java.util.Collections.sort(alive, (a, b) -> Long.compare(stamp(b), stamp(a)));
        JSONArray plans = new JSONArray();
        for (int i = 0; i < alive.size() && i < MAX_PLANS; i++) plans.put(alive.get(i));
        JSONArray deleted = new JSONArray();
        for (java.util.Map.Entry<Long, Long> e : gone.entrySet()) {
            deleted.put(new JSONObject().put("created", e.getKey()).put("at", e.getValue()));
        }
        prefs(context).edit().putString(PLANS, plans.toString()).putString(PLANS_DELETED, deleted.toString()).commit();
        return new JSONObject().put("version", 1).put("plans", plans).put("deleted", deleted).toString();
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
