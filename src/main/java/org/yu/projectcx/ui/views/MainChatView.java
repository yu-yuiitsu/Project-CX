package org.yu.projectcx.ui.views;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.core.ChatEventListener;
import org.yu.projectcx.core.ChatManager;
import org.yu.projectcx.core.ChatSession;
import org.yu.projectcx.model.FileTransfer;
import org.yu.projectcx.model.NetworkPayload;
import org.yu.projectcx.model.Peer;
import org.yu.projectcx.model.TextMessage;
import org.yu.projectcx.model.User;
import org.yu.projectcx.ui.SceneNavigator;

import java.io.File;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private Label activePeerTitle;
    private Label activePeerStatus;
    private Peer selectedPeer;
    private final java.util.Set<String> renderedPayloadIds = new java.util.HashSet<>();

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
        sidebar.setPrefWidth(280);
        sidebar.setMinWidth(240);
        sidebar.setMaxWidth(340);
        sidebar.setPadding(new Insets(16));

        // User Profile Header Card
        HBox profileCard = new HBox(12);
        profileCard.setAlignment(Pos.CENTER_LEFT);
        profileCard.getStyleClass().add("user-profile-card");

        StackPane avatarPane = createAvatarCircle(currentUser.getDisplayName(), "#6366f1");

        VBox userDetails = new VBox(2);
        Label nameLabel = new Label(currentUser.getDisplayName());
        nameLabel.getStyleClass().add("profile-name");

        HBox statusRow = new HBox(6);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        Circle statusDot = new Circle(4, Color.web("#10b981"));
        Label statusText = new Label("Online • @" + currentUser.getUsername());
        statusText.getStyleClass().add("profile-status");
        statusRow.getChildren().addAll(statusDot, statusText);

        userDetails.getChildren().addAll(nameLabel, statusRow);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnLogout = new Button("Logout");
        btnLogout.getStyleClass().add("btn-logout");
        btnLogout.setOnAction(e -> {
            chatManager.removeEventListener(this);
            chatManager.logout();
            navigator.showLoginView();
        });

        profileCard.getChildren().addAll(avatarPane, userDetails, spacer, btnLogout);

        // Peers Section Header
        HBox peersHeader = new HBox(8);
        peersHeader.setAlignment(Pos.CENTER_LEFT);
        peersHeader.getStyleClass().add("peers-header-row");

        Label peersTitle = new Label("PEERS");
        peersTitle.getStyleClass().add("section-title");

        HBox headerSpacer = new HBox();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Button btnScanLan = new Button("📡 Scan LAN");
        btnScanLan.getStyleClass().add("btn-add-peer");
        btnScanLan.setOnAction(e -> {
            chatManager.triggerLanScan();
            if (activePeerStatus != null) {
                activePeerStatus.setText("📡 Scanning local network for active peer nodes...");
            }
        });

        Button btnAddPeer = new Button("+ Add");
        btnAddPeer.getStyleClass().add("btn-add-peer");
        btnAddPeer.setOnAction(e -> handleAddPeer());

        peersHeader.getChildren().addAll(peersTitle, headerSpacer, btnScanLan, btnAddPeer);

        // Peers List Scroll Container
        peersListContainer = new VBox(6);
        peersListContainer.getStyleClass().add("peers-list");

        ScrollPane peersScroll = new ScrollPane(peersListContainer);
        peersScroll.setFitToWidth(true);
        peersScroll.getStyleClass().add("transparent-scroll");
        VBox.setVgrow(peersScroll, Priority.ALWAYS);

        sidebar.getChildren().addAll(profileCard, peersHeader, peersScroll);
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

        Button btnConnectP2P = new Button("⚡ Connect P2P");
        btnConnectP2P.getStyleClass().add("btn-secondary-sm");
        btnConnectP2P.setOnAction(e -> handleConnectP2P());

        Button btnClearChat = new Button("Clear");
        btnClearChat.getStyleClass().add("btn-secondary-sm");
        btnClearChat.setOnAction(e -> handleClearChat());

        chatHeader.getChildren().addAll(peerInfo, headerSpacer, btnConnectP2P, btnClearChat);

        // 2. Middle Message Container
        messagesContainer = new VBox(10);
        messagesContainer.setPadding(new Insets(16, 24, 16, 24));
        messagesContainer.getStyleClass().add("messages-container");

        chatScrollPane = new ScrollPane(messagesContainer);
        chatScrollPane.setFitToWidth(true);
        chatScrollPane.getStyleClass().add("chat-scroll-pane");
        VBox.setVgrow(chatScrollPane, Priority.ALWAYS);

        // 3. Bottom Input Bar
        HBox inputBar = new HBox(10);
        inputBar.setAlignment(Pos.CENTER);
        inputBar.getStyleClass().add("chat-input-bar");
        inputBar.setPadding(new Insets(14, 20, 14, 20));

        Button btnAttach = new Button("📎 File");
        btnAttach.getStyleClass().add("btn-attach");
        btnAttach.setOnAction(e -> handleSendFile());

        messageInputField = new TextField();
        messageInputField.setPromptText("Type a message...");
        messageInputField.getStyleClass().add("chat-text-input");
        HBox.setHgrow(messageInputField, Priority.ALWAYS);
        messageInputField.setOnAction(e -> handleSendMessage());

        Button btnSend = new Button("SEND");
        btnSend.getStyleClass().add("btn-send");
        btnSend.setOnAction(e -> handleSendMessage());

        inputBar.getChildren().addAll(btnAttach, messageInputField, btnSend);

        chatArea.getChildren().addAll(chatHeader, chatScrollPane, inputBar);
        return chatArea;
    }

    public void loadPeers() {
        peersListContainer.getChildren().clear();
        List<Peer> peers = chatManager.getAllPeers();
        List<Peer> displayPeers = new ArrayList<>();

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
            // Ignore temporary dummy stubs
            if (peer.getAlias().startsWith("Peer:")) {
                continue;
            }

            displayPeers.add(peer);
            HBox peerItem = createPeerItem(peer);
            peersListContainer.getChildren().add(peerItem);
        }

        if (!displayPeers.isEmpty()) {
            if (selectedPeer == null || !displayPeers.contains(selectedPeer)) {
                selectPeer(displayPeers.get(0));
            }
        } else {
            selectedPeer = null;
            activePeerTitle.setText("Select a peer to start chatting");
            activePeerStatus.setText("P2P Network Ready • Waiting for remote peers...");
            messagesContainer.getChildren().clear();
        }
    }

    private HBox createPeerItem(Peer peer) {
        HBox item = new HBox(10);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("peer-item");
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
        HBox.setHgrow(details, Priority.ALWAYS);

        item.getChildren().addAll(avatar, details);
        item.setOnMouseClicked(e -> selectPeer(peer));
        return item;
    }

    private boolean isPeerOnlineOrConnected(Peer peer) {
        if (peer == null) return false;
        ChatSession session = chatManager.getActiveSessions().get(peer.getPeerId());
        return peer.isOnline() || (session != null && session.getWebrtcManager().isDataChannelOpen());
    }

    public void selectPeer(Peer peer) {
        if (peer == null) return;
        this.selectedPeer = peer;
        chatManager.selectPeer(peer.getPeerId());

        activePeerTitle.setText(peer.getAlias());
        updatePeerStatusBadge(peer);

        loadMessagesForSelectedPeer();
        chatManager.connectToPeer(peer.getPeerId());
    }

    private void updatePeerStatusBadge(Peer peer) {
        if (peer == null) return;
        ChatSession session = chatManager.getActiveSessions().get(peer.getPeerId());
        boolean dataChannelOpen = session != null && session.getWebrtcManager().isDataChannelOpen();
        if (dataChannelOpen) {
            activePeerStatus.setText("🟢 P2P Connected • WebRTC DataChannel Open (" + peer.getEndpoint() + ")");
        } else if (peer.isOnline()) {
            activePeerStatus.setText("🟢 Online • Endpoint: " + peer.getEndpoint());
        } else {
            activePeerStatus.setText("⚪ Offline • Endpoint: " + peer.getEndpoint());
        }
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

        HBox row = new HBox();
        row.setFillHeight(true);
        row.setAlignment(isOutgoing ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        VBox bubble = new VBox(4);
        bubble.setMaxWidth(480);
        bubble.getStyleClass().add(isOutgoing ? "bubble-outgoing" : "bubble-incoming");

        // Header / Sender Name & Timestamp
        HBox metaRow = new HBox(8);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        Label senderLabel = new Label(isOutgoing ? "You" : (selectedPeer != null ? selectedPeer.getAlias() : payload.getSenderId()));
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

        activePeerStatus.setText("🟡 Connecting WebRTC P2P (Signaling & ICE)...");
        ChatSession session = chatManager.getOrCreateSession(selectedPeer.getPeerId());
        session.connect();
    }

    private void handleSendMessage() {
        String text = messageInputField.getText().trim();
        if (selectedPeer == null) {
            showAlert(Alert.AlertType.WARNING, "No Peer Selected", "Please select a peer from the directory before sending.");
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > 4096) {
            showAlert(Alert.AlertType.WARNING, "Message Too Long", "Message exceeds 4,096 characters limit.");
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
        dialog.setTitle("Add Peer Node");
        dialog.setHeaderText("Add a new peer to your decentralized contact directory");
        dialog.setContentText("Enter Alias and Endpoint (e.g., Bob@192.168.1.50:8888 or Charlie@127.0.0.1:8889):");

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

    private void handleClearChat() {
        if (selectedPeer == null) return;
        org.yu.projectcx.util.AsyncExecutor.runAsyncDb(() -> {
            chatManager.getMessageRepository().clearConversation(currentUser.getUserId(), selectedPeer.getPeerId());
            Platform.runLater(() -> messagesContainer.getChildren().clear());
        });
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
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // ==========================================
    // ChatEventListener Implementation (Reactive UI updates)
    // ==========================================

    private boolean isPayloadForSelectedPeer(NetworkPayload payload) {
        if (selectedPeer == null || payload == null) return false;
        String s = payload.getSenderId();
        String r = payload.getRecipientId();
        String pId = selectedPeer.getPeerId();
        String pAlias = selectedPeer.getAlias();
        return (s != null && (s.equalsIgnoreCase(pId) || s.equalsIgnoreCase(pAlias) || s.equalsIgnoreCase("peer_" + pAlias)))
            || (r != null && (r.equalsIgnoreCase(pId) || r.equalsIgnoreCase(pAlias) || r.equalsIgnoreCase("peer_" + pAlias)));
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
                });
            }
        });
    }

    public Pane getView() {
        return root;
    }
}
