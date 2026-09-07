package org.yu.projectcx.model;

/**
 * Lifecycle status of a P2P relationship between two peers.
 */
public enum ConnectionStatus {
    /**
     * Peer discovered on LAN or added manually, but no connection request sent yet.
     */
    DISCOVERED,

    /**
     * Local user has sent a connection request to this peer; waiting for approval.
     */
    REQUEST_SENT,

    /**
     * This peer has sent an incoming connection request to the local user; awaiting user decision.
     */
    REQUEST_RECEIVED,

    /**
     * Connection request has been accepted by both sides; full messaging & WebRTC streaming enabled.
     */
    CONNECTED,

    /**
     * Connection request was declined or blocked.
     */
    REJECTED
}
