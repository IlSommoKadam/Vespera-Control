package com.vaonis.vesperacontrol.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Client ADB TCP verso adbd (porta 5555), senza il binario {@code adb}.
 */
final class AdbClient implements Closeable {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_AUTH = 0x48545541;

    private static final int A_VERSION = 0x01000001;
    private static final int MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int maxData = 4096;
    private int nextId = 1;
    private int streamLocal;
    private int streamRemote;
    private final ByteArrayOutputStream inbox = new ByteArrayOutputStream();
    private int inboxRead;

    void connect(String host, int port, AdbKeys keys) throws IOException {
        close();
        Socket opened = new Socket();
        boolean sentKey = false;
        try {
            opened.connect(new InetSocketAddress(host, port), 8_000);
            opened.setTcpNoDelay(true);
            opened.setKeepAlive(true);
            opened.setSoTimeout(12_000);
            socket = opened;
            in = opened.getInputStream();
            out = opened.getOutputStream();
            send(A_CNXN, A_VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            while (true) {
                Message msg = readPacket();
                if (msg.command == A_CNXN) {
                    if (msg.arg1 > 0) {
                        maxData = Math.min(MAX_DATA, msg.arg1);
                    }
                    opened.setSoTimeout(15_000);
                    return;
                }
                if (msg.command == A_AUTH && msg.arg0 == AUTH_TOKEN) {
                    if (!signed) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, keys.sign(msg.data));
                        signed = true;
                        continue;
                    }
                    if (!sentKey) {
                        opened.setSoTimeout(90_000);
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, keys.publicKeyPacket());
                        sentKey = true;
                        continue;
                    }
                    continue;
                }
                throw new IOException("Handshake ADB rifiutato");
            }
        } catch (SocketTimeoutException e) {
            close();
            if (sentKey) {
                throw new IOException(
                        "Sul Pi tocca Consenti debug USB per Vespera Control, poi premi Connetti di nuovo."
                );
            }
            throw new IOException("Nessuna risposta da " + host + ":" + port);
        } catch (IOException e) {
            close();
            throw e;
        } catch (Exception e) {
            close();
            throw new IOException(e.getMessage() == null ? "Handshake ADB fallito" : e.getMessage());
        }
    }

    boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    String shell(String command) throws IOException {
        open("shell:" + command);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            while (true) {
                Message msg = readPacket();
                if (msg.command == A_WRTE && msg.arg1 == streamLocal) {
                    body.write(msg.data);
                    send(A_OKAY, streamLocal, msg.arg0, new byte[0]);
                } else if (msg.command == A_OKAY && msg.arg1 == streamLocal) {
                    continue;
                } else if (msg.command == A_CLSE && msg.arg1 == streamLocal) {
                    streamRemote = 0;
                    return body.toString(StandardCharsets.UTF_8);
                } else if (msg.arg1 != streamLocal && isStreamPacket(msg)) {
                    // Pacchetto ritardato di un canale precedente: ignora.
                    continue;
                } else {
                    throw new IOException("Shell interrotta");
                }
            }
        } finally {
            if (streamRemote != 0) {
                closeStream();
            }
        }
    }

    void pushBytes(String remotePath, byte[] data) throws IOException {
        byte[] body = data == null ? new byte[0] : data;
        open("sync:");
        try {
            byte[] spec = (remotePath + ",33188").getBytes(StandardCharsets.UTF_8);
            writePayload(syncRequest("SEND", spec));
            int off = 0;
            while (off < body.length) {
                int n = Math.min(64 * 1024, body.length - off);
                ByteArrayOutputStream chunk = new ByteArrayOutputStream(n + 8);
                chunk.write("DATA".getBytes(StandardCharsets.US_ASCII));
                chunk.write(leInt(n));
                chunk.write(body, off, n);
                writePayload(chunk.toByteArray());
                off += n;
            }
            ByteArrayOutputStream done = new ByteArrayOutputStream(8);
            done.write("DONE".getBytes(StandardCharsets.US_ASCII));
            done.write(leInt((int) (System.currentTimeMillis() / 1000L)));
            writePayload(done.toByteArray());
            SyncHeader header = readSync();
            if ("FAIL".equals(header.id)) {
                throw new RemoteFail(readFail(header));
            }
            if (!"OKAY".equals(header.id)) {
                throw new IOException("Push fallito: " + header.id);
            }
            writePayload(syncRequest("QUIT", new byte[0]));
        } finally {
            closeStream();
        }
    }

    byte[] pullBytes(String remotePath) throws IOException {
        open("sync:");
        try {
            writePayload(syncRequest("RECV", remotePath.getBytes(StandardCharsets.UTF_8)));
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            while (true) {
                SyncHeader header = readSync();
                if ("DATA".equals(header.id)) {
                    if (header.value < 0 || header.value > 8 * 1024 * 1024) {
                        throw new IOException("Blocco sync troppo grande");
                    }
                    file.write(readInbox(header.value));
                } else if ("DONE".equals(header.id)) {
                    try {
                        writePayload(syncRequest("QUIT", new byte[0]));
                    } catch (IOException ignored) {
                    }
                    return file.toByteArray();
                } else if ("FAIL".equals(header.id)) {
                    throw new RemoteFail(readFail(header));
                } else {
                    throw new IOException("Sync inatteso: " + header.id);
                }
            }
        } finally {
            closeStream();
        }
    }

    @Override
    public void close() {
        Socket current = socket;
        socket = null;
        in = null;
        out = null;
        streamLocal = 0;
        streamRemote = 0;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void open(String destination) throws IOException {
        ensureLive();
        inbox.reset();
        inboxRead = 0;
        streamLocal = nextId++;
        streamRemote = 0;
        send(A_OPEN, streamLocal, 0, (destination + "\0").getBytes(StandardCharsets.UTF_8));
        while (true) {
            Message msg = readPacket();
            if (msg.command == A_OKAY && msg.arg1 == streamLocal) {
                streamRemote = msg.arg0;
                return;
            }
            if (msg.command == A_CLSE && msg.arg1 == streamLocal) {
                streamLocal = 0;
                throw new IOException("Servizio ADB rifiutato");
            }
            if (isStreamPacket(msg)) {
                // Residuo di un canale già chiuso (CLSE/OKAY/WRTE arrivati in ritardo).
                if (msg.command == A_WRTE) {
                    send(A_CLSE, msg.arg1, msg.arg0, new byte[0]);
                }
                continue;
            }
            throw new IOException("Apertura ADB fallita");
        }
    }

    private void closeStream() {
        int local = streamLocal;
        int remote = streamRemote;
        streamLocal = 0;
        streamRemote = 0;
        if (local == 0 || out == null || socket == null) {
            return;
        }
        try {
            send(A_CLSE, local, remote, new byte[0]);
            int previous = socket.getSoTimeout();
            socket.setSoTimeout(400);
            try {
                Message msg = readPacket();
                if (msg.command == A_WRTE) {
                    send(A_OKAY, local, msg.arg0, new byte[0]);
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    socket.setSoTimeout(previous);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void writePayload(byte[] data) throws IOException {
        int off = 0;
        int limit = Math.max(1024, maxData);
        if (data.length == 0) {
            send(A_WRTE, streamLocal, streamRemote, new byte[0]);
            waitOkay();
            return;
        }
        while (off < data.length) {
            int n = Math.min(limit, data.length - off);
            send(A_WRTE, streamLocal, streamRemote, Arrays.copyOfRange(data, off, off + n));
            waitOkay();
            off += n;
        }
    }

    private void waitOkay() throws IOException {
        while (true) {
            Message msg = readPacket();
            if (msg.command == A_OKAY && msg.arg1 == streamLocal) {
                return;
            }
            if (msg.command == A_WRTE && msg.arg1 == streamLocal) {
                if (msg.data.length > 0) {
                    inbox.write(msg.data);
                }
                send(A_OKAY, streamLocal, msg.arg0, new byte[0]);
                continue;
            }
            if (msg.command == A_CLSE && msg.arg1 == streamLocal) {
                throw new EOFException("Canale ADB chiuso");
            }
            if (msg.arg1 != streamLocal && isStreamPacket(msg)) {
                continue;
            }
            throw new IOException("ACK ADB mancante");
        }
    }

    private SyncHeader readSync() throws IOException {
        byte[] header = readInbox(8);
        String id = new String(header, 0, 4, StandardCharsets.US_ASCII);
        return new SyncHeader(id, leInt(header, 4));
    }

    private String readFail(SyncHeader header) throws IOException {
        if (header.value <= 0 || header.value > 4096) {
            return "File non disponibile";
        }
        String text = new String(readInbox(header.value), StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? "File non disponibile" : text;
    }

    private byte[] readInbox(int n) throws IOException {
        while (inbox.size() - inboxRead < n) {
            Message msg = readPacket();
            if (msg.command == A_WRTE && msg.arg1 == streamLocal) {
                inbox.write(msg.data);
                send(A_OKAY, streamLocal, msg.arg0, new byte[0]);
            } else if (msg.command == A_OKAY && msg.arg1 == streamLocal) {
                continue;
            } else if (msg.command == A_CLSE && msg.arg1 == streamLocal) {
                throw new EOFException("Canale ADB chiuso");
            } else if (msg.arg1 != streamLocal && isStreamPacket(msg)) {
                continue;
            } else {
                throw new IOException("Dati ADB inattesi");
            }
        }
        byte[] all = inbox.toByteArray();
        byte[] slice = Arrays.copyOfRange(all, inboxRead, inboxRead + n);
        inboxRead += n;
        if (inboxRead > 65_536 && inboxRead == all.length) {
            inbox.reset();
            inboxRead = 0;
        }
        return slice;
    }

    private static boolean isStreamPacket(Message msg) {
        return msg.command == A_OKAY || msg.command == A_CLSE || msg.command == A_WRTE;
    }

    /** Errore riportato dal Pi (FAIL sync, es. file mancante): la connessione resta valida. */
    static final class RemoteFail extends IOException {
        RemoteFail(String message) {
            super(message);
        }
    }

    private void ensureLive() throws IOException {
        if (!isConnected()) {
            throw new IOException("Connessione ADB chiusa. Premi Connetti.");
        }
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        byte[] payload = data == null ? new byte[0] : data;
        byte[] header = new byte[24];
        putLe(header, 0, command);
        putLe(header, 4, arg0);
        putLe(header, 8, arg1);
        putLe(header, 12, payload.length);
        putLe(header, 16, checksum(payload));
        putLe(header, 20, command ^ 0xffffffff);
        out.write(header);
        if (payload.length > 0) {
            out.write(payload);
        }
        out.flush();
    }

    private Message readPacket() throws IOException {
        byte[] header = new byte[24];
        readFully(in, header);
        int command = leInt(header, 0);
        int arg0 = leInt(header, 4);
        int arg1 = leInt(header, 8);
        int length = leInt(header, 12);
        int magic = leInt(header, 20);
        if (magic != (command ^ 0xffffffff)) {
            throw new IOException("Pacchetto ADB danneggiato");
        }
        if (length < 0 || length > 1024 * 1024) {
            throw new IOException("Payload ADB troppo grande");
        }
        byte[] data = new byte[length];
        if (length > 0) {
            readFully(in, data);
        }
        return new Message(command, arg0, arg1, data);
    }

    private static byte[] syncRequest(String id, byte[] name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + name.length);
        out.write(id.getBytes(StandardCharsets.US_ASCII));
        out.write(leInt(name.length));
        out.write(name);
        return out.toByteArray();
    }

    private static void readFully(InputStream in, byte[] dest) throws IOException {
        int off = 0;
        while (off < dest.length) {
            int n = in.read(dest, off, dest.length - off);
            if (n < 0) {
                throw new EOFException("Connessione ADB chiusa");
            }
            off += n;
        }
    }

    private static int checksum(byte[] data) {
        long sum = 0;
        for (byte b : data) {
            sum += b & 0xff;
        }
        return (int) sum;
    }

    private static byte[] leInt(int value) {
        byte[] out = new byte[4];
        putLe(out, 0, value);
        return out;
    }

    private static void putLe(byte[] dest, int offset, int value) {
        dest[offset] = (byte) value;
        dest[offset + 1] = (byte) (value >>> 8);
        dest[offset + 2] = (byte) (value >>> 16);
        dest[offset + 3] = (byte) (value >>> 24);
    }

    private static int leInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] data;

        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data == null ? new byte[0] : data;
        }
    }

    private static final class SyncHeader {
        final String id;
        final int value;

        SyncHeader(String id, int value) {
            this.id = id;
            this.value = value;
        }
    }
}
