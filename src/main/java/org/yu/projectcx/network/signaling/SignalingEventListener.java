package org.yu.projectcx.network.signaling;

/**
 * Event listener interface for reacting to incoming WebRTC signaling negotiations.
 */
public interface SignalingEventListener {

    /**
     * Triggered when an incoming SDP Offer is received from a remote peer.
     */
    void onOfferReceived(SignalingMessage message);

    /**
     * Triggered when an incoming SDP Answer is received from a remote peer.
     */
    void onAnswerReceived(SignalingMessage message);

    /**
     * Triggered when an incoming ICE candidate is received.
     */
    void onIceCandidateReceived(SignalingMessage message);

    /**
     * Triggered when a remote peer establishes a signaling connection.
     */
    void onPeerConnected(String remotePeerId, String remoteAddress);

    /**
     * Triggered when a remote peer disconnects its signaling link.
     */
    void onPeerDisconnected(String remotePeerId);

    /**
     * Triggered when a direct P2P text message is received over the signaling channel.
     */
    default void onChatMessageReceived(SignalingMessage message) {}

    /**
     * Triggered on network or protocol signaling errors.
     */
    void onSignalingError(String errorMessage, Throwable cause);
}
