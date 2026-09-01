package org.yu.projectcx;

import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standard Java main entry point launcher.
 * 
 * Note: Having a separate launcher class that does not extend javafx.application.Application
 * avoids JavaFX module-path runtime restrictions when launching via classpath, fat JAR, or IDEs.
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        logger.info("Initializing Project-CX runtime launcher...");
        try {
            Application.launch(App.class, args);
        } catch (Throwable t) {
            logger.error("Application crashed during startup", t);
            System.exit(1);
        }
    }
}
