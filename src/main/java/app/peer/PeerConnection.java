package main.java.app.peer;

import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

public class PeerConnection {
    private final Socket socket;
    private final PeerController controller;
    private BufferedReader reader;
    private BufferedWriter writer;

    private volatile boolean active = true;
    private String remoteName = "Unknown";
    private final AtomicLong lastSeen = new AtomicLong(System.currentTimeMillis());

    private static final long PING_INTERVAL_MS = 10_000;
    private static final long TIMEOUT_MS = 30_000;
    private Thread readerThread;
    private Thread pingThread;
    private Thread monitorThread;

    public PeerConnection(Socket socket, PeerController controller) throws IOException {
        this.socket = socket;
        this.controller = controller;

        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

        startReader();
        startPingSender();
        startMonitor();
    }

    private void startReader() {
        readerThread = new Thread(() -> {
            try {
                String line;
                while (active && (line = reader.readLine()) != null) {
                    // update last seen on any incoming
                    touchLastSeen();

                    if (line.startsWith("HELLO|")) {
                        remoteName = safeSubstring(line, 6);
                        controller.onPeerHandshake(remoteName, this);
                        continue;
                    }

                    if (line.startsWith("CHAT|")) {
                        String[] p = line.split("\\|", 3);
                        if (p.length >= 3) {
                            String sender = p[1];
                            String msg = p[2];
                            controller.onPeerMessage(sender + ": " + msg, this);
                        }
                        continue;
                    }

                    if (line.startsWith("PING|")) {
                        // reply with PONG (echo same ts or send current)
                        String ts = safeSubstring(line, 5);
                        sendLine("PONG|" + ts);
                        continue;
                    }

                    if (line.startsWith("PONG|")) {
                        // update last seen, optionally log
                        // (we already touched lastSeen at top)
                        continue;
                    }

                    if (line.startsWith("BYE|")) {
                        break;
                    }

                    if (line.startsWith("TYPE|")) {
                        String user = line.substring(5);
                        controller.onPeerTyping(user, this);
                        continue;
                    }

                    if (line.startsWith("STOPTYPE|")) {
                        String user = line.substring(9);
                        controller.onPeerStopTyping(user, this);
                        continue;
                    }

                    if (line.startsWith("FILEINFO|")) {
                        String[] p = line.split("\\|", 3);
                        if (p.length >= 3) {
                            String fileName = p[1];
                            long fileSize = Long.parseLong(p[2]);
                            controller.onIncomingFileStart(fileName, fileSize, this);
                        }
                        continue;
                    }

                    if (line.startsWith("FILEDATA|")) {
                        String base64 = safeSubstring(line, 9);
                        controller.onIncomingFileData(base64, this);
                        continue;
                    }

                    if (line.startsWith("FILEEND")) {
                        controller.onIncomingFileEnd(this);
                        continue;
                    }


                    // fallback: treat as chat from unknown (legacy)
                    controller.onPeerMessage((remoteName != null ? remoteName : "Unknown") + ": " + line, this);
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }, "PeerReader-" + socket.getRemoteSocketAddress());
        readerThread.setDaemon(true);
        readerThread.start();
    }
    private void startPingSender() {
        pingThread = new Thread(() -> {
            try {
                while (active) {
                    sendLine("PING|" + System.currentTimeMillis());
                    Thread.sleep(PING_INTERVAL_MS);
                }
            } catch (InterruptedException ignored) {
            }
        }, "PeerPingSender-" + socket.getRemoteSocketAddress());
        pingThread.setDaemon(true);
        pingThread.start();
    }

    private void startMonitor() {
        monitorThread = new Thread(() -> {
            try {
                while (active) {
                    long since = System.currentTimeMillis() - lastSeen.get();
                    if (since > TIMEOUT_MS) {
                        // consider connection dead
                        controller.addMessageBubble("[System] Peer " + (remoteName != null ? remoteName : socket.getRemoteSocketAddress()) + " timed out (" + (since/1000) + "s). Closing.", false, true);
                        close();
                        break;
                    }
                    Thread.sleep(5_000);
                }
            } catch (InterruptedException ignored) {
            }
        }, "PeerMonitor-" + socket.getRemoteSocketAddress());
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private static String safeSubstring(String s, int from) {
        if (s == null) return "";
        return (s.length() > from) ? s.substring(from) : "";
    }

    private void touchLastSeen() {
        lastSeen.set(System.currentTimeMillis());
    }

    public synchronized void sendLine(String message) {
        try {
            writer.write(message);
            writer.write("\n");  // newline wajib!
            writer.flush();      // WAJIB flush manual!
        } catch (Exception ignored) {}
    }


    public void sendFile(String fileName, byte[] data) {
        if (!active) return;

        sendLine("FILEINFO|" + fileName + "|" + data.length);

        int chunkSize = 14000; // aman untuk TCP + base64
        int offset = 0;

        while (offset < data.length) {
            int end = Math.min(offset + chunkSize, data.length);
            byte[] chunk = Arrays.copyOfRange(data, offset, end);
            String base64 = Base64.getEncoder().encodeToString(chunk);
            sendLine("FILEDATA|" + base64);

            offset = end;
        }

        sendLine("FILEEND");
    }


    public void close() {
        if (!active) return;
        active = false;

        // try to send a BYE politely (best-effort)
        try { sendLine("BYE|" + (controller != null ? controller.getLocalUsernameSafe() : "me")); } catch (Exception ignored) {}

        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (!socket.isClosed()) socket.close(); } catch (IOException ignored) {}

        // notify controller (it will remove from peers map)
        try { controller.onPeerClosed(this); } catch (Exception ignored) {}

        // interrupt threads as best-effort
        try { if (readerThread != null) readerThread.interrupt(); } catch (Exception ignored) {}
        try { if (pingThread != null) pingThread.interrupt(); } catch (Exception ignored) {}
        try { if (monitorThread != null) monitorThread.interrupt(); } catch (Exception ignored) {}
    }

    public String getRemoteName() {
        return remoteName;
    }

    public String getRemoteAddress() {
        return socket.getRemoteSocketAddress().toString();
    }
}
