package main.java.app.peer;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.net.*;
import java.time.LocalTime;
import java.util.*;

public class PeerController {

    @FXML private TextField ipField;
    @FXML private TextField usernameField;
    @FXML private TextField chatField;
    @FXML private Button btnConnect;
    @FXML private Button btnDisconnect;
    @FXML private Button btnSend;
    @FXML private ScrollPane scrollBox;
    @FXML private VBox messageBox;

    private ServerSocket listener;
    private Thread listenerThread;
    private volatile boolean listening = false;
    private volatile boolean manualDisconnect = false;

    private final Map<String, PeerConnection> peers = new HashMap<>();
    private final Map<PeerConnection, ReconnectInfo> reconnectTargets = new HashMap<>();

    private final HBox typingBubble = new HBox();
    private Label typingLabel = new Label("");
    private long lastTypingTime = 0;
    private volatile boolean typingSent = false;

    @FXML
    private void initialize() {
        usernameField.setDisable(false);
        ipField.setDisable(false);

        chatField.setDisable(true);
        chatField.setOnAction(event -> onSend());

        btnSend.setDisable(true);

        btnDisconnect.setDisable(true);
        btnDisconnect.setManaged(false);
        btnDisconnect.setVisible(false);

        btnConnect.setDisable(false);
        btnConnect.setManaged(true);
        btnConnect.setVisible(true);

        typingLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");

        typingBubble.setPadding(new Insets(4));
        typingBubble.setAlignment(Pos.CENTER_LEFT);
        typingBubble.getChildren().add(typingLabel);
        typingBubble.setVisible(false);

        messageBox.getChildren().add(typingBubble);

        startListener();

        chatField.textProperty().addListener((pbs, oldV, newV) -> onTyping());
    }

    private static class ReconnectInfo {
        String ip;
        int port;
        String remoteName;

        ReconnectInfo(String ip, int port, String remoteName) {
            this.ip = ip;
            this.port = port;
            this.remoteName = remoteName;
        }
    }

    private boolean isMannuallyDisconnect(ReconnectInfo info) {
        return manualDisconnect;
    }

    private void onTyping() {
        String local = usernameField.getText().trim();
        if(local.isEmpty()) return;
        if (chatField.isDisable()) return;

        lastTypingTime = System.currentTimeMillis();
        if(!typingSent) {
            typingSent = true;

            synchronized (peers) {
                List <PeerConnection> typePeers = new ArrayList<>(peers.values());
                for (PeerConnection p : typePeers) {
                    p.sendLine("TYPE|" + local);
                }
            }

            new Thread(() -> {
                try {
                    Thread.sleep(1200);
                } catch (Exception ignored) {}

                if (System.currentTimeMillis() - lastTypingTime >= 1000) {
                    typingSent = false;

                    synchronized (peers) {
                        List <PeerConnection> stPeers = new ArrayList<>(peers.values());
                        for (PeerConnection p : stPeers) {
                            p.sendLine("STOPTYPE|" + local);
                        }
                    }
                }
            }).start();
        }
    }

    private void startListener() {
        new Thread(() -> {
            try {
                listener = new ServerSocket(0);
                listener.setReuseAddress(true);
                listening = true;

                int port = listener.getLocalPort();
                String localIp = detectLocalIp();

                addMessageBubble("[System] Listening on " + localIp + ":" + port, false, true);

                listenerThread = new Thread(() -> {
                    while (listening) {
                        try {
                            Socket incoming = listener.accept();
                            // ❗ Incoming connection — jangan masukkan ke peers dulu
                            // Masukkan di handshake setelah dapat username
                            new PeerConnection(incoming, this);
                        } catch (SocketException se) {
                            break;
                        } catch (IOException e) {
                            if (listening)
                                addMessageBubble("[System] Listener error: " + e.getMessage(), false, true);
                        }
                    }
                }, "PeerListenerThread");

                listenerThread.setDaemon(true);
                listenerThread.start();

            } catch (IOException e) {
                addMessageBubble("[Error] Could not start listener: " + e.getMessage(), false, true);
            }
        }, "StartListener").start();
    }

    private String detectLocalIp() {
        try {
            DatagramSocket s = new DatagramSocket();
            s.connect(InetAddress.getByName("8.8.8.8"), 10002);
            String ip = s.getLocalAddress().getHostAddress();
            s.close();
            return ip;
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    @FXML
    private void onConnect() {
        manualDisconnect = false;
        String target = ipField.getText().trim();
        String username = usernameField.getText().trim();

        if (target.isEmpty() || username.isEmpty()) {
            showAlert("Missing Info", "Please fill in both username and target IP:port!");
            return;
        }

        String[] parts = target.split(":");
        if (parts.length < 2) {
            showAlert("Format Error", "IP:Port expected (example: 192.168.1.5:53211)");
            return;
        }

        String ip = parts[0];
        int port;

        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException nfe) {
            showAlert("Port Error", "Port must be a number");
            return;
        }

        try {
            String[] selfCheck = target.split(":");
            String targetIp = selfCheck[0];
            int targetPort = Integer.parseInt(selfCheck[1]);

            String localIp = detectLocalIp();
            int localPort = listener.getLocalPort();

            List<String> selfIps = Arrays.asList(
                    localIp,
                    "127.0.0.1",
                    "0.0.0.0",
                    InetAddress.getLocalHost().getHostAddress()
            );

            if(targetIp.equals(localIp) && targetPort == localPort) {
                showAlert("Invalid Connection", "You cannot connect to your own device.");
                return;
            }

            if(targetIp.equals("127.0.0.1")) {
                showAlert("Invalid Connection", "You cannot connect to your own device.");
                return;
            }

            if(targetIp.equals("0.0.0.0")) {
                showAlert("Invalid Connection", "You cannot connect to your own device.");
                return;
            }

            String localhost = InetAddress.getLocalHost().getHostAddress();
            if(targetIp.equals(localhost)) {
                showAlert("Invalid Connection", "You cannot connect to your own device.");
                return;
            }
        } catch (Exception ignored) {}

        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, port), 4000);

                PeerConnection pc = new PeerConnection(s, this);

                // ❗ Outgoing juga jangan dimasukkan dulu
                // Tunggu handshake dulu
                pc.sendLine("HELLO|" + username);  // First line = username

                Platform.runLater(() -> {
                    chatField.setDisable(false);
                    btnSend.setDisable(false);

                    btnDisconnect.setDisable(false);
                    btnDisconnect.setManaged(true);
                    btnDisconnect.setVisible(true);

                    btnConnect.setDisable(true);
                    btnConnect.setManaged(false);
                    btnConnect.setVisible(false);

                    usernameField.setDisable(true);
                    ipField.setDisable(true);
                });

            } catch (IOException e) {
                addMessageBubble("[Error] Could not connect — " + e.getMessage(), false, true);
            }

        }, "OutgoingConnector-" + target).start();
    }

    @FXML
    private void onDisconnect() {
        new Thread(() -> {
            manualDisconnect = true;
            closeAllPeers();
            userDisconnectAll();

            Platform.runLater(() -> {
                addMessageBubble("[System] Disconnected.", false, true);
                resetUI();
            });
        }).start();
    }

    @FXML
    private void onSend() {
        String msg = chatField.getText().trim();
        if (msg.isEmpty()) return;

        String time = LocalTime.now().withNano(0).toString();
        String username = usernameField.getText().trim();

        synchronized (peers) {
            for (PeerConnection p : peers.values()) {
                p.sendLine("CHAT|" + username + "|" + msg);
            }
        }

        addMessageBubble("[" + time + "] You: " + msg, true, false);
        chatField.clear();

        typingLabel.setText("");
    }

    // safe accessor untuk nama lokal
    public String getLocalUsernameSafe() {
        String u = usernameField.getText();
        return (u == null || u.trim().isEmpty()) ? "me" : u.trim();
    }

    // 🔥 FIX — Handshake benar
    public void onPeerHandshake(String remoteName, PeerConnection conn) {
        if(remoteName.equals(getLocalUsernameSafe())) {conn.close(); return;}

        synchronized (peers) {
            // Overwrite kalau sudah ada koneksi lama
            peers.put(remoteName, conn);
        }

        try {
            String addr = conn.getRemoteAddress(); // contoh "/192.168.1.10:53211"
            String cleaned = addr.replace("/", "");
            String[] parts = cleaned.split(":");

            String ip = parts[0];
            int port = Integer.parseInt(parts[1]);

            reconnectTargets.put(conn, new ReconnectInfo(ip, port, remoteName));
        } catch (Exception e) {}

        addMessageBubble("[System] " + remoteName + " connected (" + conn.getRemoteAddress() + ")", false, true);

        Platform.runLater(() -> {
            chatField.setDisable(false);
            btnSend.setDisable(false);

            btnDisconnect.setDisable(false);
            btnDisconnect.setManaged(true);
            btnDisconnect.setVisible(true);

            btnConnect.setDisable(true);
            btnConnect.setManaged(false);
            btnConnect.setVisible(false);

            usernameField.setDisable(true);
            ipField.setDisable(true);
        });
    }

    public void onPeerMessage(String display, PeerConnection from) {
        addMessageBubble(display, false, false);
    }

    public void onPeerClosed(PeerConnection conn) {
        String name = conn.getRemoteName();

        if (name != null) {
            synchronized (peers) {
                peers.remove(name);
            }
        }

        if (name != null && peers.containsKey(name)) {
            return;
        }

        addMessageBubble("[System] " + (name != null ? name : "Unknown") + " disconnected.", false, true);

        ReconnectInfo info = reconnectTargets.remove(conn);
        if(!manualDisconnect && info != null) {
            new Thread(() -> {
                int attempt = 0;

                while (attempt < 20 && !manualDisconnect) {
                    attempt++;

                    try {
                        String msg = "[Reconnecting to" + info.remoteName + " ... attempt " + attempt + "]";
                        addMessageBubble(msg, false, true);

                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(info.ip, info.port), 3000);

                        PeerConnection newPC = new PeerConnection(s, this);

                        synchronized (peers) {
                            peers.put(info.remoteName, newPC);
                        }

                        reconnectTargets.put(newPC, info);

                        String uname = usernameField.getText().trim();
                        newPC.sendLine("HELLO|" + uname);

                        addMessageBubble("[Reconnected to " + info.remoteName + "]", false, true);
                        return;
                    } catch (Exception e) {
                        try {Thread.sleep(2000);} catch (InterruptedException ignored) {}
                    }
                }
                addMessageBubble("[Failed to reconnect to " + info.remoteName + "]",false, true);
            }, "Reconnector-" + info.remoteName).start();
        }

        synchronized (peers) {
            if (peers.isEmpty()) {
                Platform.runLater(this::resetUI);
            }
        }
    }

    private void closeAllPeers() {
        synchronized (peers) {
            for (PeerConnection p : new ArrayList<>(peers.values())) {
                try { p.close(); } catch (Exception ignored) {}
            }
            peers.clear();
        }
    }

    public void onPeerTyping (String username, PeerConnection pc) {
        Platform.runLater(() -> {
            typingLabel.setText(username + " is typing...");
            typingBubble.setVisible(true);

            messageBox.getChildren().remove(typingBubble);
            messageBox.getChildren().add(typingBubble);
            scrollBox.setVvalue(1.0);
        });
    }

    public void onPeerStopTyping (String username, PeerConnection pc) {
        Platform.runLater(() -> {
            if((username + " is typing...").equals(typingLabel.getText())) {
                typingBubble.setVisible(false);
                typingLabel.setText("");
                messageBox.getChildren().remove(typingBubble);
            }
        });
    }

    private void stopListener() {
        listening = false;
        try { if (listener != null) listener.close(); } catch (IOException ignored) {}
    }

    private void resetUI() {
        usernameField.setDisable(false);
        ipField.setDisable(false);

        chatField.setDisable(true);
        btnSend.setDisable(true);

        btnDisconnect.setDisable(true);
        btnDisconnect.setManaged(false);
        btnDisconnect.setVisible(false);

        btnConnect.setDisable(false);
        btnConnect.setManaged(true);
        btnConnect.setVisible(true);

        if (!listening) {
            startListener();
        }
    }

    void addMessageBubble(String message, boolean isOwnMessage, boolean isServerMessage) {
        Platform.runLater(() -> {
            Label bubble = new Label(message);
            bubble.setWrapText(true);

            bubble.setStyle(
                    isOwnMessage
                            ? "-fx-background-color: #FF5F1F; -fx-text-fill: white; -fx-padding: 8 12; -fx-background-radius: 12"
                            : isServerMessage
                            ? "-fx-background-color: #A9A9A9; -fx-text-fill: black; -fx-padding: 6 10; -fx-background-radius: 12; -fx-font-size: 10px"
                            : "-fx-background-color: #36454F; -fx-text-fill: white; -fx-padding: 8 12; -fx-background-radius: 12"
            );

            HBox container = new HBox(bubble);
            container.setPadding(new Insets(4));
            container.setAlignment(isOwnMessage ? Pos.CENTER_RIGHT : isServerMessage ? Pos.CENTER : Pos.CENTER_LEFT);

            int insertIndex = messageBox.getChildren().size();
            if (typingBubble.isVisible() && messageBox.getChildren().contains(typingBubble)) {
                insertIndex = messageBox.getChildren().indexOf(typingBubble);
            }

            messageBox.getChildren().add(container);

            scrollBox.setVvalue(1.0);
        });
    }

    public void userDisconnectAll() {
        manualDisconnect = true;

        List<PeerConnection> copypeer = new ArrayList<>(peers.values());
        for (PeerConnection pc : copypeer) {
            pc.close();
        }
        peers.clear();
    }

    private void showAlert(String title, String msg) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
            alert.setTitle(title);
            alert.showAndWait();
        });
    }

    public void safeShutdown() {
        closeAllPeers();
        stopListener();
    }
}
