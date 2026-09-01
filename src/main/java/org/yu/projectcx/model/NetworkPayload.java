package org.yu.projectcx.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Abstract base class representing any data packet transferred across the P2P connection.
 * 
 * Demonstrates:
 * - Abstraction: Cannot be directly instantiated; defines abstract contract methods.
 * - Inheritance: Subclasses (TextMessage, FileTransfer) inherit common network metadata.
 * - Polymorphism: Allows heterogeneous payload handling via common NetworkPayload references.
 */
public abstract class NetworkPayload {

    // Encapsulated common network fields
    private String payloadId;
    private PayloadType type;
    private String senderId;
    private String recipientId;
    private LocalDateTime timestamp;

    /**
     * Default constructor for subclasses.
     */
    protected NetworkPayload(PayloadType type) {
        this.payloadId = UUID.randomUUID().toString();
        this.type = (type != null) ? type : PayloadType.SYSTEM;
        this.senderId = "anonymous";
        this.recipientId = "broadcast";
        this.timestamp = LocalDateTime.now();
    }

    /**
     * Overloaded constructor for subclasses with sender and recipient.
     */
    protected NetworkPayload(PayloadType type, String senderId, String recipientId) {
        this(type);
        setSenderId(senderId);
        setRecipientId(recipientId);
    }

    /**
     * Full parameter constructor for subclasses (e.g. deserialization/hydration).
     */
    protected NetworkPayload(String payloadId, PayloadType type, String senderId, String recipientId, LocalDateTime timestamp) {
        setPayloadId(payloadId);
        this.type = (type != null) ? type : PayloadType.SYSTEM;
        setSenderId(senderId);
        setRecipientId(recipientId);
        this.timestamp = (timestamp != null) ? timestamp : LocalDateTime.now();
    }

    // ==========================================
    // Abstract Methods (Enforcing Abstraction)
    // ==========================================

    /**
     * Serializes this specific payload into a transmission-ready wire format.
     * Must be implemented by concrete subclasses.
     */
    public abstract String serialize();

    /**
     * Returns a polymorphic human-readable summary of this payload for UI display.
     */
    public abstract String getSummary();

    /**
     * Calculates the estimated byte payload size for bandwidth tracking.
     */
    public abstract long getEstimatedSize();

    /**
     * Validates domain constraints specific to the payload subtype.
     * @throws IllegalArgumentException if payload data is invalid.
     */
    public abstract void validate() throws IllegalArgumentException;

    // ==========================================
    // Concrete Template / Shared Methods
    // ==========================================

    /**
     * Formats standardized envelope header info common to all payloads.
     */
    public String getHeaderInfo() {
        return String.format("[%s] From: %s -> To: %s @ %s",
                type, senderId, recipientId, timestamp);
    }

    // Getters & Setters (Encapsulation)

    public String getPayloadId() {
        return payloadId;
    }

    public void setPayloadId(String payloadId) {
        if (payloadId == null || payloadId.trim().isEmpty()) {
            throw new IllegalArgumentException("Payload ID cannot be null or blank.");
        }
        this.payloadId = payloadId.trim();
    }

    public PayloadType getType() {
        return type;
    }

    protected void setType(PayloadType type) {
        this.type = type;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        if (senderId == null || senderId.trim().isEmpty()) {
            throw new IllegalArgumentException("Sender ID cannot be null or blank.");
        }
        this.senderId = senderId.trim();
    }

    public String getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(String recipientId) {
        if (recipientId == null || recipientId.trim().isEmpty()) {
            throw new IllegalArgumentException("Recipient ID cannot be null or blank.");
        }
        this.recipientId = recipientId.trim();
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NetworkPayload that)) return false;
        return Objects.equals(payloadId, that.payloadId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payloadId);
    }

    @Override
    public String toString() {
        return "NetworkPayload{" +
                "payloadId='" + payloadId + '\'' +
                ", type=" + type +
                ", senderId='" + senderId + '\'' +
                ", recipientId='" + recipientId + '\'' +
                ", summary='" + getSummary() + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
