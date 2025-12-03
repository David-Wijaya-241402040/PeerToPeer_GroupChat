package main.java.app.peer;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.awt.Desktop;

public class FileTransferManager {
    private final PeerController controller;
    private final ExecutorService executorService;
    private final Map<String, FileTransfer> pendingDownloads;
    private final Map<String, FileTransfer> pendingUploads;
    private final String downloadDirectory;
    private static final int CHUNK_SIZE = 32 * 1024; // 32KB - optimal size
    private static final int MAX_RETRIES = 3;

    public FileTransferManager(PeerController controller) {
        this.controller = controller;
        this.executorService = Executors.newFixedThreadPool(4); // Fixed thread pool
        this.pendingDownloads = new ConcurrentHashMap<>();
        this.pendingUploads = new ConcurrentHashMap<>();

        // Buat folder download di Desktop/PeerChatDownloads/
        this.downloadDirectory = System.getProperty("user.home") +
                File.separator + "Desktop" +
                File.separator + "PeerChatDownloads" +
                File.separator;

        try {
            Path downloadPath = Paths.get(downloadDirectory);
            if (!Files.exists(downloadPath)) {
                Files.createDirectories(downloadPath);
            }
        } catch (IOException e) {
            System.err.println("Error creating download directory: " + e.getMessage());
        }
    }

    // Kirim file ke satu peer - DIPERBAIKI
    public void sendFile(File file, PeerConnection peer) {
        String fileId = UUID.randomUUID().toString();
        FileTransfer upload = new FileTransfer(fileId, file.getName(), file.length(),
                controller.getLocalUsernameSafe());
        pendingUploads.put(fileId, upload);

        executorService.execute(() -> {
            int retryCount = 0;
            boolean success = false;

            while (retryCount < MAX_RETRIES && !success) {
                try {
                    // 1. Kirim metadata file
                    String metadata = String.format("FILE_META|%s|%s|%d|%s|%s",
                            fileId,
                            file.getName(),
                            file.length(),
                            controller.getLocalUsernameSafe(),
                            getFileChecksum(file));

                    if (!sendWithRetry(peer, metadata, 3)) {
                        retryCount++;
                        continue;
                    }

                    Platform.runLater(() -> {
                        controller.addMessageBubble(
                                "[System] Sending file '" + file.getName() +
                                        "' (" + formatFileSize(file.length()) + ") to " + peer.getRemoteName(),
                                false, true);
                    });

                    // 2. Tunggu konfirmasi (timeout 10 detik)
                    long startTime = System.currentTimeMillis();
                    boolean accepted = false;

                    while (System.currentTimeMillis() - startTime < 10000) {
                        if (!pendingUploads.containsKey(fileId)) {
                            // File ditolak atau error
                            Platform.runLater(() -> {
                                controller.addMessageBubble(
                                        "[System] File '" + file.getName() + "' was rejected by " + peer.getRemoteName(),
                                        false, true);
                            });
                            return;
                        }

                        FileTransfer currentUpload = pendingUploads.get(fileId);
                        if (currentUpload != null && currentUpload.isAccepted()) {
                            accepted = true;
                            break;
                        }
                        Thread.sleep(100);
                    }

                    if (!accepted) {
                        Platform.runLater(() -> {
                            controller.addMessageBubble(
                                    "[System] No response from " + peer.getRemoteName() +
                                            " for file '" + file.getName() + "'",
                                    false, true);
                        });
                        pendingUploads.remove(fileId);
                        return;
                    }

                    // 3. Kirim file dalam chunks
                    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                        long fileSize = file.length();
                        long totalChunks = (fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE;
                        int chunkNumber = 0;

                        byte[] buffer = new byte[CHUNK_SIZE];

                        while (chunkNumber < totalChunks) {
                            // Baca chunk
                            raf.seek(chunkNumber * (long) CHUNK_SIZE);
                            int bytesRead = raf.read(buffer);

                            if (bytesRead <= 0) break;

                            // Encode chunk (gunakan base64 untuk reliable transfer)
                            byte[] chunkData = new byte[bytesRead];
                            System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                            String encodedChunk = Base64.getEncoder().encodeToString(chunkData);

                            // Kirim chunk dengan retry
                            String chunkMessage = String.format("FILE_CHUNK|%s|%d|%d|%s",
                                    fileId, chunkNumber, totalChunks, encodedChunk);

                            if (!sendWithRetry(peer, chunkMessage, 2)) {
                                // Skip chunk ini untuk retry
                                Thread.sleep(100);
                                continue;
                            }

                            // Update progress
                            double progress = (double) (chunkNumber + 1) / totalChunks;
                            Platform.runLater(() -> {
                                controller.updateUploadProgress(fileId, progress, peer.getRemoteName());
                            });

                            chunkNumber++;

                            // Delay kecil untuk flow control
                            Thread.sleep(10);
                        }

                        // 4. Kirim tanda selesai
                        String endMessage = String.format("FILE_END|%s", fileId);
                        sendWithRetry(peer, endMessage, 3);

                        success = true;

                        Platform.runLater(() -> {
                            controller.addMessageBubble(
                                    "[System] File '" + file.getName() + "' sent successfully to " + peer.getRemoteName(),
                                    false, true);
                        });

                        // Cleanup
                        Thread.sleep(5000);
                        pendingUploads.remove(fileId);

                    } catch (Exception e) {
                        System.err.println("Error reading/sending file chunks: " + e.getMessage());
                        retryCount++;
                    }

                } catch (Exception e) {
                    System.err.println("Send file error (attempt " + (retryCount + 1) + "): " + e.getMessage());
                    retryCount++;

                    if (retryCount < MAX_RETRIES) {
                        try {
                            Thread.sleep(1000 * retryCount); // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            if (!success) {
                Platform.runLater(() -> {
                    controller.addMessageBubble(
                            "[Error] Failed to send file '" + file.getName() +
                                    "' to " + peer.getRemoteName() + " after " + MAX_RETRIES + " attempts",
                            false, true);
                });
                pendingUploads.remove(fileId);
            }
        });
    }

    // Helper method untuk kirim dengan retry
    private boolean sendWithRetry(PeerConnection peer, String message, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            try {
                peer.sendLine(message);
                return true;
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    System.err.println("Failed to send message after " + maxRetries + " retries: " + message);
                } else {
                    try {
                        Thread.sleep(100 * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return false;
    }

    // Kirim file ke semua peer - DIPERBAIKI
    public void sendFileToAll(File file) {
        Map<String, PeerConnection> peers = controller.getPeers();
        if (peers.isEmpty()) {
            Platform.runLater(() -> {
                controller.addMessageBubble(
                        "[System] No peers connected to send file",
                        false, true);
            });
            return;
        }

        Platform.runLater(() -> {
            controller.addMessageBubble(
                    "[System] Sending file '" + file.getName() +
                            "' (" + formatFileSize(file.length()) + ") to " + peers.size() + " peer(s)",
                    false, true);
        });

        for (PeerConnection peer : peers.values()) {
            sendFile(file, peer);
        }
    }

    // Handle incoming file metadata - DIPERBAIKI
    public void handleFileMetadata(String fileId, String fileName, long fileSize, String sender,
                                   String checksum, PeerConnection conn) {
        // Validasi file size (max 100MB untuk safety)
        if (fileSize > 100 * 1024 * 1024) {
            Platform.runLater(() -> {
                controller.addMessageBubble(
                        "[System] File '" + fileName + "' too large (" +
                                formatFileSize(fileSize) + "). Maximum 100MB allowed.",
                        false, true);
            });

            String rejectMsg = String.format("FILE_REJECT|%s|TOO_LARGE", fileId);
            conn.sendLine(rejectMsg);
            return;
        }

        Platform.runLater(() -> {
            // Tampilkan dialog konfirmasi download
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Incoming File");
            alert.setHeaderText("File from " + sender);
            alert.setContentText(String.format(
                    "File: %s\nSize: %s\n\nDo you want to download this file?",
                    fileName, formatFileSize(fileSize)));

            ButtonType downloadButton = new ButtonType("Download");
            ButtonType ignoreButton = new ButtonType("Ignore");
            alert.getButtonTypes().setAll(downloadButton, ignoreButton);

            alert.showAndWait().ifPresent(buttonType -> {
                if (buttonType == downloadButton) {
                    // Simpan info transfer untuk menerima file
                    FileTransfer transfer = new FileTransfer(fileId, fileName, fileSize, sender, checksum);
                    pendingDownloads.put(fileId, transfer);

                    // Kirim konfirmasi ke pengirim
                    String acceptMsg = String.format("FILE_ACCEPT|%s", fileId);
                    conn.sendLine(acceptMsg);

                    // Tampilkan progress bar
                    controller.showDownloadProgress(fileId, fileName, sender, fileSize);

                    controller.addMessageBubble(
                            "[System] Downloading file '" + fileName + "' (" +
                                    formatFileSize(fileSize) + ") from " + sender,
                            false, true);
                } else {
                    // Kirim penolakan ke pengirim
                    String rejectMsg = String.format("FILE_REJECT|%s|USER_REJECTED", fileId);
                    conn.sendLine(rejectMsg);

                    controller.addMessageBubble(
                            "[System] Ignored file '" + fileName + "' from " + sender,
                            false, true);
                }
            });
        });
    }

    // Handle incoming file chunk - DIPERBAIKI
    public void handleFileChunk(String fileId, int chunkNumber, long totalChunks, String encodedChunk) {
        FileTransfer transfer = pendingDownloads.get(fileId);
        if (transfer == null) {
            System.err.println("No pending download for fileId: " + fileId);
            return;
        }

        try {
            byte[] chunkData = Base64.getDecoder().decode(encodedChunk);
            transfer.writeChunk(chunkData, chunkNumber);

            // Update progress
            double progress = (double) (chunkNumber + 1) / totalChunks;
            controller.updateDownloadProgress(fileId, progress);

            // Log setiap 10% progress
            if (chunkNumber % Math.max(1, (int)(totalChunks / 10)) == 0) {
                System.out.println("Download progress for " + transfer.getFileName() +
                        ": " + String.format("%.0f%%", progress * 100));
            }

        } catch (Exception e) {
            System.err.println("Error handling file chunk " + chunkNumber + " for " + fileId +
                    ": " + e.getMessage());
        }
    }

    // Handle end of file transfer - DIPERBAIKI
    public void handleFileEnd(String fileId, PeerConnection conn) {
        FileTransfer transfer = pendingDownloads.remove(fileId);
        if (transfer != null) {
            try {
                // Simpan file
                String savedPath = transfer.complete(downloadDirectory);

                // Verifikasi file
                boolean verified = verifyFile(savedPath, transfer.getExpectedChecksum());

                Platform.runLater(() -> {
                    if (verified) {
                        controller.addMessageBubble(
                                "[System] ✓ File '" + transfer.getFileName() +
                                        "' downloaded successfully!\nSaved to: " + savedPath,
                                false, true);

                        // Tampilkan dialog sukses
                        showDownloadCompleteDialog(transfer.getFileName(), savedPath, true);
                    } else {
                        controller.addMessageBubble(
                                "[Warning] File '" + transfer.getFileName() +
                                        "' downloaded but checksum verification failed!",
                                false, true);

                        // Tampilkan dialog warning
                        showDownloadCompleteDialog(transfer.getFileName(), savedPath, false);
                    }

                    // Hapus progress bar
                    controller.removeDownloadProgress(fileId);
                });

            } catch (IOException e) {
                Platform.runLater(() -> {
                    controller.addMessageBubble(
                            "[Error] Failed to save file: " + e.getMessage(),
                            false, true);
                    controller.removeDownloadProgress(fileId);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    controller.addMessageBubble(
                            "[Error] File transfer error: " + e.getMessage(),
                            false, true);
                    controller.removeDownloadProgress(fileId);
                });
            }
        }
    }

    // Handle file acceptance from receiver
    public void handleFileAccept(String fileId, PeerConnection conn) {
        FileTransfer transfer = pendingUploads.get(fileId);
        if (transfer != null) {
            transfer.setAccepted(true);
            Platform.runLater(() -> {
                controller.addMessageBubble(
                        "[System] " + conn.getRemoteName() + " accepted your file '" +
                                transfer.getFileName() + "'",
                        false, true);
            });
        }
    }

    // Handle file rejection from receiver
    public void handleFileReject(String fileId, String reason, PeerConnection conn) {
        FileTransfer transfer = pendingUploads.remove(fileId);
        if (transfer != null) {
            Platform.runLater(() -> {
                String reasonMsg = reason != null ? " (" + reason + ")" : "";
                controller.addMessageBubble(
                        "[System] " + conn.getRemoteName() + " rejected your file '" +
                                transfer.getFileName() + "'" + reasonMsg,
                        false, true);
            });
        }
    }

    // Method untuk menampilkan dialog setelah download selesai
    private void showDownloadCompleteDialog(String fileName, String filePath, boolean success) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(success ? "Download Complete" : "Download Warning");
            alert.setHeaderText(success ?
                    "✓ File '" + fileName + "' Downloaded Successfully" :
                    "⚠ File '" + fileName + "' Downloaded (Verification Failed)");

            String content = "File saved to:\n" + filePath +
                    "\n\nSize: " + formatFileSize(new File(filePath).length());

            if (!success) {
                content += "\n\n⚠ Warning: File integrity check failed!\n" +
                        "The file may be corrupted.";
            }

            alert.setContentText(content);

            ButtonType openFolderButton = new ButtonType("Open Folder");
            ButtonType openFileButton = new ButtonType("Open File");
            ButtonType okButton = new ButtonType("OK");

            alert.getButtonTypes().setAll(openFolderButton, openFileButton, okButton);

            alert.showAndWait().ifPresent(buttonType -> {
                try {
                    File file = new File(filePath);
                    File folder = file.getParentFile();

                    if (buttonType == openFileButton && file.exists()) {
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(file);
                        }
                    } else if (buttonType == openFolderButton && folder.exists()) {
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(folder);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
    }

    // Helper methods
    private String getFileChecksum(File file) {
        // Simple checksum untuk verifikasi
        try (FileInputStream fis = new FileInputStream(file)) {
            long checksum = 0;
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    checksum += buffer[i] & 0xFF;
                }
            }
            return Long.toString(checksum);
        } catch (IOException e) {
            return "0";
        }
    }

    private boolean verifyFile(String filePath, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.equals("0")) {
            return true; // Skip verification if no checksum
        }

        File file = new File(filePath);
        String actualChecksum = getFileChecksum(file);
        return expectedChecksum.equals(actualChecksum);
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        else if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        else if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        else return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    public void shutdown() {
        executorService.shutdownNow();
        pendingDownloads.clear();
        pendingUploads.clear();
    }
}