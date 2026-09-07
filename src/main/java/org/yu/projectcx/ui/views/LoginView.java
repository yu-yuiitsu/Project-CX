package org.yu.projectcx.ui.views;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.core.ChatManager;
import org.yu.projectcx.model.User;
import org.yu.projectcx.ui.SceneNavigator;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Modern authentication screen connecting GUI -> ChatManager -> UserRepository -> SQLite.
 * 
 * Features:
 * - Clean glassmorphism dark card design.
 * - Input validation (username format, password length).
 * - Non-blocking asynchronous authentication offloaded from JavaFX thread.
 * - Local user scanning (inspect registered profiles stored in SQLite).
 * - Secure password-verified user deletion (clears user profile & conversation history).
 */
public class LoginView {

    private static final Logger logger = LoggerFactory.getLogger(LoginView.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SceneNavigator navigator;
    private final ChatManager chatManager;
    private final VBox root;

    private TextField usernameField;
    private PasswordField passwordField;
    private TextField displayNameField;
    private Label feedbackLabel;

    public LoginView(SceneNavigator navigator, ChatManager chatManager) {
        this.navigator = navigator;
        this.chatManager = chatManager;
        this.root = createView();
    }

    private VBox createView() {
        VBox container = new VBox(18);
        container.setAlignment(Pos.CENTER);
        container.getStyleClass().add("login-container");
        container.setPadding(new Insets(24, 40, 24, 40));

        // 1. Branding Header
        VBox headerBox = new VBox(6);
        headerBox.setAlignment(Pos.CENTER);

        Label title = new Label("P2P CHAT");
        title.getStyleClass().add("login-title");

        Label subtitle = new Label("Project-CX • Decentralized Peer Network");
        subtitle.getStyleClass().add("login-subtitle");

        headerBox.getChildren().addAll(title, subtitle);

        // 2. Login Form Card
        VBox formCard = new VBox(12);
        formCard.getStyleClass().add("login-card");
        formCard.setPadding(new Insets(22, 26, 22, 26));

        // Username Input
        VBox userBox = new VBox(4);
        Label userLabel = new Label("Username");
        userLabel.getStyleClass().add("input-label");
        usernameField = new TextField();
        usernameField.setPromptText("Enter username (e.g. alice)");
        usernameField.getStyleClass().add("form-input");
        usernameField.setOnAction(e -> passwordField.requestFocus());
        userBox.getChildren().addAll(userLabel, usernameField);

        // Password Input
        VBox passBox = new VBox(4);
        Label passLabel = new Label("Password");
        passLabel.getStyleClass().add("input-label");
        passwordField = new PasswordField();
        passwordField.setPromptText("Enter password");
        passwordField.getStyleClass().add("form-input");
        passwordField.setOnAction(e -> handleLogin());
        passBox.getChildren().addAll(passLabel, passwordField);

        // Display Name (for registration)
        VBox nameBox = new VBox(4);
        Label nameLabel = new Label("Display Name (for Register)");
        nameLabel.getStyleClass().add("input-label");
        displayNameField = new TextField();
        displayNameField.setPromptText("Your public alias (e.g. Alice)");
        displayNameField.getStyleClass().add("form-input");
        displayNameField.setOnAction(e -> handleRegister());
        nameBox.getChildren().addAll(nameLabel, displayNameField);

        // Feedback Label
        feedbackLabel = new Label("");
        feedbackLabel.getStyleClass().add("feedback-label");
        feedbackLabel.setWrapText(true);
        feedbackLabel.setVisible(false);

        // Action Buttons
        Button btnLogin = new Button("LOGIN");
        btnLogin.getStyleClass().add("btn-login");
        btnLogin.setMaxWidth(Double.MAX_VALUE);
        btnLogin.setOnAction(e -> handleLogin());

        Button btnRegister = new Button("REGISTER");
        btnRegister.getStyleClass().add("btn-register");
        btnRegister.setMaxWidth(Double.MAX_VALUE);
        btnRegister.setOnAction(e -> handleRegister());

        // Account Tools Row (Scan & Delete)
        HBox toolsRow = new HBox(10);
        toolsRow.setAlignment(Pos.CENTER);
        toolsRow.setPadding(new Insets(6, 0, 0, 0));

        Button btnScanUsers = new Button("🔍 Scan Local Accounts");
        btnScanUsers.getStyleClass().add("btn-tools-link");
        btnScanUsers.setOnAction(e -> handleScanUsersDialog());

        Button btnDeleteUser = new Button("🗑 Delete Account");
        btnDeleteUser.getStyleClass().add("btn-danger-outline");
        btnDeleteUser.setOnAction(e -> handleDeleteUserDialog(usernameField.getText().trim()));

        toolsRow.getChildren().addAll(btnScanUsers, btnDeleteUser);

        formCard.getChildren().addAll(
                userBox,
                passBox,
                nameBox,
                feedbackLabel,
                btnLogin,
                btnRegister,
                toolsRow
        );

        container.getChildren().addAll(headerBox, formCard);
        return container;
    }

    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showFeedback("Please enter both username and password.", true);
            return;
        }

        showFeedback("Authenticating in background...", false);

        chatManager.loginAsync(username, password).thenAccept(authUser -> {
            Platform.runLater(() -> {
                if (authUser.isPresent()) {
                    logger.info("Login successful for user: {}", username);
                    navigator.showMainChatView(authUser.get());
                } else {
                    showFeedback("Invalid username or password. Click REGISTER if you are a new user.", true);
                }
            });
        }).exceptionally(ex -> {
            Platform.runLater(() -> showFeedback("Authentication error: " + ex.getMessage(), true));
            return null;
        });
    }

    private void handleRegister() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        String displayName = displayNameField.getText().trim();

        if (username.isEmpty()) {
            showFeedback("Username cannot be blank.", true);
            return;
        }
        if (username.length() < 3) {
            showFeedback("Username must be at least 3 characters long.", true);
            return;
        }
        if (password.isEmpty() || password.length() < 3) {
            showFeedback("Password must be at least 3 characters long.", true);
            return;
        }

        if (displayName.isEmpty()) {
            displayName = username;
        }

        showFeedback("Registering user in background...", false);

        chatManager.registerAsync(username, password, displayName).thenAccept(newUser -> {
            Platform.runLater(() -> {
                showFeedback("Registration successful! You can now click LOGIN.", false);
                logger.info("Registered new user: {}", username);
            });
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                String msg = ex.getMessage();
                if (msg != null && msg.contains("UNIQUE constraint")) {
                    showFeedback("Username '" + username + "' is already registered. Please choose another.", true);
                } else {
                    showFeedback("Registration error: " + msg, true);
                }
            });
            return null;
        });
    }

    /**
     * Scans and displays all local accounts stored in the SQLite database.
     */
    private void handleScanUsersDialog() {
        showFeedback("Scanning local SQLite database for accounts...", false);

        chatManager.getAllUsersAsync().thenAccept(users -> {
            Platform.runLater(() -> {
                Dialog<Void> dialog = new Dialog<>();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.initOwner(navigator.getPrimaryStage());
                dialog.setTitle("Local User Accounts");
                dialog.setHeaderText("Scanned Registered Accounts in SQLite (" + users.size() + " found)");
                styleDialog(dialog);

                VBox dialogContent = new VBox(10);
                dialogContent.setPadding(new Insets(10));
                dialogContent.setPrefWidth(420);

                if (users.isEmpty()) {
                    Label emptyLabel = new Label("No local accounts found in database.\nRegister a new account to get started.");
                    emptyLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px;");
                    dialogContent.getChildren().add(emptyLabel);
                } else {
                    VBox userListContainer = new VBox(8);
                    for (User user : users) {
                        HBox userRow = new HBox(10);
                        userRow.setAlignment(Pos.CENTER_LEFT);
                        userRow.getStyleClass().add("user-item-card");

                        VBox details = new VBox(2);
                        Label nameLabel = new Label("@" + user.getUsername() + " (" + user.getDisplayName() + ")");
                        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #f8fafc;");

                        String createdStr = (user.getCreatedAt() != null) ? user.getCreatedAt().format(DATE_FORMATTER) : "N/A";
                        Label dateLabel = new Label("Registered: " + createdStr);
                        dateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
                        details.getChildren().addAll(nameLabel, dateLabel);
                        HBox.setHgrow(details, Priority.ALWAYS);

                        Button btnSelect = new Button("Select");
                        btnSelect.getStyleClass().add("btn-select-user");
                        btnSelect.setOnAction(e -> {
                            usernameField.setText(user.getUsername());
                            passwordField.requestFocus();
                            dialog.close();
                        });

                        Button btnDelete = new Button("Delete");
                        btnDelete.getStyleClass().add("btn-danger-outline");
                        btnDelete.setOnAction(e -> {
                            dialog.close();
                            handleDeleteUserDialog(user.getUsername());
                        });

                        userRow.getChildren().addAll(details, btnSelect, btnDelete);
                        userListContainer.getChildren().add(userRow);
                    }

                    ScrollPane scrollPane = new ScrollPane(userListContainer);
                    scrollPane.setFitToWidth(true);
                    scrollPane.setMaxHeight(300);
                    scrollPane.getStyleClass().add("transparent-scroll");
                    dialogContent.getChildren().add(scrollPane);
                }

                ButtonType closeButton = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
                dialog.getDialogPane().getButtonTypes().add(closeButton);
                dialog.getDialogPane().setContent(dialogContent);
                dialog.showAndWait();
            });
        });
    }

    /**
     * Prompts for username and password verification before permanently deleting a user account.
     */
    private void handleDeleteUserDialog(String defaultUsername) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(navigator.getPrimaryStage());
        dialog.setTitle("Delete User Data");
        dialog.setHeaderText("Permanently Delete User Account & Data\n(Requires Password Confirmation)");
        styleDialog(dialog);

        ButtonType deleteButtonType = new ButtonType("Delete Account", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(deleteButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 10));

        TextField targetUsernameField = new TextField(defaultUsername != null ? defaultUsername : "");
        targetUsernameField.setPromptText("Username");
        targetUsernameField.getStyleClass().add("form-input");

        PasswordField verifyPasswordField = new PasswordField();
        verifyPasswordField.setPromptText("Enter account password");
        verifyPasswordField.getStyleClass().add("form-input");

        Label userLabel = new Label("Username:");
        userLabel.getStyleClass().add("input-label");

        Label passLabel = new Label("Password:");
        passLabel.getStyleClass().add("input-label");

        grid.add(userLabel, 0, 0);
        grid.add(targetUsernameField, 1, 0);
        grid.add(passLabel, 0, 1);
        grid.add(verifyPasswordField, 1, 1);

        dialog.getDialogPane().setContent(grid);

        Platform.runLater(verifyPasswordField::requestFocus);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == deleteButtonType) {
                String targetUser = targetUsernameField.getText().trim();
                String targetPass = verifyPasswordField.getText();

                if (targetUser.isEmpty() || targetPass.isEmpty()) {
                    showAlert(Alert.AlertType.WARNING, "Input Error", "Please enter both username and password to confirm deletion.");
                    return false;
                }

                chatManager.deleteUserWithPasswordAsync(targetUser, targetPass).thenAccept(deleted -> {
                    Platform.runLater(() -> {
                        if (deleted) {
                            showAlert(Alert.AlertType.INFORMATION, "Account Deleted",
                                    "User @" + targetUser + " and all associated conversation history have been permanently deleted from SQLite.");
                            if (usernameField.getText().trim().equals(targetUser)) {
                                usernameField.clear();
                                passwordField.clear();
                                displayNameField.clear();
                            }
                            showFeedback("Account @" + targetUser + " deleted.", false);
                        } else {
                            showAlert(Alert.AlertType.ERROR, "Deletion Failed",
                                    "Incorrect password. Account deletion for @" + targetUser + " was aborted.");
                        }
                    });
                }).exceptionally(ex -> {
                    Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Error", "Failed to delete account: " + ex.getMessage()));
                    return null;
                });

                return true;
            }
            return false;
        });

        dialog.showAndWait();
    }

    private void showFeedback(String message, boolean isError) {
        feedbackLabel.setText(message);
        feedbackLabel.setVisible(true);
        feedbackLabel.setStyle(isError 
                ? "-fx-text-fill: #f87171; -fx-font-size: 12px;" 
                : "-fx-text-fill: #34d399; -fx-font-size: 12px;");
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

    public Pane getView() {
        return root;
    }
}
