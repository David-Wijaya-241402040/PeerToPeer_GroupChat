// File: FileTransferManager.java
package main.java.app.peer;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
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

    public FileTransferManager(PeerController controller) {
        this.controller = controller;
        this.executorService = Executors.newCachedThreadPool();
        this.pendingDownloads = new ConcurrentHashMap<>();
        this.pendingUploads = new ConcurrentHashMap<>();

        // Buat folder download di Documents/PeerChatDownloads/
        this.downloadDirectory = System.getProperty("user.home") +
                File.separator + "Documents" +
                File.separator + "PeerChatDownloads" +
                File.separator;

        try {
            Path downloadPath = Paths.get(downloadDirectory);
            if (!Files.exists(downloadPath)) {
                Files.createDirectories(downloadPath);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Kirim file ke satu peer
    public void sendFile(File file, PeerConnection peer) {
        String fileId = UUID.randomUUID().toString();
        FileTransfer upload = new FileTransfer(fileId, file.getName(), file.length(),
                controller.getLocalUsernameSafe());
        pendingUploads.put(fileId, upload);

        executorService.execute(() -> {
            try {
                // Kirim metadata file
                String metadata = String.format("FILE_META|%s|%s|%d|%s",
                        fileId,
                        file.getName(),
                        file.length(),
                        controller.getLocalUsernameSafe());

                peer.sendLine(metadata);

                Platform.runLater(() -> {
                    controller.addMessageBubble(
                            "[System] Sending file '" + file.getName() + "' to " + peer.getRemoteName(),
                            false, true);
                });

                // Tunggu 2 detik untuk konfirmasi
                Thread.sleep(2000);

                // Jika masih pending, lanjutkan pengiriman
                if (pendingUploads.containsKey(fileId)) {
                    // Baca dan kirim file dalam chunks
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[1024 * 8]; // 8KB chunks
                        int bytesRead;
                        int chunkNumber = 0;

                        while ((bytesRead = fis.read(buffer)) != -1) {
                            // Encode chunk ke base64
                            byte[] chunkData = new byte[bytesRead];
                            System.arraycopy(buffer, 0, chunkData, 0, bytesRead);
                            String encodedChunk = Base64.getEncoder().encodeToString(chunkData);

                            // Kirim chunk
                            String chunkMessage = String.format("FILE_CHUNK|%s|%d|%s",
                                    fileId, chunkNumber, encodedChunk);
                            peer.sendLine(chunkMessage);

                            chunkNumber++;
                            Thread.sleep(5); // Delay kecil
                        }

                        // Kirim tanda selesai
                        peer.sendLine(String.format("FILE_END|%s", fileId));

                        Platform.runLater(() -> {
                            controller.addMessageBubble(
                                    "[System] File '" + file.getName() + "' sent successfully to " + peer.getRemoteName(),
                                    false, true);
                        });

                        // Bersihkan dari pending uploads setelah 10 detik
                        Thread.sleep(10000);
                        pendingUploads.remove(fileId);
                    }
                }

            } catch (Exception e) {
                Platform.runLater(() -> {
                    controller.addMessageBubble(
                            "[Error] Failed to send file: " + e.getMessage(),
                            false, true);
                });
                pendingUploads.remove(fileId);
            }
        });
    }

    // Kirim file ke semua peer
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

        for (PeerConnection peer : peers.values()) {
            sendFile(file, peer);
        }
    }

    // Handle incoming file metadata
    public void handleFileMetadata(String fileId, String fileName, long fileSize, String sender, PeerConnection conn) {
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
                    FileTransfer transfer = new FileTransfer(fileId, fileName, fileSize, sender);
                    pendingDownloads.put(fileId, transfer);

                    // Kirim konfirmasi ke pengirim
                    String acceptMsg = String.format("FILE_ACCEPT|%s", fileId);
                    conn.sendLine(acceptMsg);

                    controller.addMessageBubble(
                            "[System] Downloading file '" + fileName + "' from " + sender,
                            false, true);

                    // Tampilkan progress bar
                    controller.showDownloadProgress(fileId, fileName, sender, fileSize);
                } else {
                    // Kirim penolakan ke pengirim
                    String rejectMsg = String.format("FILE_REJECT|%s", fileId);
                    conn.sendLine(rejectMsg);

                    controller.addMessageBubble(
                            "[System] Ignored file '" + fileName + "' from " + sender,
                            false, true);
                }
            });
        });
    }

    // Handle incoming file chunk
// Di FileTransferManager.java, perbaiki method handleFileChunk():
    public void handleFileChunk(String fileId, int chunkNumber, String encodedChunk) {
        FileTransfer transfer = pendingDownloads.get(fileId);
        if (transfer == null) return;

        try {
            byte[] chunkData = Base64.getDecoder().decode(encodedChunk);
            // PERBAIKI DISINI: Parameter urutan benar
            transfer.writeChunk(chunkData, chunkNumber);

            // Update progress bar
            controller.updateDownloadProgress(fileId, transfer.getProgress());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Handle end of file transfer
// Di method handleFileEnd(), tambahkan opsi untuk membuka folder
    public void handleFileEnd(String fileId, PeerConnection conn) {
        FileTransfer transfer = pendingDownloads.remove(fileId);
        if (transfer != null) {
            try {
                // Panggil complete() yang sekarang return path
                String savedPath = transfer.complete(downloadDirectory);

                Platform.runLater(() -> {
                    controller.addMessageBubble(
                            "[System] File '" + transfer.getFileName() +
                                    "' downloaded successfully!\nLocation: " + savedPath,
                            false, true);

                    // Tampilkan notifikasi dengan tombol aksi
                    showDownloadCompleteDialog(transfer.getFileName(), savedPath);

                    // Hapus progress bar
                    controller.removeDownloadProgress(fileId);
                });

            } catch (IOException e) {
                Platform.runLater(() -> {
                    controller.addMessageBubble(
                            "[Error] Failed to save file: " + e.getMessage(),
                            false, true);
                });
            }
        }
    }
    // Method untuk menampilkan dialog setelah download selesai
// Method untuk menampilkan dialog setelah download selesai
    private void showDownloadCompleteDialog(String fileName, String filePath) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Download Complete");
            alert.setHeaderText("File '" + fileName + "' has been downloaded");
            alert.setContentText("File saved to:\n" + filePath);

            // Tambahkan custom buttons
            ButtonType openFileButton = new ButtonType("Open File");
            ButtonType openFolderButton = new ButtonType("Open Folder");
            ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);

            alert.getButtonTypes().setAll(openFileButton, openFolderButton, okButton);

            alert.showAndWait().ifPresent(buttonType -> {
                try {
                    File file = new File(filePath);
                    File folder = file.getParentFile();

                    if (buttonType == openFileButton) {
                        // Buka file langsung
                        if (Desktop.isDesktopSupported()) {
                            Desktop desktop = Desktop.getDesktop();
                            if (file.exists()) {
                                desktop.open(file);
                            }
                        }
                    } else if (buttonType == openFolderButton) {
                        // Buka folder yang berisi file
                        if (Desktop.isDesktopSupported()) {
                            Desktop desktop = Desktop.getDesktop();
                            if (folder.exists()) {
                                desktop.open(folder);
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
    }
    // Tambahkan method untuk mendapatkan lokasi file yang sudah disave
    private String getActualSavedPath(String fileName) {
        Path downloadPath = Paths.get(downloadDirectory);
        Path filePath = downloadPath.resolve(fileName);

        // Cek apakah file ada dengan nama ini
        if (Files.exists(filePath)) {
            return filePath.toString();
        }

        // Cek file dengan penomoran (1), (2), dst
        int counter = 1;
        while (true) {
            String nameWithoutExt = fileName.lastIndexOf('.') > 0 ?
                    fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            String ext = fileName.lastIndexOf('.') > 0 ?
                    fileName.substring(fileName.lastIndexOf('.')) : "";
            Path numberedPath = downloadPath.resolve(
                    nameWithoutExt + " (" + counter + ")" + ext);

            if (Files.exists(numberedPath)) {
                return numberedPath.toString();
            }

            if (counter > 100) break; // Batasan untuk mencegah infinite loop
            counter++;
        }

        return downloadDirectory + fileName;
    }
    // Handle file acceptance from receiver
    public void handleFileAccept(String fileId, PeerConnection conn) {
        FileTransfer transfer = pendingUploads.get(fileId);
        if (transfer != null) {
            Platform.runLater(() -> {
                controller.addMessageBubble(
                        "[System] " + conn.getRemoteName() + " accepted your file '" +
                                transfer.getFileName() + "'",
                        false, true);
            });
        }
    }

    // Handle file rejection from receiver
    public void handleFileReject(String fileId, PeerConnection conn) {
        FileTransfer transfer = pendingUploads.get(fileId);
        if (transfer != null) {
            pendingUploads.remove(fileId);
            Platform.runLater(() -> {
                controller.addMessageBubble(
                        "[System] " + conn.getRemoteName() + " rejected your file '" +
                                transfer.getFileName() + "'",
                        false, true);
            });
        }
    }

    // Format file size untuk display
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