package org.yu.projectcx.core;

import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;

import java.util.List;

/**
 * Observer interface for reacting to chat, message, and peer lifecycle events.
 */
public interface ChatEventListener {

    void onMessageDispatched(TextMessage message);

    void onPayloadDispatched(NetworkPayload payload);

    void onPeerSelected(Peer peer);

    void onPeersUpdated(List<Peer> peers);
}
