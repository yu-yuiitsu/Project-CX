package org.yu.projectcx.model;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * Concrete implementation of a text chat message.
 * 
 * Demonstrates:
 * - Inheritance: Extends abstract NetworkPayload.
 * - Method Overriding: Implements abstract serialize(), getSummary(), getEstimatedSize(), validate().
 * - Polymorphism: Handled through NetworkPayload references.
 */
public class TextMessage extends NetworkPayload {

    private String messageContent;
    private boolean delivered;
    private boolean read;

    /**
     * Default constructor.
     */
    public TextMessage() {
        super(PayloadType.TEXT);
        this.messageContent = "";
        this.delivered = false;
        this.read = false;
    }

    /**
     * Overloaded constructor 1: Basic text message.
     */
    public TextMessage(String senderId, String recipientId, String messageContent) {
        super(PayloadType.TEXT, senderId, recipientId);
        setMessageContent(messageContent);
        this.delivered = false;
        this.read = false;
    }

    /**
     * Overloaded constructor 2: With delivery/read flags.
     */
    public TextMessage(String senderId, String recipientId, String messageContent, boolean delivered, boolean read) {
        this(senderId, recipientId, messageContent);
        this.delivered = delivered;
        this.read = read;
    }

    /**
     * Overloaded constructor 3: Full parameter constructor.
     */
    public TextMessage(String payloadId, String senderId, String recipientId, String messageContent, 
                       LocalDateTime timestamp, boolean delivered, boolean read) {
        super(payloadId, PayloadType.TEXT, senderId, recipientId, timestamp);
        setMessageContent(messageContent);
        this.delivered = delivered;
        this.read = read;
    }

    // ==========================================
    // Method Overriding (Implementing Abstraction)
    // ==========================================

    @Override
    public String serialize() {
        // Structured wire format for transmission over WebRTC DataChannel
        return String.format("TYPE:TEXT|ID:%s|FROM:%s|TO:%s|TIME:%s|DELIV:%b|READ:%b|BODY:%s",
                getPayloadId(),
                getSenderId(),
                getRecipientId(),
                getTimestamp(),
                delivered,
                read,
                messageContent
        );
    }

    @Override
    public String getSummary() {
        String preview = (messageContent.length() > 30)
                ? messageContent.substring(0, 27) + "..."
                : messageContent;
        return "[Text Message] " + preview;
    }

    @Override
    public long getEstimatedSize() {
        byte[] bytes = messageContent.getBytes(StandardCharsets.UTF_8);
        return bytes.length + 64; // Content size + approximate envelope overhead
    }

    @Override
    public void validate() throws IllegalArgumentException {
        if (messageContent == null) {
            throw new IllegalArgumentException("TextMessage content cannot be null.");
        }
        if (messageContent.length() > 10000) {
            throw new IllegalArgumentException("TextMessage exceeds maximum length of 10,000 characters.");
        }
    }

    // Getters & Setters

    public String getMessageContent() {
        return messageContent;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = (messageContent != null) ? messageContent.trim() : "";
    }

    public boolean isDelivered() {
        return delivered;
    }

    public void setDelivered(boolean delivered) {
        this.delivered = delivered;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    @Override
    public String toString() {
        return "TextMessage{" +
                "payloadId='" + getPayloadId() + '\'' +
                ", senderId='" + getSenderId() + '\'' +
                ", recipientId='" + getRecipientId() + '\'' +
                ", messageContent='" + messageContent + '\'' +
                ", delivered=" + delivered +
                ", read=" + read +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}
