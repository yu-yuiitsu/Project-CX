package org.yu.projectcx.network.webrtc;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.PayloadParser;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.network.signaling.SignalingEventListener;
import org.yu.projectcx.network.signaling.SignalingManager;
import org.yu.projectcx.network.signaling.SignalingMessage;
import org.yu.projectcx.util.AsyncExecutor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the native WebRTC PeerConnection, DataChannel lifecycle, and P2P streaming.
 * 
 * Connection Pipeline:
 * 1. Create PeerConnection with STUN configuration
 * 2. Create DataChannel ("p2p_chat_channel")
 * 3. Create SDP Offer & dispatch via SignalingManager
 * 4. Ingest SDP Answer & ICE candidates
 * 5. DataChannel state reaches OPEN -> Live direct P2P data transmission!
 */
public class WebRTCManager implements SignalingEventListener {

    private static final Logger logger = LoggerFactory.getLogger(WebRTCManager.class);
    private static final String DATA_CHANNEL_LABEL = "p2p_chat_channel";

    private final String localPeerId;
    private final String remotePeerId;
    private final String remoteHost;
    private final int remotePort;
    private final SignalingManager signalingManager;
    private final List<WebRTCDataChannelListener> listeners;
    private final AtomicBoolean isClosed;
    private final AtomicBoolean isConnecting;

    private PeerConnectionFactory factory;
    private RTCPeerConnection peerConnection;
    private RTCDataChannel dataChannel;
    private PeerConnectionObserver peerConnectionObserver;
    private RTCDataChannelObserver dataChannelObserver;
    private CreateSessionDescriptionObserver offerCreateObserver;
    private SetSessionDescriptionObserver offerSetLocalObserver;
    private CreateSessionDescriptionObserver answerCreateObserver;
    private SetSessionDescriptionObserver answerSetLocalObserver;
    private SetSessionDescriptionObserver answerSetRemoteObserver;
    private SetSessionDescriptionObserver offerSetRemoteObserver;

    public WebRTCManager() {
        this("local_peer", "remote_peer", "127.0.0.1", 8888, null);
    }

    public WebRTCManager(String localPeerId, String remotePeerId, String remoteHost, int remotePort, SignalingManager signalingManager) {
        this.localPeerId = localPeerId;
        this.remotePeerId = remotePeerId;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.signalingManager = signalingManager;
        this.listeners = new CopyOnWriteArrayList<>();
        this.isClosed = new AtomicBoolean(false);
        this.isConnecting = new AtomicBoolean(false);

        if (signalingManager != null) {
            signalingManager.addListener(this);
        }

        initializeWebRTC();
    }

    private void initializeWebRTC() {
        try {
            logger.info("Initializing WebRTC PeerConnection for local [{}] -> remote [{}]", localPeerId, remotePeerId);
            this.factory = new PeerConnectionFactory();

            RTCConfiguration config = new RTCConfiguration();
            RTCIceServer stunServer = new RTCIceServer();
            stunServer.urls = new ArrayList<>(List.of("stun:stun.l.google.com:19302"));
            config.iceServers = new ArrayList<>(List.of(stunServer));

            this.peerConnectionObserver = new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    logger.info("Discovered local ICE candidate: {}", candidate.sdp);
                    if (signalingManager != null) {
                        signalingManager.sendIceCandidate(
                                remoteHost,
                                remotePort,
                                localPeerId,
                                remotePeerId,
                                candidate.sdpMid,
                                candidate.sdpMLineIndex,
                                candidate.sdp
                        );
                    }
                }

                @Override
                public void onDataChannel(RTCDataChannel channel) {
                    logger.info("Incoming DataChannel received from remote peer: {}", channel.getLabel());
                    setDataChannel(channel);
                }

                @Override
                public void onConnectionChange(RTCPeerConnectionState state) {
                    logger.info("WebRTC PeerConnection state changed: {}", state);
                }
            };

            this.peerConnection = factory.createPeerConnection(config, this.peerConnectionObserver);
        } catch (Throwable t) {
            logger.error("Failed to initialize WebRTC PeerConnection subsystem", t);
            notifyError("Initialization error: " + t.getMessage(), t);
        }
    }

    /**
     * Initiates connection as the Caller (creates DataChannel and sends SDP Offer).
     */
    public void createConnectionAsCaller() {
        if (peerConnection == null || isDataChannelOpen() || isClosed.get()) return;
        if (!isConnecting.compareAndSet(false, true)) {
            logger.debug("createConnectionAsCaller already in progress for peer [{}]", remotePeerId);
            return;
        }

        AsyncExecutor.runAsyncNetwork(() -> {
            try {
                if (peerConnection.getSignalingState() != RTCSignalingState.STABLE) {
                    logger.debug("createConnectionAsCaller skipped: signaling state is {}", peerConnection.getSignalingState());
                    isConnecting.set(false);
                    return;
                }
                // 1. Create DataChannel
                RTCDataChannelInit init = new RTCDataChannelInit();
                init.ordered = true;
                RTCDataChannel dc = peerConnection.createDataChannel(DATA_CHANNEL_LABEL, init);
                setDataChannel(dc);

                // 2. Create SDP Offer
                RTCOfferOptions options = new RTCOfferOptions();
                this.offerCreateObserver = new CreateSessionDescriptionObserver() {
                    @Override
                    public void onSuccess(RTCSessionDescription offerDescription) {
                        logger.info("Created local SDP Offer ({} bytes)", offerDescription.sdp.length());
                        offerSetLocalObserver = new SetSessionDescriptionObserver() {
                            @Override
                            public void onSuccess() {
                                logger.info("Set local SDP description. Transmitting Offer via signaling...");
                                if (signalingManager != null) {
                                    signalingManager.sendOffer(remoteHost, remotePort, localPeerId, remotePeerId, offerDescription.sdp);
                                }
                            }

                            @Override
                            public void onFailure(String error) {
                                logger.error("Failed to set local description: {}", error);
                                isConnecting.set(false);
                            }
                        };
                        peerConnection.setLocalDescription(offerDescription, offerSetLocalObserver);
                    }

                    @Override
                    public void onFailure(String error) {
                        logger.error("Failed to create SDP Offer: {}", error);
                        isConnecting.set(false);
                    }
                };
                peerConnection.createOffer(options, this.offerCreateObserver);
            } catch (Exception e) {
                logger.error("Error creating connection as caller", e);
                notifyError("Caller connection error", e);
            }
        });
    }

    /**
     * Responds to an incoming SDP Offer as Callee (creates SDP Answer).
     */
    public void handleRemoteOffer(String offerSdp) {
        if (peerConnection == null || isDataChannelOpen()) return;

        AsyncExecutor.runAsyncNetwork(() -> {
            try {
                if (peerConnection.getSignalingState() == RTCSignalingState.HAVE_LOCAL_OFFER) {
                    if (localPeerId.compareTo(remotePeerId) < 0) {
                        logger.info("Glare detected: local offer takes precedence, ignoring remote offer.");
                        return;
                    }
                }

                RTCSessionDescription remoteOffer = new RTCSessionDescription(RTCSdpType.OFFER, offerSdp);
                this.offerSetRemoteObserver = new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        logger.info("Set remote SDP Offer. Generating SDP Answer...");
                        RTCAnswerOptions options = new RTCAnswerOptions();
                        answerCreateObserver = new CreateSessionDescriptionObserver() {
                            @Override
                            public void onSuccess(RTCSessionDescription answerDescription) {
                                answerSetLocalObserver = new SetSessionDescriptionObserver() {
                                    @Override
                                    public void onSuccess() {
                                        logger.info("Set local SDP Answer. Transmitting Answer via signaling...");
                                        if (signalingManager != null) {
                                            signalingManager.sendAnswer(remoteHost, remotePort, localPeerId, remotePeerId, answerDescription.sdp);
                                        }
                                    }

                                    @Override
                                    public void onFailure(String error) {
                                        logger.error("Failed to set local answer description: {}", error);
                                    }
                                };
                                peerConnection.setLocalDescription(answerDescription, answerSetLocalObserver);
                            }

                            @Override
                            public void onFailure(String error) {
                                logger.error("Failed to create SDP Answer: {}", error);
                            }
                        };
                        peerConnection.createAnswer(options, answerCreateObserver);
                    }

                    @Override
                    public void onFailure(String error) {
                        logger.error("Failed to set remote offer description: {}", error);
                    }
                };
                peerConnection.setRemoteDescription(remoteOffer, this.offerSetRemoteObserver);
            } catch (Exception e) {
                logger.error("Error handling remote offer", e);
            }
        });
    }

    /**
     * Sets the remote SDP Answer received from callee.
     */
    public void handleRemoteAnswer(String answerSdp) {
        if (peerConnection == null) return;

        AsyncExecutor.runAsyncNetwork(() -> {
            RTCSessionDescription remoteAnswer = new RTCSessionDescription(RTCSdpType.ANSWER, answerSdp);
            this.answerSetRemoteObserver = new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    logger.info("Remote SDP Answer accepted. WebRTC ICE negotiation finishing...");
                }

                @Override
                public void onFailure(String error) {
                    logger.error("Failed to set remote answer description: {}", error);
                }
            };
            peerConnection.setRemoteDescription(remoteAnswer, this.answerSetRemoteObserver);
        });
    }

    /**
     * Adds an ICE candidate received via signaling.
     */
    public void handleRemoteIceCandidate(String sdpMid, int sdpMLineIndex, String candidateSdp) {
        if (peerConnection == null) return;

        AsyncExecutor.runAsyncNetwork(() -> {
            try {
                RTCIceCandidate candidate = new RTCIceCandidate(sdpMid, sdpMLineIndex, candidateSdp);
                peerConnection.addIceCandidate(candidate);
                logger.info("Added remote ICE candidate: {}", candidateSdp);
            } catch (Exception e) {
                logger.error("Error adding remote ICE candidate", e);
            }
        });
    }

    // ==========================================
    // DataChannel Management & Data Transmission
    // ==========================================

    private void setDataChannel(RTCDataChannel dc) {
        this.dataChannel = dc;
        this.dataChannelObserver = new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {}

            @Override
            public void onStateChange() {
                try {
                    if (dataChannel != null) {
                        RTCDataChannelState state = dataChannel.getState();
                        logger.info("⚡ WebRTC DataChannel state: {}", state);
                        if (state == RTCDataChannelState.OPEN || state == RTCDataChannelState.CLOSED) {
                            isConnecting.set(false);
                        }
                        notifyDataChannelStateChange(state);
                    }
                } catch (Exception ignored) {}
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                try {
                    byte[] bytes = new byte[buffer.data.remaining()];
                    buffer.data.get(bytes);
                    String rawText = new String(bytes, StandardCharsets.UTF_8);

                    logger.info("📥 Received data on WebRTC DataChannel: {}", rawText);
                    NetworkPayload payload = PayloadParser.parse(rawText);
                    notifyDataPayloadReceived(payload);
                } catch (Exception ex) {
                    logger.error("Error parsing incoming DataChannel buffer", ex);
                }
            }
        };
        this.dataChannel.registerObserver(this.dataChannelObserver);

        if (dc.getState() == RTCDataChannelState.OPEN) {
            notifyDataChannelStateChange(RTCDataChannelState.OPEN);
        }
    }

    /**
     * Sends a TextMessage over the live WebRTC DataChannel.
     */
    public boolean sendTextMessage(TextMessage message) {
        return sendPayload(message);
    }

    /**
     * Sends any polymorphic NetworkPayload over the live WebRTC DataChannel.
     */
    public boolean sendPayload(NetworkPayload payload) {
        if (!isDataChannelOpen()) {
            logger.warn("Cannot send payload: WebRTC DataChannel is not open (State: {})", 
                    dataChannel != null ? dataChannel.getState() : "NULL");
            return false;
        }

        try {
            String serialized = payload.serialize();
            byte[] bytes = serialized.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
            buffer.put(bytes);
            buffer.flip();

            RTCDataChannelBuffer dataBuffer = new RTCDataChannelBuffer(buffer, false);
            dataChannel.send(dataBuffer);
            logger.info("📤 Dispatched payload over WebRTC DataChannel: [{}] \"{}\"", payload.getType(), payload.getSummary());
            return true;
        } catch (Exception e) {
            logger.error("Failed to send payload over WebRTC DataChannel", e);
            notifyError("Send error: " + e.getMessage(), e);
            return false;
        }
    }

    public boolean isDataChannelOpen() {
        if (isClosed.get()) return false;
        RTCDataChannel dc = this.dataChannel;
        if (dc == null) return false;
        try {
            return dc.getState() == RTCDataChannelState.OPEN;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isConnecting() {
        return isConnecting.get();
    }

    public RTCDataChannelState getDataChannelState() {
        if (isClosed.get()) return RTCDataChannelState.CLOSED;
        RTCDataChannel dc = this.dataChannel;
        if (dc == null) return RTCDataChannelState.CLOSED;
        try {
            return dc.getState();
        } catch (Throwable t) {
            return RTCDataChannelState.CLOSED;
        }
    }

    public void close() {
        if (!isClosed.compareAndSet(false, true)) {
            return;
        }
        logger.info("Closing WebRTCManager for peer [{}]", remotePeerId);
        if (signalingManager != null) {
            signalingManager.removeListener(this);
        }
        RTCDataChannel dc = this.dataChannel;
        this.dataChannel = null;
        if (dc != null) {
            try {
                dc.close();
            } catch (Throwable ignored) {}
        }
        RTCPeerConnection pc = this.peerConnection;
        this.peerConnection = null;
        if (pc != null) {
            try {
                pc.close();
            } catch (Throwable ignored) {}
        }
        this.factory = null;
    }

    // ==========================================
    // SignalingEventListener Callbacks
    // ==========================================

    private boolean isMatchingPeer(String senderId) {
        if (senderId == null || remotePeerId == null) return false;
        if (remotePeerId.equalsIgnoreCase(senderId)) return true;
        String s1 = remotePeerId.toLowerCase().replaceAll("[^a-z0-9_]", "");
        String s2 = senderId.toLowerCase().replaceAll("[^a-z0-9_]", "");
        if (s1.equals(s2)) return true;
        String clean1 = s1.replace("peer_", "").replace("peer", "");
        String clean2 = s2.replace("peer_", "").replace("peer", "");
        return clean1.equals(clean2);
    }

    @Override
    public void onOfferReceived(SignalingMessage message) {
        if (isMatchingPeer(message.getSenderPeerId())) {
            logger.info("Received SDP Offer from [{}], handling answer...", remotePeerId);
            handleRemoteOffer(message.getSdp());
        }
    }

    @Override
    public void onAnswerReceived(SignalingMessage message) {
        if (isMatchingPeer(message.getSenderPeerId())) {
            logger.info("Received SDP Answer from [{}], completing handshake...", remotePeerId);
            handleRemoteAnswer(message.getSdp());
        }
    }

    @Override
    public void onIceCandidateReceived(SignalingMessage message) {
        if (isMatchingPeer(message.getSenderPeerId())) {
            handleRemoteIceCandidate(message.getSdpMid(), message.getSdpMLineIndex(), message.getCandidateSdp());
        }
    }

    @Override
    public void onPeerConnected(String remotePeerId, String remoteAddress) {}

    @Override
    public void onPeerDisconnected(String remotePeerId) {
        if (isMatchingPeer(remotePeerId)) {
            close();
        }
    }

    @Override
    public void onSignalingError(String errorMessage, Throwable cause) {
        notifyError(errorMessage, cause);
    }

    // ==========================================
    // Observer Callbacks
    // ==========================================

    public void addListener(WebRTCDataChannelListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(WebRTCDataChannelListener listener) {
        listeners.remove(listener);
    }

    private void notifyDataChannelStateChange(RTCDataChannelState state) {
        for (WebRTCDataChannelListener l : listeners) l.onDataChannelStateChange(state);
    }

    private void notifyDataPayloadReceived(NetworkPayload payload) {
        for (WebRTCDataChannelListener l : listeners) l.onDataPayloadReceived(payload);
    }

    private void notifyError(String error, Throwable cause) {
        for (WebRTCDataChannelListener l : listeners) l.onWebRTCError(error, cause);
    }
}
