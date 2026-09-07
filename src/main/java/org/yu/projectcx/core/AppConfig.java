package org.yu.projectcx.core;

/**
 * Global application configuration and environment properties.
 */
public class AppConfig {

    public static final String APP_NAME = "Project-CX";
    public static final String APP_VERSION = "1.0.0-SNAPSHOT";
    public static final int DEFAULT_WINDOW_WIDTH = 900;
    public static final int DEFAULT_WINDOW_HEIGHT = 600;

    private static volatile boolean aggressiveDiscoveryEnabled = Boolean.parseBoolean(
            System.getProperty("projectcx.discovery.aggressive", "false")
    );

    public static boolean isAggressiveDiscoveryEnabled() {
        return aggressiveDiscoveryEnabled;
    }

    public static void setAggressiveDiscoveryEnabled(boolean enabled) {
        aggressiveDiscoveryEnabled = enabled;
    }

    private AppConfig() {
        // Prevent instantiation
    }
}
