package com.vaonis.vesperacontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** Preferenze device / porte, in tab Impostazioni. */
public final class DevicePrefs {

    private static final String PREFS = "vespera_device";
    private static final String KEY_IP = "device_ip";
    private static final String KEY_ADB_PORT = "adb_port";
    private static final String KEY_FTP_HD = "ftp_hd_port";
    private static final String KEY_FTP_VESP = "ftp_vespera_port";
    private static final String KEY_FTP_SOURCE = "ftp_source";
    private static final String KEY_SCRCPY_PORT = "scrcpy_adb_port";
    private static final String KEY_SCRCPY_BITRATE = "scrcpy_bitrate_mbps";
    private static final String KEY_LAT = "site_lat";
    private static final String KEY_LON = "site_lon";
    private static final String KEY_SITE_SOURCE = "site_source";

    public static final String SITE_MANUAL = "manual";
    public static final String SITE_GPS = "gps";
    public static final String SITE_VESPERA = "vespera";

    public static final int DEFAULT_ADB_PORT = 5555;
    public static final int DEFAULT_FTP_HD = 2121;
    public static final int DEFAULT_FTP_VESP = 2122;
    public static final String SOURCE_HD = "hd";
    public static final String SOURCE_VESP = "vespera";

    private DevicePrefs() {
    }

    public static String getIp(Context context) {
        return prefs(context).getString(KEY_IP, "");
    }

    public static void setIp(Context context, String ip) {
        prefs(context).edit().putString(KEY_IP, ip == null ? "" : ip.trim()).apply();
    }

    public static int getAdbPort(Context context) {
        int port = prefs(context).getInt(KEY_ADB_PORT, DEFAULT_ADB_PORT);
        return validPort(port) ? port : DEFAULT_ADB_PORT;
    }

    public static void setAdbPort(Context context, int port) {
        prefs(context).edit().putInt(KEY_ADB_PORT, validPort(port) ? port : DEFAULT_ADB_PORT).apply();
    }

    public static void setAdbPort(Context context, String portText) {
        setAdbPort(context, parsePort(portText, DEFAULT_ADB_PORT));
    }

    public static int getFtpHdPort(Context context) {
        int port = prefs(context).getInt(KEY_FTP_HD, DEFAULT_FTP_HD);
        return validPort(port) ? port : DEFAULT_FTP_HD;
    }

    public static void setFtpHdPort(Context context, String portText) {
        prefs(context).edit().putInt(KEY_FTP_HD, parsePort(portText, DEFAULT_FTP_HD)).apply();
    }

    public static int getFtpVesperaPort(Context context) {
        int port = prefs(context).getInt(KEY_FTP_VESP, DEFAULT_FTP_VESP);
        return validPort(port) ? port : DEFAULT_FTP_VESP;
    }

    public static void setFtpVesperaPort(Context context, String portText) {
        prefs(context).edit().putInt(KEY_FTP_VESP, parsePort(portText, DEFAULT_FTP_VESP)).apply();
    }

    public static String getFtpSource(Context context) {
        String s = prefs(context).getString(KEY_FTP_SOURCE, SOURCE_HD);
        return SOURCE_VESP.equals(s) ? SOURCE_VESP : SOURCE_HD;
    }

    public static void setFtpSource(Context context, String source) {
        prefs(context).edit()
                .putString(KEY_FTP_SOURCE, SOURCE_VESP.equals(source) ? SOURCE_VESP : SOURCE_HD)
                .apply();
    }

    public static int getFtpPort(Context context) {
        return SOURCE_VESP.equals(getFtpSource(context))
                ? getFtpVesperaPort(context)
                : getFtpHdPort(context);
    }

    public static int getScrcpyAdbPort(Context context) {
        int port = prefs(context).getInt(KEY_SCRCPY_PORT, DEFAULT_ADB_PORT);
        return validPort(port) ? port : DEFAULT_ADB_PORT;
    }

    public static void setScrcpyAdbPort(Context context, String portText) {
        prefs(context).edit().putInt(KEY_SCRCPY_PORT, parsePort(portText, DEFAULT_ADB_PORT)).apply();
    }

    public static int getScrcpyBitrateMbps(Context context) {
        int v = prefs(context).getInt(KEY_SCRCPY_BITRATE, 8);
        return v > 0 && v <= 100 ? v : 8;
    }

    public static void setScrcpyBitrateMbps(Context context, String text) {
        prefs(context).edit().putInt(KEY_SCRCPY_BITRATE, parsePort(text, 8)).apply();
    }

    public static boolean hasSite(Context context) {
        return !Double.isNaN(getLat(context)) && !Double.isNaN(getLon(context));
    }

    public static double getLat(Context context) {
        return readCoord(context, KEY_LAT);
    }

    public static double getLon(Context context) {
        return readCoord(context, KEY_LON);
    }

    public static String getSiteSource(Context context) {
        String source = prefs(context).getString(KEY_SITE_SOURCE, "");
        return source == null ? "" : source;
    }

    public static void setSite(Context context, double lat, double lon, String source) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return;
        if (Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01) return;
        String kept = SITE_GPS.equals(source) || SITE_VESPERA.equals(source) ? source : SITE_MANUAL;
        prefs(context).edit()
                .putString(KEY_LAT, String.format(java.util.Locale.US, "%.6f", lat))
                .putString(KEY_LON, String.format(java.util.Locale.US, "%.6f", lon))
                .putString(KEY_SITE_SOURCE, kept)
                .apply();
    }

    /** Accetta 45.07 e 45,07. NaN se il testo non è una coordinata. */
    public static double parseCoord(String text) {
        if (text == null) return Double.NaN;
        String raw = text.trim().replace(',', '.');
        if (raw.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double readCoord(Context context, String key) {
        String raw = prefs(context).getString(key, "");
        if (raw == null || raw.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static int parsePort(String text, int fallback) {
        try {
            int port = Integer.parseInt(text == null ? "" : text.trim());
            return validPort(port) ? port : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean validPort(int port) {
        return port > 0 && port <= 65535;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
