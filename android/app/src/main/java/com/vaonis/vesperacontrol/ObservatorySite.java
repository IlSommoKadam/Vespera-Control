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
        String source = DevicePrefs.getSiteSource(context);
        if (DevicePrefs.SITE_MANUAL.equals(source) || DevicePrefs.SITE_GPS.equals(source)) return;
        double[] site = parse(telescope);
        if (site == null) return;
        DevicePrefs.setSite(context, site[0], site[1], DevicePrefs.SITE_VESPERA);
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
