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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
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
            "(?i)^(\\d{4})-(\\d{2})-(\\d{2})(?:_(\\d{2})-(\\d{2})-(\\d{2}))?"
                    + ".{0,48}?(?:observation|acquisition)[_\\s-]+(.+)$");

    public static final class Item {
        public final String label;
        public final String folder;
        /** Firma dell'ultimo *-output.jpg (path + ora): cambia quando la cartella si aggiorna. */
        public final String signature;
        /** Contrassegno mostrato in lista ("nuovo", "in aggiornamento"), vuoto se invariato. */
        public String mark = "";

        public Item(String label, String folder) {
            this(label, folder, "");
        }

        public Item(String label, String folder, String signature) {
            this.label = label;
            this.folder = folder;
            this.signature = signature == null ? "" : signature;
        }

        @Override public String toString() {
            return mark == null || mark.isEmpty() ? label : label + "  \u00b7  " + mark;
        }
    }

    /** Callback progresso download (0–100). Può essere chiamato da thread worker. */
    public interface ProgressListener {
        void onProgress(int percent);
    }

    /** Bitmap + nome file remoto dell'ultimo *-output.jpg. */
    public static final class Preview {
        public final Bitmap bitmap;
        public final String fileName;
        public final String remotePath;

        public Preview(Bitmap bitmap, String fileName, String remotePath) {
            this.bitmap = bitmap;
            this.fileName = fileName;
            this.remotePath = remotePath;
        }
    }

    private static final class LatestOutput {
        final String path;
        final long timeMs;

        LatestOutput(String path, long timeMs) {
            this.path = path;
            this.timeMs = timeMs;
        }
    }

    /** Estrae target e data/ora dal nome cartella Vespera (creazione osservazione). */
    static String[] observationParts(String name) {
        if (name == null) return new String[] {"", ""};
        java.util.regex.Matcher m = OBS.matcher(name);
        if (!m.matches()) return new String[] {"", ""};
        String year = m.group(1);
        String month = m.group(2);
        String day = m.group(3);
        String hour = m.group(4);
        String minute = m.group(5);
        String target = m.group(7) == null ? "" : m.group(7).replace('_', ' ').trim();
        String when;
        if (hour != null && minute != null) {
            when = day + "/" + month + "/" + year + " " + hour + ":" + minute;
        } else {
            when = day + "/" + month + "/" + year;
        }
        return new String[] {target, when};
    }

    private static String formatWhen(long timeMs) {
        if (timeMs <= 0L) return "";
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY);
        return fmt.format(new Date(timeMs));
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
            List<Item> scored = new ArrayList<>();
            List<Long> sortKeys = new ArrayList<>();
            for (FTPFile f : files) {
                if (f == null || !f.isDirectory()) continue;
                String name = f.getName();
                if (".".equals(name) || "..".equals(name)) continue;
                String[] parts = observationParts(name);
                String folder = base.endsWith("/") ? base + name : base + "/" + name;
                LatestOutput latest = findLatestOutput(ftp, folder, 0);
                long folderTime = f.getTimestamp() == null ? 0L : f.getTimestamp().getTimeInMillis();
                String when;
                long sortTime;
                if (latest != null && latest.timeMs > 0L) {
                    when = formatWhen(latest.timeMs);
                    sortTime = latest.timeMs;
                } else if (folderTime > 0L) {
                    when = formatWhen(folderTime);
                    sortTime = folderTime;
                } else {
                    when = parts[1];
                    sortTime = 0L;
                }
                String label = name;
                if (!parts[0].isEmpty()) {
                    label = parts[0];
                    if (!when.isEmpty()) {
                        label = label + " · " + when;
                    }
                } else if (!when.isEmpty()) {
                    label = name + " · " + when;
                }
                String signature = latest != null
                        ? latest.path + "@" + latest.timeMs
                        : "dir@" + folderTime;
                scored.add(new Item(label, folder, signature));
                sortKeys.add(sortTime);
                if (scored.size() >= 80) break;
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < scored.size(); i++) order.add(i);
            Collections.sort(order, new Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) {
                    int byTime = Long.compare(sortKeys.get(b), sortKeys.get(a));
                    if (byTime != 0) return byTime;
                    return scored.get(a).label.compareToIgnoreCase(scored.get(b).label);
                }
            });
            for (int idx : order) out.add(scored.get(idx));
            return out;
        } finally {
            disconnectQuietly(ftp);
        }
    }

    public static Bitmap loadLatestOutput(String host, int port, String folder) throws IOException {
        return loadLatestPreview(host, port, folder).bitmap;
    }

    public static Preview loadLatestPreview(String host, int port, String folder) throws IOException {
        return loadLatestPreview(host, port, folder, null);
    }

    public static Preview loadLatestPreview(
            String host, int port, String folder, ProgressListener progress) throws IOException {
        FTPClient ftp = connect(host, port);
        try {
            LatestOutput best = findLatestOutput(ftp, folder, 0);
            if (best == null) {
                throw new IOException("Nessun *-output.jpg in " + folder);
            }
            long knownSize = sizeOf(ftp, best.path);
            byte[] data = download(ftp, best.path, knownSize, progress);
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bmp == null) throw new IOException("JPEG non decodificabile: " + best.path);
            int slash = best.path.lastIndexOf('/');
            String name = slash >= 0 ? best.path.substring(slash + 1) : best.path;
            return new Preview(bmp, name, best.path);
        } finally {
            disconnectQuietly(ftp);
        }
    }

    private static LatestOutput findLatestOutput(FTPClient ftp, String path, int depth)
            throws IOException {
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
            LatestOutput hit = findLatestOutput(ftp, dir, depth + 1);
            if (hit == null) continue;
            if (hit.timeMs >= bestTime) {
                bestTime = hit.timeMs;
                bestFile = hit.path;
            }
        }
        return bestFile == null ? null : new LatestOutput(bestFile, Math.max(bestTime, 0L));
    }

    private static long sizeOf(FTPClient ftp, String remote) throws IOException {
        int slash = remote.lastIndexOf('/');
        String dir = slash >= 0 ? remote.substring(0, slash) : "/";
        String name = slash >= 0 ? remote.substring(slash + 1) : remote;
        cwd(ftp, dir);
        FTPFile[] files = ftp.listFiles(name);
        if (files == null) return -1L;
        for (FTPFile f : files) {
            if (f != null && name.equals(f.getName())) {
                return Math.max(-1L, f.getSize());
            }
        }
        return -1L;
    }

    private static byte[] download(
            FTPClient ftp, String remote, long knownSize, ProgressListener progress)
            throws IOException {
        int slash = remote.lastIndexOf('/');
        String dir = slash >= 0 ? remote.substring(0, slash) : "/";
        String name = slash >= 0 ? remote.substring(slash + 1) : remote;
        cwd(ftp, dir);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (progress != null) progress.onProgress(0);
        try (InputStream in = ftp.retrieveFileStream(name)) {
            if (in == null) throw new IOException("RETR fallito: " + remote);
            byte[] buf = new byte[16_384];
            int n;
            long read = 0L;
            int lastPct = -1;
            while ((n = in.read(buf)) >= 0) {
                bos.write(buf, 0, n);
                read += n;
                if (progress != null) {
                    int pct;
                    if (knownSize > 0L) {
                        pct = (int) Math.min(99L, (read * 100L) / knownSize);
                    } else {
                        // Senza size: stima soft ogni ~256 KB.
                        pct = (int) Math.min(90L, read / 2_621L);
                    }
                    if (pct != lastPct) {
                        lastPct = pct;
                        progress.onProgress(pct);
                    }
                }
            }
        }
        if (!ftp.completePendingCommand()) {
            throw new IOException("RETR incompleto: " + remote);
        }
        if (progress != null) progress.onProgress(100);
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
