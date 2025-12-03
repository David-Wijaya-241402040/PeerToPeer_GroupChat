package main.java.app.peer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class FileTransfer {
    private final String fileId;
    private final String fileName;
    private final long fileSize;
    private final String sender;
    private final String expectedChecksum;
    private final Map<Integer, byte[]> chunks;
    private int receivedChunks;
    private long totalReceivedBytes;
    private boolean accepted;
    private long lastActivityTime;

    public FileTransfer(String fileId, String fileName, long fileSize, String sender) {
        this(fileId, fileName, fileSize, sender, null);
    }

    public FileTransfer(String fileId, String fileName, long fileSize, String sender, String checksum) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.sender = sender;
        this.expectedChecksum = checksum;
        this.chunks = new HashMap<>();
        this.receivedChunks = 0;
        this.totalReceivedBytes = 0;
        this.accepted = false;
        this.lastActivityTime = System.currentTimeMillis();
    }

    public synchronized void writeChunk(byte[] data, int chunkNumber) {
        if (data == null || chunkNumber < 0) {
            throw new IllegalArgumentException("Invalid chunk data");
        }

        // Cek apakah chunk ini sudah diterima
        if (chunks.containsKey(chunkNumber)) {
            System.out.println("Warning: Duplicate chunk " + chunkNumber + " for file " + fileName);
            return;
        }

        chunks.put(chunkNumber, data);
        receivedChunks++;
        totalReceivedBytes += data.length;
        lastActivityTime = System.currentTimeMillis();

        // Debug log
        System.out.println("Received chunk " + chunkNumber +
                " for " + fileName +
                " (" + data.length + " bytes)" +
                " - Total: " + totalReceivedBytes + "/" + fileSize);
    }

    public synchronized String complete(String downloadDirectory) throws IOException {
        // Cek jika transfer timeout
        if (System.currentTimeMillis() - lastActivityTime > 30000) {
            throw new IOException("File transfer timeout");
        }

        // Cek jika folder download ada
        Path downloadPath = Paths.get(downloadDirectory);
        if (!Files.exists(downloadPath)) {
            Files.createDirectories(downloadPath);
        }

        // Cek dan handle nama file duplicate
        Path filePath = downloadPath.resolve(fileName);
        int counter = 1;
        while (Files.exists(filePath)) {
            String nameWithoutExt = getFileNameWithoutExtension();
            String ext = getFileExtension();
            filePath = downloadPath.resolve(nameWithoutExt + " (" + counter + ")" + ext);
            counter++;
        }

        // Tulis semua chunks ke file
        try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
            for (int i = 0; i < chunks.size(); i++) {
                byte[] chunk = chunks.get(i);
                if (chunk != null) {
                    fos.write(chunk);
                } else {
                    System.err.println("Warning: Missing chunk " + i + " for file " + fileName);
                }
            }
            fos.flush();
        }

        // Verifikasi size
        long actualSize = Files.size(filePath);
        if (fileSize > 0 && actualSize != fileSize) {
            System.err.println("File size mismatch for " + fileName +
                    ": expected=" + fileSize + ", actual=" + actualSize);
        }

        // Bersihkan memory
        chunks.clear();

        return filePath.toAbsolutePath().toString();
    }

    // Helper methods
    private String getFileNameWithoutExtension() {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? fileName : fileName.substring(0, dotIndex);
    }

    private String getFileExtension() {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex);
    }

    // Getters
    public String getFileName() {
        return fileName;
    }

    public String getSender() {
        return sender;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getExpectedChecksum() {
        return expectedChecksum;
    }

    public double getProgress() {
        return fileSize > 0 ? (double) totalReceivedBytes / fileSize : 0.0;
    }

    public int getReceivedChunks() {
        return receivedChunks;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public boolean isComplete() {
        return fileSize > 0 && totalReceivedBytes >= fileSize;
    }
}