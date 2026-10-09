package com.vaonis.vesperacontrol;

import android.content.Context;

import org.json.JSONObject;

/** Coordinate sito lette dallo stato telescopio del Helper. */
public final class ObservatorySite {

    private ObservatorySite() {
    }

    /** Salva la posizione Vespera solo se l'utente non ne ha messa una a mano o via GPS. */
    public static void captureIfUnset(Context context, JSONObject telescope) {
        if (context == null) return;
        rememberHelper(context, telescope);
        String source = DevicePrefs.getSiteSource(context);
        if (DevicePrefs.SITE_MANUAL.equals(source) || DevicePrefs.SITE_GPS.equals(source)) return;
        double[] site = parse(telescope);
        if (site == null) return;
        DevicePrefs.setSite(context, site[0], site[1], DevicePrefs.SITE_VESPERA);
    }

    // ------------------------------------------------------------------ sito dell'Helper per il footer meteo

    private static final String HELPER_PREFS = "vespera_helper_site";

    /** Nome e ore della notte del sito impostato nell'Helper (≥ 0.8.48 per le ore). */
    static void rememberHelper(Context context, JSONObject telescope) {
        double[] site = parse(telescope);
        if (site == null) return;
        android.content.SharedPreferences.Editor e = helperPrefs(context).edit()
                .putString("lat", String.valueOf(site[0]))
                .putString("lon", String.valueOf(site[1]))
                .putString("label", telescope.optString("siteLabel", "").trim());
        if (telescope.has("nightStartHour") && telescope.has("nightEndHour")) {
            e.putInt("nightStart", telescope.optInt("nightStartHour", -1))
                    .putInt("nightEnd", telescope.optInt("nightEndHour", -1));
        } else {
            e.remove("nightStart").remove("nightEnd");
        }
        e.apply();
    }

    /** Vale solo se il sito dell'app è lo stesso posto dell'Helper (entro ~10 km). */
    private static boolean sameAsHelper(Context context, double lat, double lon) {
        android.content.SharedPreferences p = helperPrefs(context);
        double hLat = DevicePrefs.parseCoord(p.getString("lat", ""));
        double hLon = DevicePrefs.parseCoord(p.getString("lon", ""));
        return !Double.isNaN(hLat) && !Double.isNaN(hLon)
                && Math.abs(hLat - lat) < 0.1 && Math.abs(hLon - lon) < 0.1;
    }

    static String helperLabel(Context context, double lat, double lon) {
        if (!sameAsHelper(context, lat, lon)) return "";
        String label = helperPrefs(context).getString("label", "");
        return label == null ? "" : label;
    }

    /** Nome del paese già trovato per queste coordinate (reverse geocoding), o "". */
    static String cachedTown(Context context, double lat, double lon) {
        android.content.SharedPreferences p = helperPrefs(context);
        if (!townKey(lat, lon).equals(p.getString("townKey", ""))) return "";
        String town = p.getString("town", "");
        return town == null ? "" : town;
    }

    /** Cerca il nome del paese/città (BigDataCloud, senza chiave). Da thread di lavoro. */
    static void resolveTown(Context context, double lat, double lon) {
        if (!cachedTown(context, lat, lon).isEmpty()) return;
        try {
            com.vaonis.vesperacontrol.catalog.HttpsFetch.Result r =
                    com.vaonis.vesperacontrol.catalog.HttpsFetch.get("api.bigdatacloud.net",
                            String.format(java.util.Locale.US,
                                    "/data/reverse-geocode-client?latitude=%.5f&longitude=%.5f"
                                            + "&localityLanguage=it", lat, lon));
            if (r.code < 200 || r.code >= 300) return;
            JSONObject o = new JSONObject(r.body);
            String town = o.optString("city", "").trim();
            if (town.isEmpty()) town = o.optString("locality", "").trim();
            if (town.isEmpty()) return;
            helperPrefs(context).edit().putString("townKey", townKey(lat, lon)).putString("town", town).apply();
        } catch (Exception ignored) {
        }
    }

    private static String townKey(double lat, double lon) {
        return String.format(java.util.Locale.US, "%.3f,%.3f", lat, lon);
    }

    /** {inizio notte, alba} come nell'Helper, o null (default 19 → 10). */
    static int[] helperNightHours(Context context, double lat, double lon) {
        if (!sameAsHelper(context, lat, lon)) return null;
        android.content.SharedPreferences p = helperPrefs(context);
        int start = p.getInt("nightStart", -1);
        int end = p.getInt("nightEnd", -1);
        if (start < 0 || start > 23 || end < 0 || end > 23) return null;
        return new int[]{start, end};
    }

    static void registerHelperListener(Context context,
            android.content.SharedPreferences.OnSharedPreferenceChangeListener l) {
        helperPrefs(context).registerOnSharedPreferenceChangeListener(l);
    }

    static void unregisterHelperListener(Context context,
            android.content.SharedPreferences.OnSharedPreferenceChangeListener l) {
        helperPrefs(context).unregisterOnSharedPreferenceChangeListener(l);
    }

    private static android.content.SharedPreferences helperPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(HELPER_PREFS, Context.MODE_PRIVATE);
    }

    public static double[] parse(JSONObject telescope) {
        if (telescope == null) return null;
        double[] direct = fromObject(telescope);
        if (direct != null) return direct;
        Object location = telescope.opt("location");
        if (location instanceof JSONObject) return fromObject((JSONObject) location);
        if (location instanceof String) return fromText((String) location);
        return null;
    }

    private static double[] fromObject(JSONObject obj) {
        double lat = first(obj, "latitude", "lat", "gpsLatitude");
        double lon = first(obj, "longitude", "lon", "lng", "gpsLongitude");
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
        if (Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01) return null;
        return new double[]{lat, lon};
    }

    private static double first(JSONObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key) || obj.isNull(key)) continue;
            double value = obj.optDouble(key, Double.NaN);
            if (!Double.isNaN(value)) return value;
            try {
                return Double.parseDouble(obj.optString(key, "").trim().replace(',', '.'));
            } catch (NumberFormatException ignored) {
            }
        }
        return Double.NaN;
    }

    /** "45.0700, 7.6800" oppure "Osservatorio 45.07, 7.68". */
    static double[] fromText(String text) {
        if (text == null) return null;
        String raw = text.trim();
        if (raw.isEmpty()) return null;
        int comma = raw.lastIndexOf(',');
        if (comma <= 0 || comma >= raw.length() - 1) return null;
        String lonPart = raw.substring(comma + 1).trim();
        String before = raw.substring(0, comma).trim();
        int sep = Math.max(before.lastIndexOf(' '), before.lastIndexOf('\u00b7'));
        String latPart = sep >= 0 ? before.substring(sep + 1).trim() : before;
        double lat = DevicePrefs.parseCoord(latPart);
        double lon = DevicePrefs.parseCoord(lonPart);
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
        if (Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01) return null;
        return new double[]{lat, lon};
    }
}
