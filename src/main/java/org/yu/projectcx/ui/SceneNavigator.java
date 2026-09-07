package org.yu.projectcx.ui;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.core.ChatManager;
import org.yu.projectcx.model.User;
import org.yu.projectcx.ui.views.LoginView;
import org.yu.projectcx.ui.views.MainChatView;

/**
 * Manages UI scene navigation, window dimensions, and view transitions.
 */
public class SceneNavigator {

    private static final Logger logger = LoggerFactory.getLogger(SceneNavigator.class);

    private final Stage primaryStage;
    private final ChatManager chatManager;

    public SceneNavigator(Stage primaryStage) {
        this.primaryStage = primaryStage;
        this.chatManager = ChatManager.getInstance();
    }

    /**
     * Navigates to the Login Screen.
     */
    public void showLoginView() {
        LoginView loginView = new LoginView(this, chatManager);
        setRoot(loginView.getView(), 480, 580, "Project-CX - P2P Chat Login");
    }

    /**
     * Navigates to the Main Chat Screen after successful authentication.
     */
    public void showMainChatView(User user) {
        chatManager.setCurrentUser(user);
        MainChatView chatView = new MainChatView(this, chatManager);
        setRoot(chatView.getView(), 960, 680, "Project-CX - P2P Chat (" + user.getDisplayName() + ")");
    }

    private void setRoot(Pane pane, double width, double height, String title) {
        Scene scene = new Scene(pane, width, height);

        String cssPath = getClass().getResource("/styles/app.css") != null
                ? getClass().getResource("/styles/app.css").toExternalForm()
                : null;
        if (cssPath != null) {
            scene.getStylesheets().add(cssPath);
        }

        primaryStage.setTitle(title);
        primaryStage.setScene(scene);
        primaryStage.setWidth(width);
        primaryStage.setHeight(height);
        primaryStage.centerOnScreen();
        primaryStage.setMinWidth(420);
        primaryStage.setMinHeight(500);
        primaryStage.show();
    }

    public Stage getPrimaryStage() {
        return primaryStage;
    }

    public ChatManager getChatManager() {
        return chatManager;
    }
}
