// File: FileTransfer.java
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
    private final Map<Integer, byte[]> chunks;
    private int receivedChunks;
    private long totalReceivedBytes;

    public FileTransfer(String fileId, String fileName, long fileSize, String sender) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.sender = sender;
        this.chunks = new HashMap<>();
        this.receivedChunks = 0;
        this.totalReceivedBytes = 0;
    }

    public synchronized boolean isComplete() {
        // Cek apakah semua chunk sudah diterima
        // Ini adalah estimasi sederhana - dalam implementasi real,
        // Anda perlu tahu total berapa chunk yang diharapkan
        return fileSize > 0 && totalReceivedBytes >= fileSize;
    }

    public synchronized int getMissingChunks() {
        int maxChunk = 0;
        for (Integer chunkNum : chunks.keySet()) {
            if (chunkNum > maxChunk) {
                maxChunk = chunkNum;
            }
        }

        // Hitung berapa chunk yang hilang
        int expectedChunks = maxChunk + 1; // jika chunk dimulai dari 0
        return expectedChunks - receivedChunks;
    }

    // PERBAIKI DISINI: Ganti urutan parameter
    public synchronized void writeChunk(byte[] data, int chunkNumber) {
        // Validasi parameter
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }

        if (chunkNumber < 0) {
            throw new IllegalArgumentException("Chunk number cannot be negative");
        }

        // Cek apakah chunk ini sudah diterima sebelumnya
        if (chunks.containsKey(chunkNumber)) {
            System.out.println("Warning: Duplicate chunk received: " + chunkNumber);
            return;
        }

        chunks.put(chunkNumber, data);
        receivedChunks++;
        totalReceivedBytes += data.length;

        // Debug log
        System.out.println("Received chunk " + chunkNumber + " for file " + fileName +
                ", total received: " + receivedChunks + " chunks, " +
                totalReceivedBytes + "/" + fileSize + " bytes");
    }

    public synchronized String complete(String downloadDirectory) throws IOException {
        // Cek jika folder download ada, jika tidak buat
        Path downloadPath = Paths.get(downloadDirectory);
        if (!Files.exists(downloadPath)) {
            Files.createDirectories(downloadPath);
        }

        // Cek jika file sudah ada, tambah (1), (2), dst
        Path filePath = downloadPath.resolve(fileName);
        int counter = 1;
        while (Files.exists(filePath)) {
            String nameWithoutExt = fileName.lastIndexOf('.') > 0 ?
                    fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            String ext = fileName.lastIndexOf('.') > 0 ?
                    fileName.substring(fileName.lastIndexOf('.')) : "";
            filePath = downloadPath.resolve(nameWithoutExt + " (" + counter + ")" + ext);
            counter++;
        }

        // Rekonstruksi file dari chunks
        try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
            for (int i = 0; i < chunks.size(); i++) {
                byte[] chunk = chunks.get(i);
                if (chunk != null) {
                    fos.write(chunk);
                }
            }
        }

        // Bersihkan chunks dari memory
        chunks.clear();

        // Return full path dari file yang disimpan
        return filePath.toString();
    }
    public String getFileName() {
        return fileName;
    }

    public String getSender() {
        return sender;
    }

    public long getFileSize() {
        return fileSize;
    }

    public double getProgress() {
        return fileSize > 0 ? (double) totalReceivedBytes / fileSize : 0.0;
    }

    public int getReceivedChunks() {
        return receivedChunks;
    }
}