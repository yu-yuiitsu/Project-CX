package org.yu.projectcx.core;

/**
 * Lifecycle states of an active P2P ChatSession.
 */
public enum SessionState {
    INITIALIZING,
    CONNECTING,
    ACTIVE,
    PAUSED,
    CLOSED
}
