package org.yu.projectcx;

import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yu.projectcx.core.ChatManager;
import org.yu.projectcx.ui.SceneNavigator;

/**
 * Main JavaFX Application launcher for Project-CX.
 * Initializes the SceneNavigator and displays the primary Login screen.
 */
public class App extends Application {

    private static final Logger logger = LoggerFactory.getLogger(App.class);

    @Override
    public void start(Stage primaryStage) {
        logger.info("Launching Project-CX JavaFX Application interface...");
        try {
            SceneNavigator navigator = new SceneNavigator(primaryStage);
            primaryStage.setOnCloseRequest(e -> {
                logger.info("Window close requested. Cleaning up ChatManager...");
                try {
                    ChatManager.getInstance().logout();
                    ChatManager.getInstance().shutdown();
                } catch (Exception ignored) {}
                System.exit(0);
            });
            navigator.showLoginView();
        } catch (Throwable t) {
            logger.error("Error starting JavaFX interface", t);
        }
    }
}
