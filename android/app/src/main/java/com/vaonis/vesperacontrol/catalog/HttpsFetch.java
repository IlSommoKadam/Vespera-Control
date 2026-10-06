package com.vaonis.vesperacontrol.catalog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTPS GET semplice (cataloghi CDS / hips2fits), senza HostDns Helper. */
public final class HttpsFetch {

    public static final class Result {
        public final int code;
        public final String body;
        public final byte[] bytes;

        Result(int code, String body, byte[] bytes) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }
    }

    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_BYTES = 2_500_000;

    private HttpsFetch() {
    }

    public static Result get(String host, String pathAndQuery) throws Exception {
        return get(host, pathAndQuery, "application/json,text/*,*/*");
    }

    public static byte[] getBytes(String host, String pathAndQuery) throws Exception {
        Result result = get(host, pathAndQuery, "image/jpeg,image/*,*/*");
        if (result.code < 200 || result.code >= 300 || result.bytes.length == 0) {
            throw new Exception("HTTP " + result.code);
        }
        if (result.bytes.length > MAX_BYTES) {
            throw new Exception("image too large");
        }
        return result.bytes;
    }

    private static Result get(String host, String pathAndQuery, String accept) throws Exception {
        String path = pathAndQuery == null ? "/" : pathAndQuery;
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        URL url = new URL("https://" + host + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", accept);
            conn.setRequestProperty("User-Agent", "VesperaControl/1.0");
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] bytes = readLimited(in, MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new Exception("response too large");
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            return new Result(code, body, bytes);
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readLimited(InputStream in, int limit) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > limit) {
                out.write(buf, 0, n);
                break;
            }
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }
}
