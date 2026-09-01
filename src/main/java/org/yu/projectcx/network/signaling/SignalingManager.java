package org.yu.projectcx.network.signaling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * High-level coordinator managing peer-to-peer WebRTC signaling negotiations.
 * Supports both:
 * 1. Direct Local P2P Signaling Server (LAN/Direct Port)
 * 2. Internet-Hosted Cloud Signaling Server (VPS/Cloud Relay)
 */
public class SignalingManager {

    private static final Logger logger = LoggerFactory.getLogger(SignalingManager.class);
    public static final int DEFAULT_SIGNALING_PORT = 8888;

    private final SignalingServer server;
    private final SignalingClient client;
    private final List<SignalingEventListener> listeners;
    private int localPort;

    public SignalingManager() {
        this(DEFAULT_SIGNALING_PORT);
    }

    public SignalingManager(int localPort) {
        this.localPort = localPort;
        this.server = new SignalingServer(localPort);
        this.client = new SignalingClient();
        this.listeners = new CopyOnWriteArrayList<>();

        // Forward events from local server
        this.server.addListener(createBridgeListener());

        // Forward events from hosted server client
        this.client.addListener(createBridgeListener());
    }

    private SignalingEventListener createBridgeListener() {
        return new SignalingEventListener() {
            @Override
            public void onOfferReceived(SignalingMessage message) {
                for (SignalingEventListener l : listeners) l.onOfferReceived(message);
            }

            @Override
            public void onAnswerReceived(SignalingMessage message) {
                for (SignalingEventListener l : listeners) l.onAnswerReceived(message);
            }

            @Override
            public void onIceCandidateReceived(SignalingMessage message) {
                for (SignalingEventListener l : listeners) l.onIceCandidateReceived(message);
            }

            @Override
            public void onPeerConnected(String remotePeerId, String remoteAddress) {
                for (SignalingEventListener l : listeners) l.onPeerConnected(remotePeerId, remoteAddress);
            }

            @Override
            public void onPeerDisconnected(String remotePeerId) {
                for (SignalingEventListener l : listeners) l.onPeerDisconnected(remotePeerId);
            }

            @Override
            public void onChatMessageReceived(SignalingMessage message) {
                for (SignalingEventListener l : listeners) l.onChatMessageReceived(message);
            }

            @Override
            public void onSignalingError(String errorMessage, Throwable cause) {
                for (SignalingEventListener l : listeners) l.onSignalingError(errorMessage, cause);
            }
        };
    }

    /**
     * Starts the local signaling listener server.
     */
    public synchronized void start() throws IOException {
        server.start();
        this.localPort = server.getPort();
        logger.info("SignalingManager local server listening on port {}", localPort);
    }

    /**
     * Connects to a cloud/Internet Hosted Signaling Server on a remote VPS.
     */
    public CompletableFuture<Void> connectToHostedServer(String host, int port, String localPeerId) {
        logger.info("Connecting to Hosted Signaling Server at {}:{} for local peer [{}]", host, port, localPeerId);
        return client.connectToHostedServerAsync(host, port, localPeerId);
    }

    /**
     * Stops local signaling server and active channels.
     */
    public synchronized void stop() {
        server.stop();
        client.disconnect();
        logger.info("SignalingManager stopped.");
    }

    // ==========================================
    // Outgoing Signaling Transmissions
    // ==========================================

    /**
     * Transmits an SDP Offer to a remote peer.
     */
    public CompletableFuture<Void> sendOffer(String host, int port, String localPeerId, String targetPeerId, String sdp) {
        SignalingMessage msg = new SignalingMessage(SignalingType.OFFER, localPeerId, targetPeerId, sdp);
        return client.sendMessageAsync(host, port, msg);
    }

    /**
     * Transmits an SDP Answer in response to an Offer from a remote peer.
     */
    public CompletableFuture<Void> sendAnswer(String host, int port, String localPeerId, String targetPeerId, String sdp) {
        SignalingMessage msg = new SignalingMessage(SignalingType.ANSWER, localPeerId, targetPeerId, sdp);
        return client.sendMessageAsync(host, port, msg);
    }

    /**
     * Transmits a discovered ICE candidate to a remote peer.
     */
    public CompletableFuture<Void> sendIceCandidate(String host, int port, String localPeerId, String targetPeerId,
                                                    String sdpMid, int sdpMLineIndex, String candidateSdp) {
        SignalingMessage msg = new SignalingMessage(localPeerId, targetPeerId, sdpMid, sdpMLineIndex, candidateSdp);
        return client.sendMessageAsync(host, port, msg);
    }

    public CompletableFuture<Void> sendHeartbeat(String host, int port, String localPeerId, String targetPeerId) {
        SignalingMessage msg = new SignalingMessage(SignalingType.HEARTBEAT, localPeerId, targetPeerId);
        return client.sendMessageAsync(host, port, msg);
    }

    public CompletableFuture<Void> sendBye(String host, int port, String localPeerId, String targetPeerId) {
        SignalingMessage msg = new SignalingMessage(SignalingType.BYE, localPeerId, targetPeerId);
        return client.sendMessageAsync(host, port, msg);
    }

    public void addListener(SignalingEventListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(SignalingEventListener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        return server.isRunning() || client.isConnected();
    }

    public int getLocalPort() {
        return server.getPort();
    }

    public SignalingClient getClient() {
        return client;
    }

    public SignalingServer getServer() {
        return server;
    }
}
