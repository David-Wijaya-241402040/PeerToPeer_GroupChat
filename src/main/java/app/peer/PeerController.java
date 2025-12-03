package main.java.app.peer;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Label;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    @FXML private Button btnFile;
    @FXML private ScrollPane scrollBox;
    @FXML private VBox messageBox;

    private ServerSocket listener;
    private Thread listenerThread;
    private volatile boolean listening = false;
    private volatile boolean manualDisconnect = false;
    private volatile boolean firstConnect = true;

    private final Map<String, PeerConnection> peers = new HashMap<>();
    private final Map<PeerConnection, ReconnectInfo> reconnectTargets = new HashMap<>();

    private final Map<String, HBox> typingBubbles = new HashMap<>();
    private final Map<String, Long> lastTypingTime = new HashMap<>();
    private volatile boolean typingSent = false;

    // Di deklarasi field, TAMBAHKAN:
    private FileTransferManager fileTransferManager;
    private final Map<String, ProgressBarData> downloadProgressBars = new HashMap<>();

    @FXML
    private void initialize() {
        usernameField.setDisable(false);
        ipField.setDisable(false);

        chatField.setDisable(true);
        chatField.setOnAction(event -> onSend());

        btnSend.setDisable(true);
        btnFile.setDisable(true);

        btnDisconnect.setDisable(true);
        btnDisconnect.setManaged(false);
        btnDisconnect.setVisible(false);

        btnConnect.setDisable(false);
        btnConnect.setManaged(true);
        btnConnect.setVisible(true);

        chatField.textProperty().addListener((pbs, oldV, newV) -> onTyping());

        // Inisialisasi FileTransferManager
        fileTransferManager = new FileTransferManager(this);
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

    // Method untuk mengakses peers dari FileTransferManager
    public Map<String, PeerConnection> getPeers() {
        synchronized (peers) {
            return new HashMap<>(peers);
        }
    }

    // Handler untuk file transfer - TAMBAHKAN method ini
    public void onFileMetadata(String fileId, String fileName, long fileSize, String sender, PeerConnection conn) {
        fileTransferManager.handleFileMetadata(fileId, fileName, fileSize, sender, conn);
    }

    public void onFileChunk(String fileId, int chunkNumber, String encodedChunk, PeerConnection conn) {
        fileTransferManager.handleFileChunk(fileId, chunkNumber, encodedChunk);
    }

    public void onFileEnd(String fileId, PeerConnection conn) {
        fileTransferManager.handleFileEnd(fileId, conn);
    }

    public void onFileAccept(String fileId, PeerConnection conn) {
        fileTransferManager.handleFileAccept(fileId, conn);
    }

    public void onFileReject(String fileId, PeerConnection conn) {
        fileTransferManager.handleFileReject(fileId, conn);
    }

    // Method untuk memilih dan mengirim file
    @FXML
    private void onSendFile() {
        if (peers.isEmpty()) {
            showAlert("No Peers", "There are no connected peers to send files to.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Send");

        // Filter untuk tipe file
        FileChooser.ExtensionFilter allFiles = new FileChooser.ExtensionFilter("All Files", "*.*");
        FileChooser.ExtensionFilter images = new FileChooser.ExtensionFilter("Images",
                "*.png", "*.jpg", "*.jpeg", "*.gif");
        FileChooser.ExtensionFilter documents = new FileChooser.ExtensionFilter("Documents",
                "*.txt", "*.pdf", "*.doc", "*.docx");
        FileChooser.ExtensionFilter videos = new FileChooser.ExtensionFilter("Videos",
                "*.mp4", "*.avi", "*.mkv", "*.mov");

        fileChooser.getExtensionFilters().addAll(images, documents, videos, allFiles);

        File selectedFile = fileChooser.showOpenDialog(null);

        if (selectedFile != null) {
            // Buat daftar penerima
            List<String> recipients = new ArrayList<>();
            recipients.add("All Peers");

            synchronized (peers) {
                recipients.addAll(peers.keySet());
            }

            // Tampilkan dialog untuk memilih penerima
            ChoiceDialog<String> recipientDialog = new ChoiceDialog<>("All Peers", recipients);
            recipientDialog.setTitle("Send File To");
            recipientDialog.setHeaderText("Select recipient");
            recipientDialog.setContentText("Choose who to send the file:");

            recipientDialog.showAndWait().ifPresent(recipient -> {
                if (recipient.equals("All Peers")) {
                    fileTransferManager.sendFileToAll(selectedFile);
                    addMessageBubble("[System] Sending file '" + selectedFile.getName() + "' to all peers",
                            false, true);
                } else {
                    synchronized (peers) {
                        PeerConnection peer = peers.get(recipient);
                        if (peer != null) {
                            fileTransferManager.sendFile(selectedFile, peer);
                            addMessageBubble("[System] Sending file '" + selectedFile.getName() + "' to " + recipient,
                                    false, true);
                        }
                    }
                }
            });
        }
    }
    // Method untuk menampilkan progress bar download - TAMBAHKAN method ini
// Di PeerController.java, update method showDownloadProgress:
    public void showDownloadProgress(String fileId, String fileName, String sender, long fileSize) {
        Platform.runLater(() -> {
            // Buat HBox untuk progress
            HBox progressContainer = new HBox(10);
            progressContainer.setPadding(new Insets(5));
            progressContainer.setAlignment(Pos.CENTER_LEFT);
            progressContainer.setStyle("-fx-background-color: #f0f0f0; -fx-background-radius: 5;");

            // Icon file
            Label fileIcon = new Label("📁");
            fileIcon.setStyle("-fx-font-size: 16px;");

            // Info file
            VBox fileInfo = new VBox(2);
            Label fileNameLabel = new Label(fileName);
            fileNameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");

            Label fileDetails = new Label("From: " + sender + " | Size: " + formatFileSize(fileSize));
            fileDetails.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            fileInfo.getChildren().addAll(fileNameLabel, fileDetails);

            // Progress bar
            ProgressBar progressBar = new ProgressBar(0);
            progressBar.setPrefWidth(200);

            // Label persentase
            Label percentLabel = new Label("0%");
            percentLabel.setStyle("-fx-font-size: 11px; -fx-min-width: 35px;");

            progressContainer.getChildren().addAll(fileIcon, fileInfo, progressBar, percentLabel);

            // Simpan reference untuk update
            downloadProgressBars.put(fileId, new ProgressBarData(progressContainer, progressBar, percentLabel));
            messageBox.getChildren().add(progressContainer);

            scrollBox.setVvalue(1.0);
        });
    }

    // Class helper untuk menyimpan data progress bar
    private static class ProgressBarData {
        HBox container;
        ProgressBar progressBar;
        Label percentLabel;

        ProgressBarData(HBox container, ProgressBar progressBar, Label percentLabel) {
            this.container = container;
            this.progressBar = progressBar;
            this.percentLabel = percentLabel;
        }
    }

    // Update method updateDownloadProgress:
    public void updateDownloadProgress(String fileId, double progress) {
        Platform.runLater(() -> {
            ProgressBarData data = downloadProgressBars.get(fileId);
            if (data != null) {
                data.progressBar.setProgress(progress);
                data.percentLabel.setText(String.format("%.0f%%", progress * 100));

                // Jika selesai, ubah warna progress bar
                if (progress >= 1.0) {
                    data.progressBar.setStyle("-fx-accent: #4CAF50;"); // Hijau saat selesai
                    data.percentLabel.setText("✓");
                }
            }
        });
    }
    // Hapus progress bar setelah selesai - TAMBAHKAN method ini
// Hapus progress bar setelah selesai
    public void removeDownloadProgress(String fileId) {
        Platform.runLater(() -> {
            ProgressBarData data = downloadProgressBars.remove(fileId);
            if (data != null && data.container != null) {
                messageBox.getChildren().remove(data.container);
            }
        });
    }

    // Helper untuk format file size - TAMBAHKAN method ini
    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        else if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        else if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        else return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private boolean isMannuallyDisconnect(ReconnectInfo info) {
        return manualDisconnect;
    }

    private void onTyping() {
        String local = usernameField.getText().trim();
        if(local.isEmpty()) return;
        if (chatField.isDisable()) return;

        lastTypingTime.put(local, System.currentTimeMillis());
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

                Long last = lastTypingTime.get(local);
                if (last != null && System.currentTimeMillis() - last >= 1000) {
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
                            PeerConnection pc = new PeerConnection(incoming, this);

                            String local = usernameField.getText().trim();
                            if(!local.isEmpty()) {
                                pc.sendLine("HELLO|" + local);
                            } else {
                                pc.sendLine("BYE|No username set");
                                pc.close();
                            }
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

        if(username.isEmpty()) {
            showAlert("Missing info", "Username can't be empty!!");
            if(firstConnect == false && listening) {
                stopListener();
                messageBox.getChildren().clear();
                scrollBox.setVvalue(1.0);
                firstConnect = true;
            }
            return;
        }

        if(target.isEmpty() && !username.isEmpty() && (firstConnect == true)) {
            if(!listening) startListener();
            firstConnect = false;
            return;
        }

        if(target.isEmpty()) {
            showAlert("WARNING", "Fill the IP and Port Field if you are not the host!!");
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
                    btnFile.setDisable(false);

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
                p.sendLine("STOPTYPE|" + username);
            }
        }

        addMessageBubble("[" + time + "] You: " + msg, true, false);
        chatField.clear();

        typingSent = false;
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

    public void onPeerTyping(String username, PeerConnection pc) {
        Platform.runLater(() -> {
            if (username == null || username.isEmpty()) return;
            HBox bubble = typingBubbles.get(username);
            if (bubble == null) {
                Label lbl = new Label(username + " is typing...");
                lbl.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
                bubble = new HBox(lbl);
                bubble.setPadding(new Insets(4));
                bubble.setAlignment(Pos.CENTER_LEFT);
                typingBubbles.put(username, bubble);

                // tambahkan bubble ke ujung messageBox (atau di index tertentu).
                messageBox.getChildren().add(bubble);
            } else {
                // bubble sudah ada — refresh posisinya ke bawah
                messageBox.getChildren().remove(bubble);
                messageBox.getChildren().add(bubble);
            }
            scrollBox.setVvalue(1.0);
        });
    }

    public void onPeerStopTyping(String username, PeerConnection pc) {
        Platform.runLater(() -> {
            HBox bubble = typingBubbles.remove(username);
            if (bubble != null) {
                messageBox.getChildren().remove(bubble);
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
        if (fileTransferManager != null) {
            fileTransferManager.shutdown();
        }
    }
}
