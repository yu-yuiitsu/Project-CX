package org.yu.projectcx.network.signaling;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Encapsulated signaling packet exchanged between peers out-of-band to negotiate a WebRTC connection.
 */
public class SignalingMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String messageId;
    private SignalingType type;
    private String senderPeerId;
    private String recipientPeerId;
    private String sdp;
    private String sdpMid;
    private int sdpMLineIndex;
    private String candidateSdp;
    private long timestamp;

    public SignalingMessage() {
        this.messageId = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Constructor for SDP Offer / Answer signaling.
     */
    public SignalingMessage(SignalingType type, String senderPeerId, String recipientPeerId, String sdp) {
        this();
        this.type = Objects.requireNonNull(type, "SignalingType cannot be null");
        this.senderPeerId = Objects.requireNonNull(senderPeerId, "SenderPeerId cannot be null");
        this.recipientPeerId = recipientPeerId;
        this.sdp = sdp;
    }

    /**
     * Constructor for ICE Candidate signaling.
     */
    public SignalingMessage(String senderPeerId, String recipientPeerId, String sdpMid, int sdpMLineIndex, String candidateSdp) {
        this();
        this.type = SignalingType.ICE_CANDIDATE;
        this.senderPeerId = Objects.requireNonNull(senderPeerId, "SenderPeerId cannot be null");
        this.recipientPeerId = recipientPeerId;
        this.sdpMid = sdpMid;
        this.sdpMLineIndex = sdpMLineIndex;
        this.candidateSdp = candidateSdp;
    }

    /**
     * Constructor for Heartbeat or Bye signals.
     */
    public SignalingMessage(SignalingType type, String senderPeerId, String recipientPeerId) {
        this(type, senderPeerId, recipientPeerId, null);
    }

    // ==========================================
    // Serialized Wire Formatting (JSON-like compact format)
    // ==========================================

    /**
     * Serializes this signaling message into a delimited string for TCP socket transmission.
     */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(escape(messageId)).append("||");
        sb.append(type != null ? type.name() : "").append("||");
        sb.append(escape(senderPeerId)).append("||");
        sb.append(escape(recipientPeerId)).append("||");
        sb.append(escape(sdp)).append("||");
        sb.append(escape(sdpMid)).append("||");
        sb.append(sdpMLineIndex).append("||");
        sb.append(escape(candidateSdp)).append("||");
        sb.append(timestamp);
        return sb.toString();
    }

    /**
     * Deserializes a string representation into a SignalingMessage object.
     */
    public static SignalingMessage deserialize(String data) {
        if (data == null || data.trim().isEmpty()) {
            throw new IllegalArgumentException("Cannot deserialize empty signaling data.");
        }

        String[] parts = data.split("\\|\\|", -1);
        if (parts.length < 9) {
            throw new IllegalArgumentException("Invalid signaling packet format. Parts count: " + parts.length);
        }

        SignalingMessage msg = new SignalingMessage();
        msg.setMessageId(unescape(parts[0]));
        msg.setType(SignalingType.valueOf(parts[1]));
        msg.setSenderPeerId(unescape(parts[2]));
        msg.setRecipientPeerId(unescape(parts[3]));
        msg.setSdp(unescape(parts[4]));
        msg.setSdpMid(unescape(parts[5]));
        try {
            msg.setSdpMLineIndex(Integer.parseInt(parts[6]));
        } catch (NumberFormatException e) {
            msg.setSdpMLineIndex(0);
        }
        msg.setCandidateSdp(unescape(parts[7]));
        try {
            msg.setTimestamp(Long.parseLong(parts[8]));
        } catch (NumberFormatException e) {
            msg.setTimestamp(System.currentTimeMillis());
        }

        return msg;
    }

    private static String escape(String val) {
        if (val == null) return "__NULL__";
        return val.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String val) {
        if (val == null || "__NULL__".equals(val)) return null;
        return val.replace("\\r", "\r").replace("\\n", "\n").replace("\\\\", "\\");
    }

    // Getters and Setters

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public SignalingType getType() {
        return type;
    }

    public void setType(SignalingType type) {
        this.type = type;
    }

    public String getSenderPeerId() {
        return senderPeerId;
    }

    public void setSenderPeerId(String senderPeerId) {
        this.senderPeerId = senderPeerId;
    }

    public String getRecipientPeerId() {
        return recipientPeerId;
    }

    public void setRecipientPeerId(String recipientPeerId) {
        this.recipientPeerId = recipientPeerId;
    }

    public String getSdp() {
        return sdp;
    }

    public void setSdp(String sdp) {
        this.sdp = sdp;
    }

    public String getSdpMid() {
        return sdpMid;
    }

    public void setSdpMid(String sdpMid) {
        this.sdpMid = sdpMid;
    }

    public int getSdpMLineIndex() {
        return sdpMLineIndex;
    }

    public void setSdpMLineIndex(int sdpMLineIndex) {
        this.sdpMLineIndex = sdpMLineIndex;
    }

    public String getCandidateSdp() {
        return candidateSdp;
    }

    public void setCandidateSdp(String candidateSdp) {
        this.candidateSdp = candidateSdp;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "SignalingMessage{" +
                "id='" + messageId + '\'' +
                ", type=" + type +
                ", sender='" + senderPeerId + '\'' +
                ", recipient='" + recipientPeerId + '\'' +
                (sdp != null ? ", sdp=" + sdp.length() + " chars" : "") +
                (candidateSdp != null ? ", candidate='" + candidateSdp + '\'' : "") +
                '}';
    }
}
