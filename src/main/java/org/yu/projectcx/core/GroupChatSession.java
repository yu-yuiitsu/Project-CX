package org.yu.projectcx.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.yu.projectcx.util.AsyncExecutor;

/**
 * Manages an active multi-peer group chat session.
 * Coordinates broadcasting messages to all connected peer nodes in full-mesh P2P topology.
 */
public class GroupChatSession {

    private static final Logger logger = LoggerFactory.getLogger(GroupChatSession.class);

    public static final String GROUP_PEER_ID = "group_all_connected";
    public static final String GROUP_ALIAS = "Group Chat";

    private String groupName = "Mesh Group Chat";
    private String groupId = GROUP_PEER_ID;
    private final User localUser;
    private final Map<String, Peer> members;
    private final ChatManager chatManager;

    public GroupChatSession(User localUser, ChatManager chatManager) {
        this.localUser = localUser;
        this.chatManager = chatManager;
        this.members = new ConcurrentHashMap<>();
    }

    public String getGroupName() {
        return (groupName != null && !groupName.trim().isEmpty()) ? groupName : "Mesh Group Chat";
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getGroupId() {
        return groupId != null ? groupId : GROUP_PEER_ID;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public synchronized void setMembers(Collection<Peer> designatedPeers) {
        members.clear();
        if (designatedPeers != null) {
            for (Peer p : designatedPeers) {
                members.put(p.getPeerId(), p);
            }
        }
    }

    public synchronized void syncMembers(Collection<Peer> connectedPeers) {
        members.clear();
        if (connectedPeers != null) {
            for (Peer p : connectedPeers) {
                if (p.getConnectionStatus() == org.yu.projectcx.model.ConnectionStatus.CONNECTED) {
                    members.put(p.getPeerId(), p);
                }
            }
        }
    }

    public void addMember(Peer peer) {
        if (peer != null) {
            members.put(peer.getPeerId(), peer);
        }
    }

    public void removeMember(String peerId) {
        if (peerId != null) {
            members.remove(peerId);
        }
    }

    public List<Peer> getMembers() {
        return new ArrayList<>(members.values());
    }

    public int getMemberCount() {
        return members.size();
    }

    public List<String> getMemberAliases() {
        return members.values().stream().map(Peer::getAlias).sorted().collect(Collectors.toList());
    }

    public String getFormattedMemberList() {
        List<String> aliases = getMemberAliases();
        if (aliases.isEmpty()) return "No other peers";
        return String.join(", ", aliases);
    }

    private final List<NetworkPayload> groupHistory = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Adds an incoming or outgoing payload to the group conversation history.
     */
    public void addGroupPayload(NetworkPayload payload) {
        if (payload == null || payload.getPayloadId() == null) return;
        boolean exists = groupHistory.stream().anyMatch(p -> payload.getPayloadId().equals(p.getPayloadId()));
        if (!exists) {
            // For outgoing broadcast messages, prevent storing multiple identical messages sent at the same second
            if (payload instanceof TextMessage tm && localUser != null && payload.getSenderId().equals(localUser.getUserId())) {
                boolean duplicateOutgoing = groupHistory.stream().anyMatch(p ->
                        p instanceof TextMessage existingTm &&
                        existingTm.getSenderId().equals(localUser.getUserId()) &&
                        existingTm.getMessageContent().equals(tm.getMessageContent()) &&
                        existingTm.getTimestamp() != null && tm.getTimestamp() != null &&
                        Math.abs(java.time.Duration.between(existingTm.getTimestamp(), tm.getTimestamp()).toSeconds()) < 3
                );
                if (duplicateOutgoing) return;
            }
            groupHistory.add(payload);
        }
    }

    public List<NetworkPayload> getGroupConversationHistory() {
        return new ArrayList<>(groupHistory);
    }

    public void clearHistory() {
        groupHistory.clear();
    }

    /**
     * Broadcasts a text message to all active members in the group.
     */
    public CompletableFuture<List<TextMessage>> broadcastTextMessageAsync(String text) {
        List<Peer> currentMembers = getMembers();
        if (currentMembers.isEmpty()) {
            syncMembers(chatManager.getAllPeers());
            currentMembers = getMembers();
        }
        if (currentMembers.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        String messageId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        String senderId = localUser != null ? localUser.getUsername() : "anonymous";
        TextMessage groupMsg = new TextMessage(messageId, senderId, GROUP_PEER_ID, text, now, true, false);

        // 1. Add to group history immediately
        addGroupPayload(groupMsg);

        // 2. Persist to SQLite messages table under GROUP_PEER_ID
        try {
            chatManager.getMessageRepository().saveMessage(groupMsg);
        } catch (Exception e) {
            logger.warn("Failed to persist group message to SQLite: {}", e.getMessage());
        }

        // 3. Notify local UI so sender's screen immediately renders the message
        AsyncExecutor.runOnFxThread(() -> chatManager.notifyMessageDispatched(groupMsg));

        // 4. Dispatch to each member in full-mesh P2P
        List<CompletableFuture<TextMessage>> futures = new ArrayList<>();
        for (Peer peer : currentMembers) {
            futures.add(chatManager.sendGroupPayloadToPeerAsync(groupMsg, peer.getPeerId()));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }
}
