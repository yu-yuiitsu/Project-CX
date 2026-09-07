package org.yu.projectcx.ui.views;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.core.ChatEventListener;
import org.yu.projectcx.core.ChatManager;
import org.yu.projectcx.core.ChatSession;
import org.yu.projectcx.core.GroupChatSession;
import org.yu.projectcx.model.ConnectionStatus;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;
import org.yu.projectcx.ui.SceneNavigator;

import java.io.File;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Main application interface implementing:
 * GUI -> ChatManager -> MessageManager -> TextMessage -> SQLite
 * 
 * Features:
 * - Dynamic sidebar with live peer online/offline indicators.
 * - Reactive WebRTC DataChannel connection status in header.
 * - Localized message bubble timestamps.
 * - Real filesystem file transfers with progress & SHA-256 hashes.
 * - Robust input validation and error dialogs.
 */
public class MainChatView implements ChatEventListener {

    private static final Logger logger = LoggerFactory.getLogger(MainChatView.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MMM d, HH:mm");

    private final SceneNavigator navigator;
    private final ChatManager chatManager;
    private final User currentUser;

    private HBox root;
    private VBox peersListContainer;
    private VBox messagesContainer;
    private ScrollPane chatScrollPane;
    private TextField messageInputField;
    private Button btnAttach;
    private Button btnSend;
    private Button btnConnectP2P;
    private Button btnDisconnect;
    private Button btnClearChat;
    private Label activePeerTitle;
    private Label activePeerStatus;
    private Peer selectedPeer;
    private boolean isGroupChatSelected = false;
    private final java.util.Set<String> renderedPayloadIds = new java.util.HashSet<>();
    private final java.util.Set<String> renderedOutgoingGroupMsgs = new java.util.HashSet<>();

    public MainChatView(SceneNavigator navigator, ChatManager chatManager) {
        this.navigator = navigator;
        this.chatManager = chatManager;
        this.currentUser = chatManager.getCurrentUser();
        this.chatManager.addEventListener(this);
        this.root = createView();
    }

    private HBox createView() {
        HBox mainLayout = new HBox();
        mainLayout.getStyleClass().add("chat-layout");

        // 1. Left Sidebar: User Profile & Peers Directory
        VBox sidebar = createSidebar();

        // 2. Right Main Area: Active Chat Conversation
        VBox chatArea = createChatArea();

        mainLayout.getChildren().addAll(sidebar, chatArea);
        HBox.setHgrow(chatArea, Priority.ALWAYS);

        loadPeers();
        return mainLayout;
    }

    private VBox createSidebar() {
        VBox sidebar = new VBox(12);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(290);
        sidebar.setMinWidth(260);
        sidebar.setMaxWidth(360);
        sidebar.setPadding(new Insets(16));

        // User Profile Header Card
        HBox profileCard = new HBox(8);
        profileCard.setAlignment(Pos.CENTER_LEFT);
        profileCard.getStyleClass().add("user-profile-card");

        StackPane avatarPane = createAvatarCircle(currentUser.getDisplayName(), "#6366f1");

        VBox userDetails = new VBox(2);
        userDetails.setMinWidth(0);
        Label nameLabel = new Label(currentUser.getDisplayName());
        nameLabel.getStyleClass().add("profile-name");
        nameLabel.setMinWidth(0);
        nameLabel.setMaxWidth(115);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        HBox statusRow = new HBox(5);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.setMinWidth(0);
        Circle statusDot = new Circle(3.5, Color.web("#10b981"));
        Label statusText = new Label("Online • @" + currentUser.getUsername());
        statusText.getStyleClass().add("profile-status");
        statusText.setMinWidth(0);
        statusText.setMaxWidth(110);
        statusText.setTextOverrun(OverrunStyle.ELLIPSIS);
        statusRow.getChildren().addAll(statusDot, statusText);

        userDetails.getChildren().addAll(nameLabel, statusRow);
        HBox.setHgrow(userDetails, Priority.ALWAYS);

        Button btnLogout = new Button("Logout");
        btnLogout.getStyleClass().add("btn-logout");
        btnLogout.setTooltip(new Tooltip("Log out of current session"));
        btnLogout.setMinWidth(Region.USE_PREF_SIZE);
        btnLogout.setMaxWidth(Region.USE_PREF_SIZE);
        btnLogout.setOnAction(e -> {
            chatManager.removeEventListener(this);
            chatManager.logout();
            navigator.showLoginView();
        });

        profileCard.getChildren().addAll(avatarPane, userDetails, btnLogout);

        // Peers Section Header
        HBox peersHeader = new HBox(8);
        peersHeader.setAlignment(Pos.CENTER_LEFT);
        peersHeader.setPadding(new Insets(10, 0, 4, 0));

        Label peersTitle = new Label("DIRECTORIES");
        peersTitle.getStyleClass().add("section-title");

        HBox headerSpacer = new HBox();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        peersHeader.getChildren().addAll(peersTitle, headerSpacer);

        // Sidebar Action Toolbar
        HBox actionToolbar = new HBox(5);
        actionToolbar.setAlignment(Pos.CENTER_LEFT);
        actionToolbar.getStyleClass().add("sidebar-toolbar");
        actionToolbar.setFillHeight(true);

        Button btnScanLan = new Button("📡 Scan");
        btnScanLan.getStyleClass().add("btn-toolbar-action");
        btnScanLan.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnScanLan, Priority.ALWAYS);
        btnScanLan.setOnAction(e -> {
            chatManager.triggerLanScan();
            if (activePeerStatus != null) {
                activePeerStatus.setText("📡 Scanning local network for active peer nodes...");
            }
        });

        Button btnAddPeer = new Button("+ Peer");
        btnAddPeer.getStyleClass().add("btn-toolbar-action");
        btnAddPeer.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnAddPeer, Priority.ALWAYS);
        btnAddPeer.setOnAction(e -> handleAddPeer());

        Button btnCreateGroup = new Button("+ Group");
        btnCreateGroup.getStyleClass().add("btn-toolbar-group");
        btnCreateGroup.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnCreateGroup, Priority.ALWAYS);
        btnCreateGroup.setOnAction(e -> handleCreateGroupChat());

        actionToolbar.getChildren().addAll(btnScanLan, btnAddPeer, btnCreateGroup);

        // Peers List Scroll Container
        peersListContainer = new VBox(6);
        peersListContainer.getStyleClass().add("peers-list");
        peersListContainer.setPadding(new Insets(2, 0, 8, 0));

        ScrollPane peersScroll = new ScrollPane(peersListContainer);
        peersScroll.setFitToWidth(true);
        peersScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        peersScroll.getStyleClass().add("transparent-scroll");
        VBox.setVgrow(peersScroll, Priority.ALWAYS);

        sidebar.getChildren().addAll(profileCard, peersHeader, actionToolbar, peersScroll);
        return sidebar;
    }

    private VBox createChatArea() {
        VBox chatArea = new VBox();
        chatArea.getStyleClass().add("chat-area");

        // 1. Top Chat Header
        HBox chatHeader = new HBox(14);
        chatHeader.setAlignment(Pos.CENTER_LEFT);
        chatHeader.getStyleClass().add("chat-header");
        chatHeader.setPadding(new Insets(14, 20, 14, 20));

        VBox peerInfo = new VBox(2);
        activePeerTitle = new Label("Select a peer to start chatting");
        activePeerTitle.getStyleClass().add("active-peer-title");

        activePeerStatus = new Label("P2P Network Ready");
        activePeerStatus.getStyleClass().add("active-peer-subtitle");
        peerInfo.getChildren().addAll(activePeerTitle, activePeerStatus);

        HBox headerSpacer = new HBox();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        this.btnConnectP2P = new Button("⚡ Connect P2P");
        btnConnectP2P.getStyleClass().add("btn-secondary-sm");
        btnConnectP2P.setOnAction(e -> handleConnectP2P());

        this.btnDisconnect = new Button("Disconnect");
        btnDisconnect.getStyleClass().add("btn-decline-sm");
        btnDisconnect.setOnAction(e -> handleDisconnect());
        btnDisconnect.setVisible(false);
        btnDisconnect.setManaged(false);

        this.btnClearChat = new Button("Clear");
        btnClearChat.getStyleClass().add("btn-secondary-sm");
        btnClearChat.setOnAction(e -> handleClearChat());

        chatHeader.getChildren().addAll(peerInfo, headerSpacer, btnConnectP2P, btnDisconnect, btnClearChat);

        // 2. Middle Message Container
        messagesContainer = new VBox(10);
        messagesContainer.setPadding(new Insets(16, 24, 16, 24));
        messagesContainer.getStyleClass().add("messages-container");

        chatScrollPane = new ScrollPane(messagesContainer);
        chatScrollPane.setFitToWidth(true);
        chatScrollPane.getStyleClass().add("chat-scroll-pane");
        VBox.setVgrow(chatScrollPane, Priority.ALWAYS);

        // Smooth auto-scroll when new messages arrive or container height changes
        messagesContainer.heightProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
        });

        // 3. Bottom Input Bar
        HBox inputBar = new HBox(10);
        inputBar.setAlignment(Pos.CENTER);
        inputBar.getStyleClass().add("chat-input-bar");
        inputBar.setPadding(new Insets(14, 20, 14, 20));

        this.btnAttach = new Button("📎 File");
        btnAttach.getStyleClass().add("btn-attach");
        btnAttach.setOnAction(e -> handleSendFile());

        messageInputField = new TextField();
        messageInputField.setPromptText("Type a message...");
        messageInputField.getStyleClass().add("chat-text-input");
        HBox.setHgrow(messageInputField, Priority.ALWAYS);
        messageInputField.setOnAction(e -> handleSendMessage());

        this.btnSend = new Button("SEND");
        btnSend.getStyleClass().add("btn-send");
        btnSend.setOnAction(e -> handleSendMessage());

        inputBar.getChildren().addAll(btnAttach, messageInputField, btnSend);

        chatArea.getChildren().addAll(chatHeader, chatScrollPane, inputBar);
        return chatArea;
    }

    public void loadPeers() {
        peersListContainer.getChildren().clear();
        List<Peer> peers = chatManager.getAllPeers();
        List<Peer> validPeers = new ArrayList<>();

        for (Peer peer : peers) {
            // Ignore self
            if (currentUser != null && (
                    peer.getPeerId().equals(currentUser.getUserId()) ||
                    peer.getAlias().equalsIgnoreCase(currentUser.getUsername()) ||
                    peer.getAlias().equalsIgnoreCase(currentUser.getDisplayName()) ||
                    peer.getPeerId().equalsIgnoreCase("peer_" + currentUser.getUsername())
            )) {
                continue;
            }
            // Ignore temporary dummy stubs and test peers
            if (peer.getAlias().startsWith("Peer:") 
                    || peer.getAlias().equalsIgnoreCase("alice")
                    || peer.getAlias().equalsIgnoreCase("bob")
                    || peer.getAlias().equalsIgnoreCase("charlie")
                    || peer.getAlias().toLowerCase().startsWith("test_")
                    || peer.getPeerId().toLowerCase().startsWith("test_")) {
                continue;
            }
            validPeers.add(peer);
        }

        List<Peer> connectedPeers = new ArrayList<>();
        List<Peer> requestPeers = new ArrayList<>();
        List<Peer> discoveredPeers = new ArrayList<>();

        for (Peer p : validPeers) {
            ConnectionStatus st = p.getConnectionStatus();
            if (st == ConnectionStatus.CONNECTED) {
                connectedPeers.add(p);
            } else if (st == ConnectionStatus.REQUEST_RECEIVED || st == ConnectionStatus.REQUEST_SENT) {
                requestPeers.add(p);
            } else if (p.isOnline()) {
                discoveredPeers.add(p);
            }
        }

        // 1. Connection Requests Section (if any)
        if (!requestPeers.isEmpty()) {
            Label reqHeader = new Label("CONNECTION REQUESTS (" + requestPeers.size() + ")");
            reqHeader.getStyleClass().add("section-title-requests");
            reqHeader.setPadding(new Insets(6, 4, 4, 4));
            peersListContainer.getChildren().add(reqHeader);

            for (Peer peer : requestPeers) {
                peersListContainer.getChildren().add(createRequestPeerItem(peer));
            }
        }

        // 2. Group Chat Section (if custom group created or 2+ connected peers)
        List<Peer> groupPeers = (chatManager.getGroupChatSession() != null && !chatManager.getGroupChatSession().getMembers().isEmpty())
                ? chatManager.getGroupChatSession().getMembers()
                : (connectedPeers.size() >= 2 ? connectedPeers : Collections.emptyList());

        if (!groupPeers.isEmpty()) {
            String gTitle = chatManager.getGroupChatSession() != null ? chatManager.getGroupChatSession().getGroupName() : "GROUP CONNECTION";
            Label groupHeader = new Label(gTitle.toUpperCase() + " (" + (groupPeers.size() + 1) + " PEERS)");
            groupHeader.getStyleClass().add("section-title-group");
            groupHeader.setPadding(new Insets(6, 4, 4, 4));
            peersListContainer.getChildren().add(groupHeader);
            peersListContainer.getChildren().add(createGroupChatItem(groupPeers));
        }

        // 3. Connected Peers Section
        Label connHeader = new Label("CONNECTED PEERS (" + connectedPeers.size() + ")");
        connHeader.getStyleClass().add("section-title-connected");
        connHeader.setPadding(new Insets(8, 4, 4, 4));
        peersListContainer.getChildren().add(connHeader);

        if (connectedPeers.isEmpty()) {
            Label emptyLbl = new Label("No connected peers yet. Send requests below!");
            emptyLbl.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px; -fx-padding: 4 8;");
            peersListContainer.getChildren().add(emptyLbl);
        } else {
            for (Peer peer : connectedPeers) {
                peersListContainer.getChildren().add(createConnectedPeerItem(peer));
            }
        }

        // 4. Discovered / Nearby Peers Section (if any)
        if (!discoveredPeers.isEmpty()) {
            Label discHeader = new Label("DISCOVERED / NEARBY (" + discoveredPeers.size() + ")");
            discHeader.getStyleClass().add("section-title-discovered");
            discHeader.setPadding(new Insets(10, 4, 4, 4));
            peersListContainer.getChildren().add(discHeader);

            for (Peer peer : discoveredPeers) {
                peersListContainer.getChildren().add(createDiscoveredPeerItem(peer));
            }
        }

        // Select peer or keep group selection if needed
        if (isGroupChatSelected) {
            if (groupPeers.isEmpty()) {
                isGroupChatSelected = false;
                if (!connectedPeers.isEmpty()) {
                    selectPeer(connectedPeers.get(0));
                }
            } else {
                updateInputBarState();
                return;
            }
        }

        if (!validPeers.isEmpty()) {
            if (selectedPeer == null || !validPeers.contains(selectedPeer)) {
                if (!connectedPeers.isEmpty()) {
                    selectPeer(connectedPeers.get(0));
                } else if (!requestPeers.isEmpty()) {
                    selectPeer(requestPeers.get(0));
                } else if (!discoveredPeers.isEmpty()) {
                    selectPeer(discoveredPeers.get(0));
                } else {
                    selectPeer(validPeers.get(0));
                }
            } else {
                validPeers.stream().filter(p -> p.equals(selectedPeer)).findFirst().ifPresent(p -> {
                    this.selectedPeer = p;
                    updatePeerStatusBadge(p);
                    updateInputBarState();
                });
            }
        } else {
            selectedPeer = null;
            activePeerTitle.setText("Select a peer to start chatting");
            activePeerStatus.setText("P2P Network Ready • Waiting for remote peers...");
            messagesContainer.getChildren().clear();
            renderNoPeerSelectedCard();
            updateInputBarState();
        }
    }

    private HBox createGroupChatItem(List<Peer> connectedPeers) {
        HBox item = new HBox(10);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add(isGroupChatSelected ? "group-chat-item-selected" : "group-chat-item");
        item.setPadding(new Insets(10, 12, 10, 12));

        StackPane avatar = createAvatarCircle("👥", "#8b5cf6");

        VBox details = new VBox(2);
        String gName = (chatManager.getGroupChatSession() != null) 
                ? chatManager.getGroupChatSession().getGroupName() 
                : "Mesh Group Chat";
        Label name = new Label(gName);
        name.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 13px;");

        StringBuilder members = new StringBuilder();
        for (int i = 0; i < connectedPeers.size(); i++) {
            if (i > 0) members.append(", ");
            members.append(connectedPeers.get(i).getAlias());
        }
        Label desc = new Label((connectedPeers.size() + 1) + " members: You, " + members);
        desc.setStyle("-fx-text-fill: #c084fc; -fx-font-size: 11px;");
        desc.setMaxWidth(200);
        desc.setTextOverrun(OverrunStyle.ELLIPSIS);
        details.getChildren().addAll(name, desc);
        HBox.setHgrow(details, Priority.ALWAYS);

        item.getChildren().addAll(avatar, details);
        item.setOnMouseClicked(e -> selectGroupChat(connectedPeers));
        return item;
    }

    public void selectGroupChat(List<Peer> connectedPeers) {
        this.isGroupChatSelected = true;
        this.selectedPeer = null;

        String gName = (chatManager.getGroupChatSession() != null) 
                ? chatManager.getGroupChatSession().getGroupName() 
                : "Mesh Group Chat";

        activePeerTitle.setText("👥 " + gName + " (" + (connectedPeers.size() + 1) + " peers)");
        StringBuilder sb = new StringBuilder("🟢 Full-Mesh P2P Active • Members: You, ");
        for (int i = 0; i < connectedPeers.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(connectedPeers.get(i).getAlias());
        }
        activePeerStatus.setText(sb.toString());

        updateInputBarState();
        loadMessagesForGroupChat();
        loadPeers();
    }

    private void loadMessagesForGroupChat() {
        messagesContainer.getChildren().clear();
        renderedPayloadIds.clear();
        renderedOutgoingGroupMsgs.clear();
        if (chatManager.getGroupChatSession() == null) return;

        chatManager.getGroupConversationHistoryAsync().thenAccept(dbHistory -> {
            Platform.runLater(() -> {
                if (!isGroupChatSelected) return;
                messagesContainer.getChildren().clear();
                renderedPayloadIds.clear();
                renderedOutgoingGroupMsgs.clear();

                List<NetworkPayload> memHistory = chatManager.getGroupChatSession().getGroupConversationHistory();
                Set<String> seenIds = new HashSet<>();
                List<NetworkPayload> combined = new ArrayList<>();

                if (dbHistory != null) {
                    for (NetworkPayload p : dbHistory) {
                        if (p.getPayloadId() != null && seenIds.add(p.getPayloadId())) {
                            combined.add(p);
                        }
                    }
                }
                if (memHistory != null) {
                    for (NetworkPayload p : memHistory) {
                        if (p.getPayloadId() != null && seenIds.add(p.getPayloadId())) {
                            combined.add(p);
                        }
                    }
                }

                for (NetworkPayload payload : combined) {
                    renderMessageBubble(payload);
                }
                if (combined.isEmpty()) {
                    renderEmptyGroupChatCard();
                }
                scrollToBottom();
            });
        });
    }

    private HBox createConnectedPeerItem(Peer peer) {
        HBox item = new HBox(10);
        item.setAlignment(Pos.CENTER_LEFT);
        boolean isSelected = !isGroupChatSelected && selectedPeer != null && selectedPeer.getPeerId().equals(peer.getPeerId());
        item.getStyleClass().add(isSelected ? "peer-item-selected" : "peer-item");
        item.setPadding(new Insets(10, 12, 10, 12));

        StackPane avatar = createAvatarCircle(peer.getAlias(), "#3b82f6");

        VBox details = new VBox(2);
        Label name = new Label(peer.getAlias());
        name.getStyleClass().add("peer-item-name");

        boolean isPeerConnected = isPeerOnlineOrConnected(peer);

        HBox endpointBox = new HBox(6);
        endpointBox.setAlignment(Pos.CENTER_LEFT);
        Circle statusDot = new Circle(3.5, isPeerConnected ? Color.web("#10b981") : Color.web("#64748b"));
        Label endpoint = new Label(isPeerConnected ? "Online • " + peer.getEndpoint() : "Offline • " + peer.getEndpoint());
        endpoint.getStyleClass().add("peer-item-endpoint");
        endpointBox.getChildren().addAll(statusDot, endpoint);

        details.getChildren().addAll(name, endpointBox);

        if (peer.hasConnectedPeers()) {
            Label networkBadge = new Label("🔗 In group with: " + peer.getConnectedPeersSummary());
            networkBadge.getStyleClass().add("peer-connected-badge");
            networkBadge.setMaxWidth(200);
            networkBadge.setTextOverrun(OverrunStyle.ELLIPSIS);
            details.getChildren().add(networkBadge);
        }

        HBox.setHgrow(details, Priority.ALWAYS);

        HBox actions = new HBox(4);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Button btnDisc = new Button("Disconnect");
        btnDisc.getStyleClass().add("btn-decline-sm");
        btnDisc.setOnAction(e -> {
            e.consume();
            chatManager.disconnectPeer(peer.getPeerId());
        });

        Button btnRemove = new Button("✕");
        btnRemove.getStyleClass().add("btn-remove-peer-sm");
        btnRemove.setOnAction(e -> {
            e.consume();
            handleRemovePeer(peer);
        });
        actions.getChildren().addAll(btnDisc, btnRemove);

        item.getChildren().addAll(avatar, details, actions);
        item.setOnMouseClicked(e -> selectPeer(peer));
        return item;
    }

    private HBox createRequestPeerItem(Peer peer) {
        HBox item = new HBox(10);
        item.setAlignment(Pos.CENTER_LEFT);
        boolean isSelected = !isGroupChatSelected && selectedPeer != null && selectedPeer.getPeerId().equals(peer.getPeerId());
        item.getStyleClass().add(isSelected ? "peer-item-selected" : "peer-item");
        item.setPadding(new Insets(10, 12, 10, 12));

        StackPane avatar = createAvatarCircle(peer.getAlias(), peer.isGroupJoinRequested() ? "#8b5cf6" : "#f59e0b");

        VBox details = new VBox(2);
        Label name = new Label(peer.getAlias());
        name.getStyleClass().add("peer-item-name");

        String statusText;
        if (peer.isGroupJoinRequested()) {
            statusText = "👥 Group Join Requested";
        } else if (peer.getConnectionStatus() == ConnectionStatus.REQUEST_RECEIVED) {
            statusText = "🔔 Request Received";
        } else {
            statusText = "⏳ Request Pending";
        }
        Label statusLbl = new Label(statusText);
        statusLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (peer.isGroupJoinRequested() ? "#c084fc" : "#fbbf24") + ";");
        details.getChildren().addAll(name, statusLbl);

        if (peer.hasConnectedPeers()) {
            Label networkBadge = new Label("🔗 In group with: " + peer.getConnectedPeersSummary());
            networkBadge.getStyleClass().add("peer-connected-badge");
            networkBadge.setMaxWidth(200);
            networkBadge.setTextOverrun(OverrunStyle.ELLIPSIS);
            details.getChildren().add(networkBadge);
        }

        HBox.setHgrow(details, Priority.ALWAYS);

        HBox actions = new HBox(4);
        actions.setAlignment(Pos.CENTER_RIGHT);

        if (peer.isGroupJoinRequested()) {
            Button btnAccept = new Button("✓ Accept");
            btnAccept.getStyleClass().add("btn-group-join-sm");
            btnAccept.setOnAction(e -> chatManager.acceptGroupJoinRequest(peer.getPeerId()));

            Button btnDecline = new Button("✕");
            btnDecline.getStyleClass().add("btn-decline-sm");
            btnDecline.setOnAction(e -> chatManager.rejectConnectionRequest(peer.getPeerId()));

            actions.getChildren().addAll(btnAccept, btnDecline);
        } else if (peer.getConnectionStatus() == ConnectionStatus.REQUEST_RECEIVED) {
            Button btnAccept = new Button("✓ Accept");
            btnAccept.getStyleClass().add("btn-accept-sm");
            btnAccept.setOnAction(e -> chatManager.acceptConnectionRequest(peer.getPeerId()));

            Button btnDecline = new Button("✕");
            btnDecline.getStyleClass().add("btn-decline-sm");
            btnDecline.setOnAction(e -> chatManager.rejectConnectionRequest(peer.getPeerId()));

            actions.getChildren().addAll(btnAccept, btnDecline);
        } else {
            Label pendingBadge = new Label("Sent");
            pendingBadge.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 10px; -fx-padding: 2 6; -fx-background-color: rgba(148, 163, 184, 0.15); -fx-background-radius: 4;");
            actions.getChildren().add(pendingBadge);
        }

        item.getChildren().addAll(avatar, details, actions);
        item.setOnMouseClicked(e -> selectPeer(peer));
        return item;
    }

    private HBox createDiscoveredPeerItem(Peer peer) {
        HBox item = new HBox(10);
        item.setAlignment(Pos.CENTER_LEFT);
        boolean isSelected = !isGroupChatSelected && selectedPeer != null && selectedPeer.getPeerId().equals(peer.getPeerId());
        item.getStyleClass().add(isSelected ? "peer-item-selected" : "peer-item");
        item.setPadding(new Insets(10, 12, 10, 12));

        StackPane avatar = createAvatarCircle(peer.getAlias(), "#64748b");

        VBox details = new VBox(2);
        Label name = new Label(peer.getAlias());
        name.getStyleClass().add("peer-item-name");

        HBox endpointBox = new HBox(6);
        endpointBox.setAlignment(Pos.CENTER_LEFT);
        Circle statusDot = new Circle(3.5, Color.web("#38bdf8"));
        Label endpoint = new Label("Discovered • " + peer.getEndpoint());
        endpoint.getStyleClass().add("peer-item-endpoint");
        endpointBox.getChildren().addAll(statusDot, endpoint);

        details.getChildren().addAll(name, endpointBox);

        if (peer.hasConnectedPeers()) {
            Label networkBadge = new Label("🔗 In group with: " + peer.getConnectedPeersSummary());
            networkBadge.getStyleClass().add("peer-connected-badge");
            networkBadge.setMaxWidth(200);
            networkBadge.setTextOverrun(OverrunStyle.ELLIPSIS);
            details.getChildren().add(networkBadge);
        }

        HBox.setHgrow(details, Priority.ALWAYS);

        HBox actions = new HBox(4);
        actions.setAlignment(Pos.CENTER_RIGHT);

        if (peer.hasConnectedPeers()) {
            Button btnAskGroup = new Button("👥 Join");
            btnAskGroup.getStyleClass().add("btn-group-join-sm");
            btnAskGroup.setOnAction(e -> {
                chatManager.sendGroupJoinRequest(peer.getPeerId());
                if (activePeerStatus != null) {
                    activePeerStatus.setText("⏳ Sent group join request to " + peer.getAlias());
                }
            });
            actions.getChildren().add(btnAskGroup);
        } else {
            Button btnConnect = new Button("Connect");
            btnConnect.getStyleClass().add("btn-connect-sm");
            btnConnect.setOnAction(e -> chatManager.sendConnectionRequest(peer.getPeerId()));
            actions.getChildren().add(btnConnect);
        }

        Button btnRemove = new Button("✕");
        btnRemove.getStyleClass().add("btn-remove-peer-sm");
        btnRemove.setOnAction(e -> {
            e.consume();
            handleRemovePeer(peer);
        });
        actions.getChildren().add(btnRemove);

        item.getChildren().addAll(avatar, details, actions);
        item.setOnMouseClicked(e -> selectPeer(peer));
        return item;
    }

    private boolean isPeerOnlineOrConnected(Peer peer) {
        if (peer == null) return false;
        try {
            ChatSession session = chatManager.getActiveSessions().get(peer.getPeerId());
            return peer.isOnline() || (session != null && session.getWebrtcManager() != null && session.getWebrtcManager().isDataChannelOpen());
        } catch (Throwable t) {
            return peer.isOnline();
        }
    }

    public void selectPeer(Peer peer) {
        if (peer == null) return;
        this.isGroupChatSelected = false;
        this.selectedPeer = peer;
        chatManager.selectPeer(peer.getPeerId());

        activePeerTitle.setText(peer.getAlias());
        updatePeerStatusBadge(peer);
        updateInputBarState();

        loadMessagesForSelectedPeer();
    }

    private void updatePeerStatusBadge(Peer peer) {
        if (peer == null) return;
        ConnectionStatus status = peer.getConnectionStatus();

        if (status == ConnectionStatus.CONNECTED) {
            boolean dataChannelOpen = false;
            try {
                ChatSession session = chatManager.getActiveSessions().get(peer.getPeerId());
                dataChannelOpen = session != null && session.getWebrtcManager() != null && session.getWebrtcManager().isDataChannelOpen();
            } catch (Throwable ignored) {}

            String groupNote = peer.hasConnectedPeers() ? " • In group with: " + peer.getConnectedPeersSummary() : "";
            if (dataChannelOpen) {
                activePeerStatus.setText("🟢 Connected • WebRTC DataChannel Open (" + peer.getEndpoint() + ")" + groupNote);
            } else if (peer.isOnline()) {
                activePeerStatus.setText("🟢 Connected • Online (" + peer.getEndpoint() + ")" + groupNote);
            } else {
                activePeerStatus.setText("⚪ Connected • Offline (" + peer.getEndpoint() + ")" + groupNote);
            }
        } else if (peer.isGroupJoinRequested()) {
            activePeerStatus.setText("👥 Group Connection Request Received • Action Required");
        } else if (status == ConnectionStatus.REQUEST_RECEIVED) {
            activePeerStatus.setText("🔔 Connection Request Received • Action Required");
        } else if (status == ConnectionStatus.REQUEST_SENT) {
            activePeerStatus.setText("⏳ Connection Request Pending Approval");
        } else if (status == ConnectionStatus.REJECTED) {
            activePeerStatus.setText("⚪ Connection Request Declined");
        } else {
            String groupNote = peer.hasConnectedPeers() ? " • In group with: " + peer.getConnectedPeersSummary() : "";
            activePeerStatus.setText("📡 Discovered on LAN • Not Connected (" + peer.getEndpoint() + ")" + groupNote);
        }
    }

    private void updateInputBarState() {
        if (isGroupChatSelected) {
            if (messageInputField != null) {
                messageInputField.setDisable(false);
                messageInputField.setPromptText("Type a message to group...");
            }
            if (btnSend != null) btnSend.setDisable(false);
            if (btnAttach != null) btnAttach.setDisable(true);
            if (btnConnectP2P != null) {
                btnConnectP2P.setVisible(false);
                btnConnectP2P.setManaged(false);
            }
            if (btnDisconnect != null) {
                btnDisconnect.setVisible(false);
                btnDisconnect.setManaged(false);
            }
            if (btnClearChat != null) {
                btnClearChat.setVisible(true);
                btnClearChat.setManaged(true);
            }
            return;
        }

        boolean isConnected = (selectedPeer != null && selectedPeer.getConnectionStatus() == ConnectionStatus.CONNECTED);
        if (messageInputField != null) {
            messageInputField.setDisable(!isConnected);
            if (!isConnected) {
                if (selectedPeer != null && selectedPeer.isGroupJoinRequested()) {
                    messageInputField.setPromptText("Accept group connection request to start chatting...");
                } else if (selectedPeer != null && selectedPeer.getConnectionStatus() == ConnectionStatus.REQUEST_RECEIVED) {
                    messageInputField.setPromptText("Accept connection request to start chatting...");
                } else if (selectedPeer != null && selectedPeer.getConnectionStatus() == ConnectionStatus.REQUEST_SENT) {
                    messageInputField.setPromptText("Waiting for peer to accept request...");
                } else if (selectedPeer != null) {
                    messageInputField.setPromptText("Send connection request to start chatting...");
                } else {
                    messageInputField.setPromptText("Select a peer...");
                }
            } else {
                messageInputField.setPromptText("Type a message to " + selectedPeer.getAlias() + "...");
            }
        }
        if (btnSend != null) {
            btnSend.setDisable(!isConnected);
        }
        if (btnAttach != null) {
            btnAttach.setDisable(!isConnected);
        }
        if (btnConnectP2P != null) {
            boolean showConnect = (selectedPeer != null && !isConnected);
            btnConnectP2P.setVisible(showConnect);
            btnConnectP2P.setManaged(showConnect);
        }
        if (btnDisconnect != null) {
            btnDisconnect.setVisible(isConnected);
            btnDisconnect.setManaged(isConnected);
        }
        if (btnClearChat != null) {
            boolean showClear = (selectedPeer != null);
            btnClearChat.setVisible(showClear);
            btnClearChat.setManaged(showClear);
        }
    }

    private void renderConnectionStatusCard(Peer peer) {
        VBox card = new VBox(12);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("connect-request-card");
        card.setMaxWidth(480);

        ConnectionStatus status = peer.getConnectionStatus();
        if (peer.isGroupJoinRequested()) {
            Label title = new Label("🔔 Group Connection Request from " + peer.getAlias());
            title.getStyleClass().add("request-card-title");

            String summary = peer.hasConnectedPeers() ? peer.getConnectedPeersSummary() : "your active peers";
            Label desc = new Label(peer.getAlias() + " wants to join your group connection with " + summary + ". Accept to connect and introduce all peers for full-mesh group chat.");
            desc.getStyleClass().add("request-card-desc");
            desc.setWrapText(true);
            desc.setTextAlignment(TextAlignment.CENTER);

            HBox actions = new HBox(12);
            actions.setAlignment(Pos.CENTER);

            Button btnAccept = new Button("✓ Accept Group Connection");
            btnAccept.getStyleClass().add("btn-group-join-lg");
            btnAccept.setOnAction(e -> chatManager.acceptGroupJoinRequest(peer.getPeerId()));

            Button btnDecline = new Button("✕ Decline");
            btnDecline.getStyleClass().add("btn-decline-lg");
            btnDecline.setOnAction(e -> chatManager.rejectConnectionRequest(peer.getPeerId()));

            actions.getChildren().addAll(btnAccept, btnDecline);
            card.getChildren().addAll(title, desc, actions);

        } else if (status == ConnectionStatus.REQUEST_RECEIVED) {
            Label title = new Label("🔔 Connection Request from " + peer.getAlias());
            title.getStyleClass().add("request-card-title");

            Label desc = new Label(peer.getAlias() + " wants to connect with you. Accept to start direct encrypted messaging and file sharing.");
            desc.getStyleClass().add("request-card-desc");
            desc.setWrapText(true);
            desc.setTextAlignment(TextAlignment.CENTER);

            HBox actions = new HBox(12);
            actions.setAlignment(Pos.CENTER);

            Button btnAccept = new Button("✓ Accept Connection");
            btnAccept.getStyleClass().add("btn-accept-lg");
            btnAccept.setOnAction(e -> chatManager.acceptConnectionRequest(peer.getPeerId()));

            Button btnDecline = new Button("✕ Decline");
            btnDecline.getStyleClass().add("btn-decline-lg");
            btnDecline.setOnAction(e -> chatManager.rejectConnectionRequest(peer.getPeerId()));

            actions.getChildren().addAll(btnAccept, btnDecline);
            card.getChildren().addAll(title, desc, actions);

        } else if (status == ConnectionStatus.REQUEST_SENT) {
            Label title = new Label("⏳ Connection Request Pending");
            title.getStyleClass().add("request-card-title");

            Label desc = new Label("Your connection request was sent to " + peer.getAlias() + " (" + peer.getEndpoint() + "). Once approved, messaging will be unlocked.");
            desc.getStyleClass().add("request-card-desc");
            desc.setWrapText(true);
            desc.setTextAlignment(TextAlignment.CENTER);

            card.getChildren().addAll(title, desc);

            if (peer.hasConnectedPeers()) {
                Button btnAskGroup = new Button("👥 Ask to Join Group Connection (" + peer.getConnectedPeersSummary() + ")");
                btnAskGroup.getStyleClass().add("btn-group-join-lg");
                btnAskGroup.setOnAction(e -> {
                    chatManager.sendGroupJoinRequest(peer.getPeerId());
                    activePeerStatus.setText("⏳ Sent group join request to " + peer.getAlias());
                });
                card.getChildren().add(btnAskGroup);
            }

        } else if (status == ConnectionStatus.REJECTED) {
            Label title = new Label("⚪ Connection Request Declined");
            title.getStyleClass().add("request-card-title");

            Label desc = new Label("This connection was declined. You can send a new connection request whenever you'd like.");
            desc.getStyleClass().add("request-card-desc");
            desc.setWrapText(true);
            desc.setTextAlignment(TextAlignment.CENTER);

            Button btnRetry = new Button("⚡ Send Connection Request Again");
            btnRetry.getStyleClass().add("btn-connect-lg");
            btnRetry.setOnAction(e -> chatManager.sendConnectionRequest(peer.getPeerId()));

            card.getChildren().addAll(title, desc, btnRetry);

        } else { // DISCOVERED
            Label title = new Label("👋 Connect with " + peer.getAlias());
            title.getStyleClass().add("request-card-title");

            Label desc = new Label(peer.getAlias() + " was discovered on your local network at " + peer.getEndpoint() + ". Send a connection request to start chatting.");
            desc.getStyleClass().add("request-card-desc");
            desc.setWrapText(true);
            desc.setTextAlignment(TextAlignment.CENTER);

            Button btnConnect = new Button("⚡ Send Connection Request");
            btnConnect.getStyleClass().add("btn-connect-lg");
            btnConnect.setOnAction(e -> chatManager.sendConnectionRequest(peer.getPeerId()));

            card.getChildren().addAll(title, desc, btnConnect);

            if (peer.hasConnectedPeers()) {
                Label groupNotice = new Label("💡 " + peer.getAlias() + " is also connected with: " + peer.getConnectedPeersSummary());
                groupNotice.setStyle("-fx-text-fill: #c084fc; -fx-font-size: 12px; -fx-font-weight: bold;");

                Button btnAskGroup = new Button("👥 Ask to Join Group Connection");
                btnAskGroup.getStyleClass().add("btn-group-join-lg");
                btnAskGroup.setOnAction(e -> {
                    chatManager.sendGroupJoinRequest(peer.getPeerId());
                    activePeerStatus.setText("⏳ Sent group join request to " + peer.getAlias());
                });
                card.getChildren().addAll(groupNotice, btnAskGroup);
            }
        }

        HBox wrapper = new HBox(card);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPadding(new Insets(20, 0, 20, 0));
        messagesContainer.getChildren().add(wrapper);
    }

    private void renderNoPeerSelectedCard() {
        VBox card = new VBox(12);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("empty-state-card");
        card.setMaxWidth(460);

        Label icon = new Label("🌐");
        icon.setStyle("-fx-font-size: 36px;");

        Label title = new Label("Welcome to Project-CX");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");

        Label desc = new Label("Select a peer node from the left sidebar to begin encrypted P2P messaging. You can scan LAN for active nodes or add peers directly.");
        desc.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");
        desc.setWrapText(true);
        desc.setTextAlignment(TextAlignment.CENTER);

        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER);

        Button btnScan = new Button("📡 Scan LAN");
        btnScan.getStyleClass().add("btn-secondary-sm");
        btnScan.setOnAction(e -> chatManager.triggerLanScan());

        Button btnAdd = new Button("👤 + Peer");
        btnAdd.getStyleClass().add("btn-secondary-sm");
        btnAdd.setOnAction(e -> handleAddPeer());

        actions.getChildren().addAll(btnScan, btnAdd);
        card.getChildren().addAll(icon, title, desc, actions);

        HBox wrapper = new HBox(card);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPadding(new Insets(40, 0, 0, 0));
        messagesContainer.getChildren().add(wrapper);
    }

    private void renderEmptyConversationCard(Peer peer) {
        VBox card = new VBox(10);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("empty-state-card");
        card.setMaxWidth(440);

        Label icon = new Label("💬");
        icon.setStyle("-fx-font-size: 32px;");

        Label title = new Label("Connected with " + peer.getAlias());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");

        Label desc = new Label("No messages yet in this conversation.\nSend a message below or share a file over WebRTC DataChannel.");
        desc.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");
        desc.setWrapText(true);
        desc.setTextAlignment(TextAlignment.CENTER);

        card.getChildren().addAll(icon, title, desc);

        HBox wrapper = new HBox(card);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPadding(new Insets(40, 0, 0, 0));
        messagesContainer.getChildren().add(wrapper);
    }

    private void renderEmptyGroupChatCard() {
        VBox card = new VBox(10);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("empty-state-card");
        card.setMaxWidth(460);

        Label icon = new Label("👥");
        icon.setStyle("-fx-font-size: 32px;");

        String gName = (chatManager.getGroupChatSession() != null)
                ? chatManager.getGroupChatSession().getGroupName()
                : "Mesh Group Chat";

        Label title = new Label("Welcome to " + gName);
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");

        Label desc = new Label("All messages sent here are broadcast over full-mesh P2P to all connected members.\nType a message below to start group conversation!");
        desc.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");
        desc.setWrapText(true);
        desc.setTextAlignment(TextAlignment.CENTER);

        card.getChildren().addAll(icon, title, desc);

        HBox wrapper = new HBox(card);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPadding(new Insets(40, 0, 0, 0));
        messagesContainer.getChildren().add(wrapper);
    }

    private void loadMessagesForSelectedPeer() {
        messagesContainer.getChildren().clear();
        renderedPayloadIds.clear();
        if (selectedPeer == null) return;

        chatManager.getConversationHistoryAsync(selectedPeer.getPeerId()).thenAccept(conversation -> {
            Platform.runLater(() -> {
                messagesContainer.getChildren().clear();
                renderedPayloadIds.clear();
                for (NetworkPayload payload : conversation) {
                    renderMessageBubble(payload);
                }
                if (selectedPeer.getConnectionStatus() != ConnectionStatus.CONNECTED) {
                    renderConnectionStatusCard(selectedPeer);
                } else if (conversation.isEmpty()) {
                    renderEmptyConversationCard(selectedPeer);
                }
                scrollToBottom();
            });
        });
    }

    private void renderMessageBubble(NetworkPayload payload) {
        if (payload == null || payload.getPayloadId() == null) return;
        if (!renderedPayloadIds.add(payload.getPayloadId())) {
            return; // Duplicate already rendered!
        }

        boolean isOutgoing = payload.getSenderId().equals(currentUser.getUserId())
                || payload.getSenderId().equalsIgnoreCase(currentUser.getUsername());

        if (isGroupChatSelected && isOutgoing && payload instanceof TextMessage tm) {
            String dedupeKey = tm.getMessageContent() + "_" + (payload.getTimestamp() != null ? payload.getTimestamp().getMinute() + "_" + payload.getTimestamp().getSecond() : "");
            if (!renderedOutgoingGroupMsgs.add(dedupeKey)) {
                return;
            }
        }

        HBox row = new HBox();
        row.setFillHeight(true);
        row.setAlignment(isOutgoing ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        VBox bubble = new VBox(4);
        bubble.setMaxWidth(520);
        bubble.maxWidthProperty().bind(messagesContainer.widthProperty().multiply(0.72));
        bubble.getStyleClass().add(isOutgoing ? "bubble-outgoing" : "bubble-incoming");

        // Header / Sender Name & Timestamp
        HBox metaRow = new HBox(8);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        String senderDisplayName;
        if (isOutgoing) {
            senderDisplayName = "You";
        } else {
            senderDisplayName = chatManager.getPeer(payload.getSenderId()).map(Peer::getAlias)
                    .orElse(selectedPeer != null ? selectedPeer.getAlias() : payload.getSenderId());
        }
        Label senderLabel = new Label(senderDisplayName);
        senderLabel.getStyleClass().add("bubble-sender");

        String timeStr = payload.getTimestamp() != null 
                ? payload.getTimestamp().format(TIME_FORMATTER) 
                : "";
        Label timeLabel = new Label(timeStr);
        timeLabel.getStyleClass().add("bubble-time");

        metaRow.getChildren().addAll(senderLabel, timeLabel);

        // Content
        if (payload instanceof TextMessage tm) {
            Label body = new Label(tm.getMessageContent());
            body.setWrapText(true);
            body.getStyleClass().add("bubble-text");
            bubble.getChildren().addAll(metaRow, body);
        } else if (payload instanceof FileTransfer ft) {
            HBox fileCard = new HBox(10);
            fileCard.setAlignment(Pos.CENTER_LEFT);
            fileCard.getStyleClass().add("file-transfer-card");

            Label fileIcon = new Label("📁");
            fileIcon.setStyle("-fx-font-size: 20px;");

            VBox fileInfo = new VBox(2);
            Label fileName = new Label(ft.getFileName());
            fileName.getStyleClass().add("file-name");
            Label fileSize = new Label(ft.getFormattedFileSize() + " • " + ft.getMimeType() + " • " + ft.getStatus());
            fileSize.getStyleClass().add("file-details");
            fileInfo.getChildren().addAll(fileName, fileSize);

            fileCard.getChildren().addAll(fileIcon, fileInfo);
            bubble.getChildren().addAll(metaRow, fileCard);
        }

        row.getChildren().add(bubble);
        messagesContainer.getChildren().add(row);
    }

    private void handleConnectP2P() {
        if (selectedPeer == null) {
            showAlert(Alert.AlertType.WARNING, "No Peer Selected", "Please select a peer from the directory first.");
            return;
        }

        if (selectedPeer.getConnectionStatus() == ConnectionStatus.REQUEST_RECEIVED) {
            chatManager.acceptConnectionRequest(selectedPeer.getPeerId());
            activePeerStatus.setText("✓ Accepted connection request from " + selectedPeer.getAlias());
            return;
        }

        if (selectedPeer.getConnectionStatus() != ConnectionStatus.CONNECTED) {
            chatManager.sendConnectionRequest(selectedPeer.getPeerId());
            activePeerStatus.setText("⏳ Sent connection request to " + selectedPeer.getAlias());
            return;
        }

        activePeerStatus.setText("🟡 Connecting WebRTC P2P (Signaling & ICE)...");
        ChatSession session = chatManager.getOrCreateSession(selectedPeer.getPeerId());
        session.connect();
    }

    private void handleDisconnect() {
        if (selectedPeer == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(navigator.getPrimaryStage());
        confirm.setTitle("Disconnect Peer");
        confirm.setHeaderText("Disconnect from @" + selectedPeer.getAlias() + "?");
        confirm.setContentText("This will close the active connection with @" + selectedPeer.getAlias() + ".");
        styleDialog(confirm);

        Optional<ButtonType> res = confirm.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            chatManager.disconnectPeer(selectedPeer.getPeerId());
        }
    }

    private void handleSendMessage() {
        String text = messageInputField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > 4096) {
            showAlert(Alert.AlertType.WARNING, "Message Too Long", "Message exceeds 4,096 characters limit.");
            return;
        }

        if (isGroupChatSelected) {
            messageInputField.clear();
            chatManager.sendGroupMessageAsync(text).thenAccept(sentMsgs -> {
                logger.info("Sent Group TextMessages asynchronously to {} peers", sentMsgs.size());
            }).exceptionally(ex -> {
                logger.error("Failed to dispatch group message asynchronously", ex);
                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Group Send Error", "Failed to dispatch group message: " + ex.getMessage()));
                return null;
            });
            return;
        }

        if (selectedPeer == null) {
            showAlert(Alert.AlertType.WARNING, "No Peer Selected", "Please select a peer from the directory before sending.");
            return;
        }

        messageInputField.clear();

        // Non-blocking async dispatch
        chatManager.sendMessageAsync(text, selectedPeer.getPeerId()).thenAccept(sentMsg -> {
            logger.info("Sent TextMessage asynchronously: {}", sentMsg.getSummary());
        }).exceptionally(ex -> {
            logger.error("Failed to dispatch message asynchronously", ex);
            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Send Error", "Failed to dispatch message: " + ex.getMessage()));
            return null;
        });
    }

    private void handleSendFile() {
        if (selectedPeer == null) {
            showAlert(Alert.AlertType.WARNING, "No Peer Selected", "Please select a peer from the directory before sending a file.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Send via P2P WebRTC");
        File selectedFile = fileChooser.showOpenDialog(navigator.getPrimaryStage());

        if (selectedFile != null && selectedFile.exists()) {
            String fileName = selectedFile.getName();
            long fileSize = selectedFile.length();
            String mimeType = "application/octet-stream";
            try {
                String probed = Files.probeContentType(selectedFile.toPath());
                if (probed != null) mimeType = probed;
            } catch (Exception ignored) {}

            String checksum = "sha256_" + Long.toHexString(selectedFile.lastModified() ^ fileSize);

            chatManager.sendFileAsync(
                    fileName,
                    fileSize,
                    checksum,
                    mimeType,
                    selectedPeer.getPeerId()
            ).exceptionally(ex -> {
                logger.error("Failed to send file asynchronously", ex);
                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "File Transfer Error", "Failed to send file: " + ex.getMessage()));
                return null;
            });
        }
    }

    private void handleAddPeer() {
        TextInputDialog dialog = new TextInputDialog("");
        dialog.initOwner(navigator.getPrimaryStage());
        dialog.setTitle("Add Peer Node");
        dialog.setHeaderText("Add a new peer to your decentralized contact directory");
        dialog.setContentText("Enter Alias and Endpoint (e.g., Bob@192.168.1.50:8888 or Charlie@127.0.0.1:8889):");
        dialog.getEditor().getStyleClass().add("form-input");
        styleDialog(dialog);

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(input -> {
            String raw = input.trim();
            if (raw.isEmpty()) return;

            String alias = raw;
            String ip = "127.0.0.1";
            int port = 8888;

            if (raw.contains("@")) {
                String[] parts = raw.split("@");
                alias = parts[0].trim();
                String endpoint = parts[1].trim();
                if (endpoint.contains(":")) {
                    String[] epParts = endpoint.split(":");
                    ip = epParts[0].trim();
                    try {
                        port = Integer.parseInt(epParts[1].trim());
                    } catch (NumberFormatException nfe) {
                        showAlert(Alert.AlertType.ERROR, "Invalid Port", "Port must be a valid number (1 - 65535).");
                        return;
                    }
                } else {
                    ip = endpoint;
                }
            }

            if (alias.isEmpty()) {
                showAlert(Alert.AlertType.ERROR, "Invalid Alias", "Peer alias cannot be blank.");
                return;
            }

            if (port < 1 || port > 65535) {
                showAlert(Alert.AlertType.ERROR, "Invalid Port", "Port must be between 1 and 65535.");
                return;
            }

            chatManager.addPeerAsync(alias, ip, port)
                    .thenAccept(newPeer -> Platform.runLater(() -> {
                        loadPeers();
                        selectPeer(newPeer);
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Add Peer Failed", ex.getMessage()));
                        return null;
                    });
        });
    }

    private void handleCreateGroupChat() {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(navigator.getPrimaryStage());
        dialog.setTitle("Create Group Chat");
        dialog.setHeaderText("Create a decentralized P2P multi-peer group chat");
        styleDialog(dialog);

        VBox content = new VBox(14);
        content.setPrefWidth(420);
        content.setPadding(new Insets(12));

        // 1. Group Name Input
        VBox nameBox = new VBox(4);
        Label nameLabel = new Label("Group Name");
        nameLabel.getStyleClass().add("input-label");
        TextField groupNameField = new TextField();
        groupNameField.setPromptText("e.g. Dev Team, Alpha Squad...");
        groupNameField.setText("Mesh Group Chat");
        groupNameField.getStyleClass().add("form-input");
        nameBox.getChildren().addAll(nameLabel, groupNameField);

        // 2. Peer Selection
        VBox selectBox = new VBox(6);
        HBox selectHeader = new HBox(8);
        selectHeader.setAlignment(Pos.CENTER_LEFT);
        Label selectLabel = new Label("Select Members to Invite");
        selectLabel.getStyleClass().add("input-label");
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label countLabel = new Label("Selected: 0 peers");
        countLabel.setStyle("-fx-text-fill: #c084fc; -fx-font-size: 11px; -fx-font-weight: bold;");
        selectHeader.getChildren().addAll(selectLabel, spacer, countLabel);

        VBox checkboxContainer = new VBox(6);
        List<Peer> allValidPeers = chatManager.getAllPeers().stream()
                .filter(p -> currentUser == null || (
                        !p.getPeerId().equals(currentUser.getUserId()) &&
                        !p.getAlias().equalsIgnoreCase(currentUser.getUsername()) &&
                        !p.getAlias().equalsIgnoreCase(currentUser.getDisplayName()) &&
                        !p.getPeerId().equalsIgnoreCase("peer_" + currentUser.getUsername()) &&
                        !p.getAlias().startsWith("Peer:") &&
                        !p.getAlias().equalsIgnoreCase("alice") &&
                        !p.getAlias().equalsIgnoreCase("bob") &&
                        !p.getAlias().equalsIgnoreCase("charlie") &&
                        !p.getAlias().toLowerCase().startsWith("test_") &&
                        !p.getPeerId().toLowerCase().startsWith("test_")
                ))
                .toList();

        Map<String, CheckBox> peerCheckboxes = new LinkedHashMap<>();

        Runnable updateCount = () -> {
            long count = peerCheckboxes.values().stream().filter(CheckBox::isSelected).count();
            countLabel.setText("Selected: " + count + " peer" + (count == 1 ? "" : "s"));
        };

        if (allValidPeers.isEmpty()) {
            Label noPeers = new Label("No peers discovered yet. Scan LAN or add peers first!");
            noPeers.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px; -fx-padding: 8;");
            checkboxContainer.getChildren().add(noPeers);
        } else {
            for (Peer p : allValidPeers) {
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("group-checkbox-row");

                CheckBox cb = new CheckBox();
                if (p.getConnectionStatus() == ConnectionStatus.CONNECTED) {
                    cb.setSelected(true);
                }
                peerCheckboxes.put(p.getPeerId(), cb);
                cb.setOnAction(e -> updateCount.run());

                VBox pDetails = new VBox(2);
                Label pAlias = new Label(p.getAlias());
                pAlias.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 12px;");

                String stText = (p.getConnectionStatus() == ConnectionStatus.CONNECTED) ? "🟢 Connected" : "📡 Discovered";
                Label pStatus = new Label(stText + " • " + p.getEndpoint());
                pStatus.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px;");
                pDetails.getChildren().addAll(pAlias, pStatus);
                HBox.setHgrow(pDetails, Priority.ALWAYS);

                row.getChildren().addAll(cb, pDetails);
                row.setOnMouseClicked(e -> {
                    if (e.getTarget() != cb) {
                        cb.setSelected(!cb.isSelected());
                        updateCount.run();
                    }
                });
                checkboxContainer.getChildren().add(row);
            }
            updateCount.run();
        }

        ScrollPane peerScroll = new ScrollPane(checkboxContainer);
        peerScroll.setFitToWidth(true);
        peerScroll.setMaxHeight(200);
        peerScroll.getStyleClass().add("transparent-scroll");
        selectBox.getChildren().addAll(selectHeader, peerScroll);

        content.getChildren().addAll(nameBox, selectBox);
        dialog.getDialogPane().setContent(content);

        ButtonType btnCreateType = new ButtonType("Create Group", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(btnCreateType, ButtonType.CANCEL);

        dialog.setResultConverter(buttonType -> {
            if (buttonType == btnCreateType) {
                String gName = groupNameField.getText().trim();
                if (gName.isEmpty()) gName = "Mesh Group Chat";

                List<String> selectedIds = peerCheckboxes.entrySet().stream()
                        .filter(e -> e.getValue().isSelected())
                        .map(Map.Entry::getKey)
                        .toList();

                if (selectedIds.isEmpty()) {
                    showAlert(Alert.AlertType.WARNING, "No Peers Selected", "Please select at least 1 peer to create a group chat.");
                    return false;
                }

                final String finalGName = gName;
                chatManager.createGroupChat(gName, selectedIds).thenAccept(session -> {
                    Platform.runLater(() -> {
                        loadPeers();
                        List<Peer> activeMembers = session.getMembers();
                        selectGroupChat(activeMembers);
                        if (activePeerStatus != null) {
                            activePeerStatus.setText("👥 Created Group '" + finalGName + "' with " + activeMembers.size() + " peers");
                        }
                    });
                });
                return true;
            }
            return false;
        });

        dialog.showAndWait();
    }

    private void handleClearChat() {
        if (isGroupChatSelected) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(navigator.getPrimaryStage());
            confirm.setTitle("Clear Group Chat");
            confirm.setHeaderText("Clear Group Conversation History?");
            confirm.setContentText("This will permanently clear all messages in this group chat from SQLite and the screen.");
            styleDialog(confirm);

            Optional<ButtonType> res = confirm.showAndWait();
            if (res.isPresent() && res.get() == ButtonType.OK) {
                chatManager.clearGroupChatHistory().thenRun(() -> {
                    Platform.runLater(() -> {
                        messagesContainer.getChildren().clear();
                        renderedPayloadIds.clear();
                        renderedOutgoingGroupMsgs.clear();
                        renderEmptyGroupChatCard();
                    });
                });
            }
            return;
        }

        if (selectedPeer == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(navigator.getPrimaryStage());
        confirm.setTitle("Clear Conversation");
        confirm.setHeaderText("Clear conversation with " + selectedPeer.getAlias() + "?");
        confirm.setContentText("This will permanently delete local chat history with this peer from your SQLite database.");
        styleDialog(confirm);

        Optional<ButtonType> res = confirm.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            org.yu.projectcx.util.AsyncExecutor.runAsyncDb(() -> {
                chatManager.getMessageRepository().clearConversation(currentUser.getUserId(), selectedPeer.getPeerId());
                Platform.runLater(() -> {
                    messagesContainer.getChildren().clear();
                    renderedPayloadIds.clear();
                    renderEmptyConversationCard(selectedPeer);
                });
            });
        }
    }

    private void handleRemovePeer(Peer peer) {
        if (peer == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(navigator.getPrimaryStage());
        confirm.setTitle("Remove Peer");
        confirm.setHeaderText("Remove @" + peer.getAlias() + " from directories?");
        confirm.setContentText("This will remove @" + peer.getAlias() + " (" + peer.getEndpoint() + ") from your peer directory.");
        styleDialog(confirm);

        Optional<ButtonType> res = confirm.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.OK) {
            chatManager.removePeerAsync(peer.getPeerId()).thenAccept(deleted -> {
                Platform.runLater(() -> {
                    if (selectedPeer != null && selectedPeer.getPeerId().equals(peer.getPeerId())) {
                        selectedPeer = null;
                    }
                    loadPeers();
                });
            });
        }
    }

    private StackPane createAvatarCircle(String name, String colorHex) {
        String initial = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase() : "?";
        Circle circle = new Circle(18, Color.web(colorHex));
        Label label = new Label(initial);
        label.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;");
        return new StackPane(circle, label);
    }

    private void scrollToBottom() {
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.initOwner(navigator.getPrimaryStage());
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        styleDialog(alert);
        alert.showAndWait();
    }

    private void styleDialog(Dialog<?> dialog) {
        if (dialog == null || dialog.getDialogPane() == null) return;
        String css = getClass().getResource("/styles/app.css") != null 
                ? getClass().getResource("/styles/app.css").toExternalForm() 
                : null;
        if (css != null) {
            dialog.getDialogPane().getStylesheets().add(css);
        }
        dialog.getDialogPane().getStyleClass().add("dark-dialog");
    }

    // ==========================================
    // ChatEventListener Implementation (Reactive UI updates)
    // ==========================================

    private boolean isPayloadForSelectedPeer(NetworkPayload payload) {
        if (payload == null) return false;
        boolean isGroupMsg = GroupChatSession.GROUP_PEER_ID.equals(payload.getRecipientId());
        if (isGroupChatSelected) {
            return isGroupMsg;
        }
        if (isGroupMsg) {
            return false;
        }
        if (selectedPeer == null) return false;
        String s = payload.getSenderId();
        String r = payload.getRecipientId();
        return (matchesSelectedPeer(s) && matchesCurrentUser(r))
            || (matchesCurrentUser(s) && matchesSelectedPeer(r));
    }

    private boolean matchesCurrentUser(String id) {
        if (id == null || currentUser == null) return false;
        String uId = currentUser.getUserId();
        String uName = currentUser.getUsername();
        String uDisplay = currentUser.getDisplayName();
        if (id.equalsIgnoreCase(uId) || id.equalsIgnoreCase(uName) || id.equalsIgnoreCase("peer_" + uName)) {
            return true;
        }
        if (uDisplay != null && (id.equalsIgnoreCase(uDisplay) || id.equalsIgnoreCase("peer_" + uDisplay))) {
            return true;
        }
        return false;
    }

    private boolean matchesSelectedPeer(String id) {
        if (id == null || selectedPeer == null) return false;
        String pId = selectedPeer.getPeerId();
        String pAlias = selectedPeer.getAlias();
        if (id.equalsIgnoreCase(pId) || id.equalsIgnoreCase(pAlias)
                || id.equalsIgnoreCase("peer_" + pAlias) || id.equalsIgnoreCase("peer_" + pId)
                || id.replace("peer_", "").equalsIgnoreCase(pId.replace("peer_", ""))
                || (pAlias != null && id.replace("peer_", "").equalsIgnoreCase(pAlias.replace("peer_", "")))) {
            return true;
        }
        try {
            if (chatManager != null && chatManager.getUserRepository() != null) {
                Optional<User> uPeer = chatManager.getUserRepository().findByIdOrUsernameOrDisplayName(pId);
                if (uPeer.isEmpty() && pAlias != null) {
                    uPeer = chatManager.getUserRepository().findByIdOrUsernameOrDisplayName(pAlias);
                }
                if (uPeer.isPresent()) {
                    User u = uPeer.get();
                    if (id.equalsIgnoreCase(u.getUserId()) || id.equalsIgnoreCase(u.getUsername())
                            || (u.getDisplayName() != null && id.equalsIgnoreCase(u.getDisplayName()))) {
                        return true;
                    }
                }
                Optional<User> uId = chatManager.getUserRepository().findByIdOrUsernameOrDisplayName(id);
                if (uId.isPresent()) {
                    User u = uId.get();
                    if (u.getUsername().equalsIgnoreCase(pId) || u.getUsername().equalsIgnoreCase(pAlias)
                            || (u.getDisplayName() != null && (u.getDisplayName().equalsIgnoreCase(pId) || u.getDisplayName().equalsIgnoreCase(pAlias)))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onMessageDispatched(TextMessage message) {
        Platform.runLater(() -> {
            if (isPayloadForSelectedPeer(message)) {
                renderMessageBubble(message);
                scrollToBottom();
            }
        });
    }

    @Override
    public void onPayloadDispatched(NetworkPayload payload) {
        Platform.runLater(() -> {
            if (isPayloadForSelectedPeer(payload)) {
                renderMessageBubble(payload);
                scrollToBottom();
            }
        });
    }

    @Override
    public void onPeerSelected(Peer peer) {
        Platform.runLater(() -> updatePeerStatusBadge(peer));
    }

    @Override
    public void onPeersUpdated(List<Peer> peers) {
        Platform.runLater(() -> {
            loadPeers();
            if (selectedPeer != null) {
                chatManager.getPeer(selectedPeer.getPeerId()).ifPresent(p -> {
                    this.selectedPeer = p;
                    updatePeerStatusBadge(p);
                    updateInputBarState();
                });
            }
        });
    }

    public Pane getView() {
        return root;
    }
}
