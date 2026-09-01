package org.yu.projectcx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.PayloadType;
import org.yu.projectcx.model.TextMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Manages message tracking, retrieval, and filtering for a chat session.
 * 
 * Demonstrates:
 * - Composition: Owned by ChatSession.
 * - Method Overloading:
 *   - addMessage(NetworkPayload payload)
 *   - addMessage(String senderId, String recipientId, String textContent)
 *   - getMessages(int limit)
 *   - getMessages(PayloadType type)
 *   - getMessages(PayloadType type, int limit)
 */
public class MessageManager {

    private static final Logger logger = LoggerFactory.getLogger(MessageManager.class);
    private final List<NetworkPayload> messages;

    public MessageManager() {
        this.messages = new ArrayList<>();
    }

    // ==========================================
    // Method Overloading: addMessage(...)
    // ==========================================

    /**
     * Overload 1: Appends any concrete NetworkPayload.
     */
    public synchronized void addMessage(NetworkPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null.");
        }
        messages.add(payload);
        logger.debug("Message added: {} (Total: {})", payload.getPayloadId(), messages.size());
    }

    /**
     * Overload 2: Constructs and appends a TextMessage from raw parameters.
     */
    public synchronized TextMessage addMessage(String senderId, String recipientId, String textContent) {
        TextMessage textMsg = new TextMessage(senderId, recipientId, textContent);
        addMessage(textMsg);
        return textMsg;
    }

    // ==========================================
    // Method Overloading: getMessages(...)
    // ==========================================

    /**
     * Returns all recorded messages.
     */
    public synchronized List<NetworkPayload> getAllMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /**
     * Overload 1: Returns the most recent N messages.
     */
    public synchronized List<NetworkPayload> getMessages(int limit) {
        if (limit <= 0) return Collections.emptyList();
        int start = Math.max(0, messages.size() - limit);
        return Collections.unmodifiableList(new ArrayList<>(messages.subList(start, messages.size())));
    }

    /**
     * Overload 2: Returns all messages matching a specific PayloadType.
     */
    public synchronized List<NetworkPayload> getMessages(PayloadType type) {
        if (type == null) return Collections.emptyList();
        return messages.stream()
                .filter(m -> m.getType() == type)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Overload 3: Returns the most recent N messages matching a specific PayloadType.
     */
    public synchronized List<NetworkPayload> getMessages(PayloadType type, int limit) {
        List<NetworkPayload> filtered = getMessages(type);
        if (limit <= 0) return Collections.emptyList();
        int start = Math.max(0, filtered.size() - limit);
        return Collections.unmodifiableList(new ArrayList<>(filtered.subList(start, filtered.size())));
    }

    /**
     * Filters and returns only text chat messages.
     */
    public synchronized List<TextMessage> getTextMessages() {
        List<TextMessage> result = new ArrayList<>();
        for (NetworkPayload p : messages) {
            if (p instanceof TextMessage tm) {
                result.add(tm);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Filters and returns only file transfers.
     */
    public synchronized List<FileTransfer> getFileTransfers() {
        List<FileTransfer> result = new ArrayList<>();
        for (NetworkPayload p : messages) {
            if (p instanceof FileTransfer ft) {
                result.add(ft);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Finds a message by its unique payload ID.
     */
    public synchronized Optional<NetworkPayload> getMessageById(String payloadId) {
        return messages.stream()
                .filter(m -> m.getPayloadId().equals(payloadId))
                .findFirst();
    }

    /**
     * Returns the total count of unread incoming text messages.
     */
    public synchronized int getUnreadCount() {
        int unread = 0;
        for (NetworkPayload p : messages) {
            if (p instanceof TextMessage tm && !tm.isRead()) {
                unread++;
            }
        }
        return unread;
    }

    /**
     * Marks all text messages in this manager as read.
     */
    public synchronized void markAllAsRead() {
        for (NetworkPayload p : messages) {
            if (p instanceof TextMessage tm) {
                tm.setRead(true);
            }
        }
    }

    /**
     * Returns the total number of messages recorded.
     */
    public synchronized int getMessageCount() {
        return messages.size();
    }

    /**
     * Clears all session messages.
     */
    public synchronized void clear() {
        messages.clear();
        logger.debug("MessageManager cleared.");
    }
}
