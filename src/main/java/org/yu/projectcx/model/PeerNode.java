package org.yu.projectcx.model;

import java.time.LocalDateTime;

/**
 * Represents a peer node connection metadata entity.
 */
public record PeerNode(
        long id,
        String peerId,
        String nickname,
        LocalDateTime lastConnected
) {
}
