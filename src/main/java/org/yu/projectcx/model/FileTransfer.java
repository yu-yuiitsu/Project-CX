package org.yu.projectcx.model;

import java.time.LocalDateTime;

/**
 * Concrete implementation of a P2P file transfer payload.
 * 
 * Demonstrates:
 * - Inheritance: Extends abstract NetworkPayload.
 * - Method Overriding: Implements serialize(), getSummary(), getEstimatedSize(), validate().
 * - Polymorphism: Enables unified handling alongside TextMessage via NetworkPayload references.
 */
public class FileTransfer extends NetworkPayload {

    private String fileName;
    private long fileSize; // in bytes
    private String fileChecksum; // e.g. SHA-256
    private String mimeType;
    private double transferProgress; // 0.0 to 100.0
    private TransferStatus status;

    /**
     * Default constructor.
     */
    public FileTransfer() {
        super(PayloadType.FILE_TRANSFER);
        this.fileName = "untitled";
        this.fileSize = 0L;
        this.fileChecksum = "";
        this.mimeType = "application/octet-stream";
        this.transferProgress = 0.0;
        this.status = TransferStatus.PENDING;
    }

    /**
     * Overloaded constructor 1: Basic file metadata.
     */
    public FileTransfer(String senderId, String recipientId, String fileName, long fileSize) {
        super(PayloadType.FILE_TRANSFER, senderId, recipientId);
        setFileName(fileName);
        setFileSize(fileSize);
        this.fileChecksum = "";
        this.mimeType = "application/octet-stream";
        this.transferProgress = 0.0;
        this.status = TransferStatus.PENDING;
    }

    /**
     * Overloaded constructor 2: Full checksum & MIME type.
     */
    public FileTransfer(String senderId, String recipientId, String fileName, long fileSize, 
                        String fileChecksum, String mimeType) {
        this(senderId, recipientId, fileName, fileSize);
        setFileChecksum(fileChecksum);
        setMimeType(mimeType);
    }

    /**
     * Overloaded constructor 3: Full parameter constructor.
     */
    public FileTransfer(String payloadId, String senderId, String recipientId, String fileName, 
                        long fileSize, String fileChecksum, String mimeType, double transferProgress, 
                        TransferStatus status, LocalDateTime timestamp) {
        super(payloadId, PayloadType.FILE_TRANSFER, senderId, recipientId, timestamp);
        setFileName(fileName);
        setFileSize(fileSize);
        setFileChecksum(fileChecksum);
        setMimeType(mimeType);
        setTransferProgress(transferProgress);
        setStatus(status);
    }

    // ==========================================
    // Method Overriding (Implementing Abstraction)
    // ==========================================

    @Override
    public String serialize() {
        // Wire format for P2P file transfer handshake
        return String.format("TYPE:FILE|ID:%s|FROM:%s|TO:%s|TIME:%s|NAME:%s|SIZE:%d|HASH:%s|MIME:%s|PROG:%.2f|STAT:%s",
                getPayloadId(),
                getSenderId(),
                getRecipientId(),
                getTimestamp(),
                fileName,
                fileSize,
                fileChecksum,
                mimeType,
                transferProgress,
                status
        );
    }

    @Override
    public String getSummary() {
        return String.format("[File Transfer] %s (%s, %.1f%%, %s)",
                fileName, getFormattedFileSize(), transferProgress, status);
    }

    @Override
    public long getEstimatedSize() {
        // Estimated footprint for transmission (file payload size)
        return fileSize + 128;
    }

    @Override
    public void validate() throws IllegalArgumentException {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("FileTransfer requires a valid non-empty file name.");
        }
        if (fileSize < 0) {
            throw new IllegalArgumentException("FileTransfer size cannot be negative: " + fileSize);
        }
        if (transferProgress < 0.0 || transferProgress > 100.0) {
            throw new IllegalArgumentException("Transfer progress out of bounds [0.0 - 100.0]: " + transferProgress);
        }
    }

    // Getters & Setters

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("File name cannot be null or blank.");
        }
        this.fileName = fileName.trim();
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        if (fileSize < 0) {
            throw new IllegalArgumentException("File size cannot be negative. Given: " + fileSize);
        }
        this.fileSize = fileSize;
    }

    public String getFileChecksum() {
        return fileChecksum;
    }

    public void setFileChecksum(String fileChecksum) {
        this.fileChecksum = (fileChecksum != null) ? fileChecksum.trim() : "";
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = (mimeType != null && !mimeType.trim().isEmpty()) ? mimeType.trim() : "application/octet-stream";
    }

    public double getTransferProgress() {
        return transferProgress;
    }

    public void setTransferProgress(double transferProgress) {
        if (transferProgress < 0.0 || transferProgress > 100.0) {
            throw new IllegalArgumentException("Transfer progress must be between 0.0 and 100.0. Given: " + transferProgress);
        }
        this.transferProgress = transferProgress;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public void setStatus(TransferStatus status) {
        this.status = (status != null) ? status : TransferStatus.PENDING;
    }

    public String getFormattedFileSize() {
        if (fileSize < 1024) {
            return fileSize + " B";
        }
        int exp = (int) (Math.log(fileSize) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.2f %sB", fileSize / Math.pow(1024, exp), pre);
    }

    @Override
    public String toString() {
        return "FileTransfer{" +
                "payloadId='" + getPayloadId() + '\'' +
                ", senderId='" + getSenderId() + '\'' +
                ", recipientId='" + getRecipientId() + '\'' +
                ", fileName='" + fileName + '\'' +
                ", fileSize=" + getFormattedFileSize() +
                ", progress=" + String.format("%.1f%%", transferProgress) +
                ", status=" + status +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
