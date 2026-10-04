package com.vaonis.vesperacontrol.ftp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Anteprima FTP (stile StarStacKadam): elenco USER + ultimo *-output.jpg.
 * Nessuno stacking.
 */
public final class FtpPreview {
    private static final Pattern OUTPUT = Pattern.compile("(?i).*?-output\\.jpe?g$");
    private static final Pattern OBS = Pattern.compile(
            "(?i)^(\\d{4}-\\d{2}-\\d{2}).{0,48}?(?:observation|acquisition)[_\\s-]+(.+)$");

    public static final class Item {
        public final String label;
        public final String folder;

        public Item(String label, String folder) {
            this.label = label;
            this.folder = folder;
        }

        @Override public String toString() {
            return label;
        }
    }

    private FtpPreview() {}

    public static List<Item> listObjects(String host, int port) throws IOException {
        FTPClient ftp = connect(host, port);
        try {
            List<Item> out = new ArrayList<>();
            String user = findUserDir(ftp);
            String base = user == null ? "/" : "/" + user;
            cwd(ftp, base);
            FTPFile[] files = ftp.listFiles();
            if (files == null) return out;
            for (FTPFile f : files) {
                if (f == null || !f.isDirectory()) continue;
                String name = f.getName();
                if (".".equals(name) || "..".equals(name)) continue;
                String label = name;
                java.util.regex.Matcher m = OBS.matcher(name);
                if (m.matches()) {
                    label = m.group(2).replace('_', ' ').trim() + " · " + m.group(1);
                }
                String folder = base.endsWith("/") ? base + name : base + "/" + name;
                out.add(new Item(label, folder));
                if (out.size() >= 80) break;
            }
            return out;
        } finally {
            disconnectQuietly(ftp);
        }
    }

    public static Bitmap loadLatestOutput(String host, int port, String folder) throws IOException {
        FTPClient ftp = connect(host, port);
        try {
            String best = findLatestOutput(ftp, folder, 0);
            if (best == null) {
                throw new IOException("Nessun *-output.jpg in " + folder);
            }
            byte[] data = download(ftp, best);
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bmp == null) throw new IOException("JPEG non decodificabile: " + best);
            return bmp;
        } finally {
            disconnectQuietly(ftp);
        }
    }

    private static String findLatestOutput(FTPClient ftp, String path, int depth) throws IOException {
        if (depth > 5) return null;
        cwd(ftp, path);
        FTPFile[] files = ftp.listFiles();
        if (files == null) return null;
        String bestFile = null;
        long bestTime = -1;
        List<String> dirs = new ArrayList<>();
        for (FTPFile f : files) {
            if (f == null) continue;
            String name = f.getName();
            if (".".equals(name) || "..".equals(name)) continue;
            String remote = path.endsWith("/") ? path + name : path + "/" + name;
            if (f.isDirectory()) {
                String low = name.toLowerCase(Locale.US);
                if (low.contains("dark") || low.contains("expert")) continue;
                dirs.add(remote);
                continue;
            }
            if (!OUTPUT.matcher(name).matches()) continue;
            long t = f.getTimestamp() == null ? 0L : f.getTimestamp().getTimeInMillis();
            if (t >= bestTime) {
                bestTime = t;
                bestFile = remote;
            }
        }
        for (String dir : dirs) {
            String hit = findLatestOutput(ftp, dir, depth + 1);
            if (hit != null && bestFile == null) bestFile = hit;
        }
        return bestFile;
    }

    private static byte[] download(FTPClient ftp, String remote) throws IOException {
        int slash = remote.lastIndexOf('/');
        String dir = slash >= 0 ? remote.substring(0, slash) : "/";
        String name = slash >= 0 ? remote.substring(slash + 1) : remote;
        cwd(ftp, dir);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = ftp.retrieveFileStream(name)) {
            if (in == null) throw new IOException("RETR fallito: " + remote);
            byte[] buf = new byte[16_384];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
        }
        if (!ftp.completePendingCommand()) {
            throw new IOException("RETR incompleto: " + remote);
        }
        return bos.toByteArray();
    }

    private static String findUserDir(FTPClient ftp) throws IOException {
        cwd(ftp, "/");
        FTPFile[] files = ftp.listFiles();
        if (files == null) return null;
        for (FTPFile f : files) {
            if (f != null && f.isDirectory() && "user".equalsIgnoreCase(f.getName())) {
                return f.getName();
            }
        }
        return null;
    }

    private static void cwd(FTPClient ftp, String path) throws IOException {
        String target = (path == null || path.isEmpty()) ? "/" : path;
        if (!ftp.changeWorkingDirectory(target)) {
            throw new IOException("CWD fallito: " + target);
        }
    }

    private static FTPClient connect(String host, int port) throws IOException {
        if (host == null || host.trim().isEmpty()) throw new IOException("Host FTP vuoto");
        FTPClient ftp = new FTPClient();
        InetAddress address = InetAddress.getByName(host.trim());
        String dataHost = address.getHostAddress();
        ftp.setConnectTimeout(12_000);
        ftp.setDefaultTimeout(12_000);
        ftp.setDataTimeout(java.time.Duration.ofSeconds(45));
        ftp.setUseEPSVwithIPv4(false);
        ftp.setRemoteVerificationEnabled(false);
        ftp.setPassiveNatWorkaroundStrategy(hostname -> dataHost);
        ftp.connect(address, port);
        int reply = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply) && reply != 230) {
            disconnectQuietly(ftp);
            throw new IOException("FTP rifiutato: " + ftp.getReplyString());
        }
        ftp.enterLocalPassiveMode();
        boolean logged = ftp.login("anonymous", "anonymous@");
        if (!logged) {
            logged = ftp.login("anonymous", "");
        }
        if (!logged) {
            disconnectQuietly(ftp);
            throw new IOException("Login FTP fallito");
        }
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        return ftp;
    }

    private static void disconnectQuietly(FTPClient ftp) {
        if (ftp == null) return;
        try {
            if (ftp.isConnected()) ftp.disconnect();
        } catch (Exception ignored) {
        }
    }
}
