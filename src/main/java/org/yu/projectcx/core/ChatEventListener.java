package org.yu.projectcx.core;

import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;

import java.util.List;

/**
 * Observer interface for reacting to chat, message, and peer lifecycle events.
 */
public interface ChatEventListener {

    default void onMessageDispatched(TextMessage message) {}

    default void onPayloadDispatched(NetworkPayload payload) {}

    default void onPeerSelected(Peer peer) {}

    default void onPeersUpdated(List<Peer> peers) {}
}
