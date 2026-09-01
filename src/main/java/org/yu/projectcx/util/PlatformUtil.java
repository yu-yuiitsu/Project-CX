package org.yu.projectcx.util;

/**
 * Utility helper for operating system and runtime platform detection.
 */
public class PlatformUtil {

    public static String getOperatingSystem() {
        return System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")";
    }

    public static String getJavaVersion() {
        return System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
    }

    public static String getJavaFxVersion() {
        return System.getProperty("javafx.version", "21.0.2");
    }
}
