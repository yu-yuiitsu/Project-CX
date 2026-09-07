package org.yu.projectcx.network.signaling;

/**
 * Event listener interface for reacting to incoming WebRTC signaling negotiations.
 */
public interface SignalingEventListener {

    /**
     * Triggered when an incoming SDP Offer is received from a remote peer.
     */
    default void onOfferReceived(SignalingMessage message) {}

    /**
     * Triggered when an incoming SDP Answer is received from a remote peer.
     */
    default void onAnswerReceived(SignalingMessage message) {}

    /**
     * Triggered when an incoming ICE candidate is received.
     */
    default void onIceCandidateReceived(SignalingMessage message) {}

    /**
     * Triggered when a remote peer establishes a signaling connection.
     */
    default void onPeerConnected(String remotePeerId, String remoteAddress) {}

    /**
     * Triggered when a remote peer disconnects its signaling link.
     */
    default void onPeerDisconnected(String remotePeerId) {}

    /**
     * Triggered when a direct P2P text message is received over the signaling channel.
     */
    default void onChatMessageReceived(SignalingMessage message) {}

    /**
     * Triggered when an incoming connection request is received from a peer.
     */
    default void onConnectionRequestReceived(SignalingMessage message) {}

    /**
     * Triggered when a previously sent connection request is accepted by the remote peer.
     */
    default void onConnectionAccepted(SignalingMessage message) {}

    /**
     * Triggered when a previously sent connection request is rejected by the remote peer.
     */
    default void onConnectionRejected(SignalingMessage message) {}

    /**
     * Triggered when a query for active connected peers is received.
     */
    default void onPeerNetworkQueryReceived(SignalingMessage message) {}

    /**
     * Triggered when a response containing connected peers is received.
     */
    default void onPeerNetworkResponseReceived(SignalingMessage message) {}

    /**
     * Triggered when an incoming request to join a group connection is received.
     */
    default void onGroupJoinRequestReceived(SignalingMessage message) {}

    /**
     * Triggered when an introduction to a new group member is received from a trusted peer.
     */
    default void onGroupIntroduceReceived(SignalingMessage message) {}

    /**
     * Triggered on network or protocol signaling errors.
     */
    default void onSignalingError(String errorMessage, Throwable cause) {}
}
