package org.yu.projectcx.core;

import dev.onvoid.webrtc.RTCDataChannelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;
import org.yu.projectcx.network.signaling.SignalingClient;
import org.yu.projectcx.network.signaling.SignalingMessage;
import org.yu.projectcx.network.signaling.SignalingType;
import org.yu.projectcx.network.signaling.SignalingManager;
import org.yu.projectcx.network.webrtc.WebRTCDataChannelListener;
import org.yu.projectcx.network.webrtc.WebRTCManager;
import org.yu.projectcx.util.AsyncExecutor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Orchestrates an active point-to-point chat connection between a User and a Peer.
 * 
 * Demonstrates:
 * 1. Method Overloading:
 *    - sendMessage(String text)
 *    - sendMessage(String text, boolean requireDeliveryReceipt)
 *    - sendMessage(NetworkPayload payload)
 *    - sendFile(...)
 * 2. Composition: Owns MessageManager and WebRTCManager.
 * 3. Association: Associates User (local) and Peer (remote).
 * 4. WebRTC DataChannel Integration: Streams text and binary payloads directly P2P.
 */
public class ChatSession {

    private static final Logger logger = LoggerFactory.getLogger(ChatSession.class);

    private final String sessionId;
    private final LocalDateTime startedAt;

    // Association
    private final User localUser;
    private final Peer remotePeer;

    // Composition
    private final MessageManager messageManager;
    private final SignalingManager signalingManager;
    private WebRTCManager webrtcManager;

    private SessionState state;
    private SessionPayloadListener payloadListener;

    public interface SessionPayloadListener {
        void onPayloadReceived(NetworkPayload payload);
        void onSessionStateChanged(SessionState state);
    }

    /**
     * Constructs a new ChatSession associating local user and target peer with default signaling.
     */
    public ChatSession(User localUser, Peer remotePeer) {
        this(localUser, remotePeer, null);
    }

    /**
     * Constructs a new ChatSession with dedicated SignalingManager for WebRTC negotiation.
     */
    public ChatSession(User localUser, Peer remotePeer, SignalingManager signalingManager) {
        if (localUser == null) {
            throw new IllegalArgumentException("Local user cannot be null in ChatSession.");
        }
        if (remotePeer == null) {
            throw new IllegalArgumentException("Remote peer cannot be null in ChatSession.");
        }

        this.sessionId = UUID.randomUUID().toString();
        this.startedAt = LocalDateTime.now();

        this.localUser = localUser;
        this.remotePeer = remotePeer;
        this.signalingManager = signalingManager;

        this.messageManager = new MessageManager();
        initWebRtcManager();

        this.state = SessionState.INITIALIZING;
        logger.info("ChatSession [{}] created between User [{}] and Peer [{}]",
                sessionId, localUser.getUsername(), remotePeer.getAlias());
    }

    private void initWebRtcManager() {
        if (this.webrtcManager != null) {
            try {
                this.webrtcManager.close();
            } catch (Throwable ignored) {}
        }
        this.webrtcManager = new WebRTCManager(
                localUser.getUsername(),
                remotePeer.getPeerId(),
                remotePeer.getIpAddress(),
                remotePeer.getPort(),
                signalingManager
        );

        // Register listener for incoming DataChannel payloads
        this.webrtcManager.addListener(new WebRTCDataChannelListener() {
            @Override
            public void onDataChannelStateChange(RTCDataChannelState dcState) {
                logger.info("Session [{}] WebRTC DataChannel state changed to: {}", sessionId, dcState);
                if (dcState == RTCDataChannelState.OPEN) {
                    state = SessionState.ACTIVE;
                    remotePeer.setOnline(true);
                } else if (dcState == RTCDataChannelState.CLOSED) {
                    state = SessionState.CLOSED;
                    remotePeer.setOnline(false);
                }
                if (payloadListener != null) {
                    payloadListener.onSessionStateChanged(state);
                }
            }

            @Override
            public void onDataPayloadReceived(NetworkPayload payload) {
                logger.info("Session [{}] received DataChannel payload: {}", sessionId, payload.getSummary());
                receivePayload(payload);
            }

            @Override
            public void onWebRTCError(String errorMessage, Throwable cause) {
                logger.error("Session [{}] WebRTC error: {}", sessionId, errorMessage, cause);
            }
        });
    }

    /**
     * Connects the P2P session and initiates WebRTC PeerConnection negotiation.
     */
    public synchronized void connect() {
        if (state == SessionState.CLOSED) {
            logger.info("Session [{}] was CLOSED, re-initializing WebRTC subsystem for reconnect...", sessionId);
            initWebRtcManager();
            this.state = SessionState.INITIALIZING;
        }
        if (webrtcManager != null && (webrtcManager.isDataChannelOpen() || webrtcManager.isConnecting())) {
            this.state = SessionState.ACTIVE;
            this.remotePeer.setOnline(true);
            logger.debug("Session [{}] WebRTC DataChannel is already OPEN or negotiating.", sessionId);
            return;
        }
        if (state == SessionState.CONNECTING) {
            logger.debug("Session [{}] is already CONNECTING - skipping redundant connect", sessionId);
            return;
        }
        this.state = SessionState.CONNECTING;
        logger.info("Connecting session [{}] to peer endpoint: {}", sessionId, remotePeer.getEndpoint());

        try {
            webrtcManager.createConnectionAsCaller();
        } catch (Exception e) {
            logger.warn("Could not initiate WebRTC caller handshake: {}", e.getMessage());
        }

        this.state = SessionState.ACTIVE;
        this.remotePeer.setOnline(true);
        logger.info("Session [{}] is now ACTIVE.", sessionId);
    }

    // ==========================================
    // Method Overloading: sendMessage(...)
    // ==========================================

    /**
     * Overload 1: Dispatches an outgoing text message.
     */
    public synchronized TextMessage sendMessage(String text) {
        return sendMessage(text, true);
    }

    /**
     * Overload 2: Dispatches an outgoing text message with delivery receipt control.
     */
    public synchronized TextMessage sendMessage(String text, boolean requireDeliveryReceipt) {
        if (state == SessionState.CLOSED || state == SessionState.PAUSED) {
            try {
                connect();
            } catch (Exception ignored) {}
        }

        TextMessage message = new TextMessage(localUser.getUsername(), remotePeer.getPeerId(), text);
        message.setDelivered(requireDeliveryReceipt);
        message.validate();

        // 1. Save to session MessageManager
        messageManager.addMessage(message);

        // 2. Transmit over live WebRTC DataChannel (if open) or direct signaling socket
        if (webrtcManager.isDataChannelOpen()) {
            webrtcManager.sendTextMessage(message);
        } else {
            // Trigger connection in background if not yet connected
            if (state != SessionState.CONNECTING && state != SessionState.ACTIVE) {
                try {
                    connect();
                } catch (Exception ignored) {}
            }
            // Send direct signaling chat message to peer's local signaling server
            SignalingMessage chatSigMsg = new SignalingMessage(SignalingType.CHAT_MESSAGE, localUser.getUsername(), remotePeer.getPeerId(), text);
            AsyncExecutor.runAsyncNetwork(() -> {
                try {
                    new SignalingClient().sendMessageDirect(remotePeer.getIpAddress(), remotePeer.getPort(), chatSigMsg);
                    logger.info("Session [{}] dispatched chat message via direct signaling socket to {}:{}", sessionId, remotePeer.getIpAddress(), remotePeer.getPort());
                } catch (Exception e) {
                    logger.warn("Direct signaling socket chat send error to {}:{} - {}", remotePeer.getIpAddress(), remotePeer.getPort(), e.getMessage());
                }
            });
        }

        logger.info("Session [{}] text sent: \"{}\" (Receipt required: {})", sessionId, text, requireDeliveryReceipt);
        return message;
    }

    /**
     * Overload 3: Dispatches any generic / polymorphic NetworkPayload.
     */
    public synchronized NetworkPayload sendMessage(NetworkPayload payload) {
        if (state == SessionState.CLOSED || state == SessionState.PAUSED) {
            try {
                connect();
            } catch (Exception ignored) {}
        }
        if (payload == null) {
            throw new IllegalArgumentException("Cannot send a null payload.");
        }

        payload.validate();
        messageManager.addMessage(payload);

        if (webrtcManager.isDataChannelOpen()) {
            webrtcManager.sendPayload(payload);
        } else {
            // Direct signaling fallback for TextMessage if DataChannel is not open
            if (payload instanceof TextMessage tm) {
                if (state != SessionState.CONNECTING && state != SessionState.ACTIVE) {
                    try { connect(); } catch (Exception ignored) {}
                }
                String recipient = tm.getRecipientId();
                SignalingMessage chatSigMsg = new SignalingMessage(SignalingType.CHAT_MESSAGE, localUser.getUsername(), recipient, tm.getMessageContent());
                AsyncExecutor.runAsyncNetwork(() -> {
                    try {
                        new SignalingClient().sendMessageDirect(remotePeer.getIpAddress(), remotePeer.getPort(), chatSigMsg);
                        logger.info("Session [{}] dispatched payload via direct signaling socket to {}:{}", sessionId, remotePeer.getIpAddress(), remotePeer.getPort());
                    } catch (Exception e) {
                        logger.warn("Direct signaling socket send error to {}:{} - {}", remotePeer.getIpAddress(), remotePeer.getPort(), e.getMessage());
                    }
                });
            }
        }

        logger.info("Session [{}] generic payload sent: [{}] {}", sessionId, payload.getType(), payload.getSummary());
        return payload;
    }

    // ==========================================
    // Method Overloading: sendFile(...)
    // ==========================================

    /**
     * Overload 1: Dispatches an existing FileTransfer payload.
     */
    public synchronized FileTransfer sendFile(FileTransfer fileTransfer) {
        sendMessage(fileTransfer);
        return fileTransfer;
    }

    /**
     * Overload 2: Dispatches a file transfer with standard filename and size.
     */
    public synchronized FileTransfer sendFile(String fileName, long fileSize) {
        return sendFile(fileName, fileSize, null, "application/octet-stream");
    }

    /**
     * Overload 3: Dispatches a file transfer with integrity checksum and MIME type.
     */
    public synchronized FileTransfer sendFile(String fileName, long fileSize, String checksum, String mimeType) {
        FileTransfer fileTransfer = new FileTransfer(
                localUser.getUserId(),
                remotePeer.getPeerId(),
                fileName,
                fileSize,
                checksum,
                mimeType
        );
        return sendFile(fileTransfer);
    }

    /**
     * Receives and processes an incoming payload from the remote peer.
     */
    public synchronized void receivePayload(NetworkPayload payload) {
        if (payload == null) return;
        boolean isGroup = GroupChatSession.GROUP_PEER_ID.equals(payload.getRecipientId());
        if (!isGroup) {
            payload.setSenderId(remotePeer.getPeerId());
            payload.setRecipientId(localUser.getUsername());
        } else {
            if (payload.getSenderId() == null || payload.getSenderId().trim().isEmpty()) {
                payload.setSenderId(remotePeer.getPeerId());
            }
        }
        payload.validate();
        messageManager.addMessage(payload);
        logger.info("Session [{}] received incoming payload: {} (isGroup: {})", sessionId, payload.getPayloadId(), isGroup);

        if (payloadListener != null) {
            payloadListener.onPayloadReceived(payload);
        }
    }

    /**
     * Closes the chat session and disposes composed WebRTC components.
     */
    public synchronized void closeSession() {
        this.state = SessionState.CLOSED;
        this.messageManager.clear();
        this.webrtcManager.close();
        logger.info("ChatSession [{}] closed and composed resources released.", sessionId);
    }

    public void setPayloadListener(SessionPayloadListener payloadListener) {
        this.payloadListener = payloadListener;
    }

    // Getters

    public String getSessionId() {
        return sessionId;
    }

    public User getLocalUser() {
        return localUser;
    }

    public Peer getRemotePeer() {
        return remotePeer;
    }

    public Peer getPeer() {
        return remotePeer;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public WebRTCManager getWebrtcManager() {
        return webrtcManager;
    }

    public SessionState getState() {
        return state;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    @Override
    public String toString() {
        return "ChatSession{" +
                "sessionId='" + sessionId + '\'' +
                ", localUser=" + localUser.getUsername() +
                ", remotePeer=" + remotePeer.getAlias() +
                ", state=" + state +
                ", messageCount=" + messageManager.getMessageCount() +
                '}';
    }
}
