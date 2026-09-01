package org.yu.projectcx.network.webrtc;

import dev.onvoid.webrtc.RTCDataChannelState;
import org.yu.projectcx.model.NetworkPayload;

/**
 * Listener for WebRTC DataChannel connection lifecycle and data stream events.
 */
public interface WebRTCDataChannelListener {

    /**
     * Fired when the WebRTC DataChannel state changes (e.g. CONNECTING, OPEN, CLOSED).
     */
    void onDataChannelStateChange(RTCDataChannelState state);

    /**
     * Fired when a raw binary or text data payload is received over the WebRTC DataChannel.
     */
    void onDataPayloadReceived(NetworkPayload payload);

    /**
     * Fired on WebRTC connection errors.
     */
    void onWebRTCError(String errorMessage, Throwable cause);
}
