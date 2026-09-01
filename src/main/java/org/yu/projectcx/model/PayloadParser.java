package org.yu.projectcx.model;

import java.time.LocalDateTime;

/**
 * Utility for parsing wire format strings into polymorphic NetworkPayload instances.
 * 
 * Demonstrates:
 * - Polymorphic Factory: Returns base NetworkPayload reference pointing to concrete subclasses.
 */
public class PayloadParser {

    /**
     * Parses serialized wire strings back into concrete TextMessage or FileTransfer objects.
     */
    public static NetworkPayload parse(String wireData) {
        if (wireData == null || wireData.trim().isEmpty()) {
            throw new IllegalArgumentException("Wire data cannot be null or empty.");
        }

        if (wireData.startsWith("TYPE:TEXT|")) {
            return parseTextMessage(wireData);
        } else if (wireData.startsWith("TYPE:FILE|")) {
            return parseFileTransfer(wireData);
        } else {
            throw new IllegalArgumentException("Unknown payload format: " + wireData);
        }
    }

    private static TextMessage parseTextMessage(String data) {
        String[] parts = data.split("\\|", 8);
        String id = extractValue(parts[1], "ID");
        String from = extractValue(parts[2], "FROM");
        String to = extractValue(parts[3], "TO");
        LocalDateTime time = LocalDateTime.parse(extractValue(parts[4], "TIME"));
        boolean deliv = Boolean.parseBoolean(extractValue(parts[5], "DELIV"));
        boolean read = Boolean.parseBoolean(extractValue(parts[6], "READ"));
        String body = extractValue(parts[7], "BODY");

        return new TextMessage(id, from, to, body, time, deliv, read);
    }

    private static FileTransfer parseFileTransfer(String data) {
        String[] parts = data.split("\\|", 11);
        String id = extractValue(parts[1], "ID");
        String from = extractValue(parts[2], "FROM");
        String to = extractValue(parts[3], "TO");
        LocalDateTime time = LocalDateTime.parse(extractValue(parts[4], "TIME"));
        String name = extractValue(parts[5], "NAME");
        long size = Long.parseLong(extractValue(parts[6], "SIZE"));
        String hash = extractValue(parts[7], "HASH");
        String mime = extractValue(parts[8], "MIME");
        double prog = Double.parseDouble(extractValue(parts[9], "PROG"));
        TransferStatus stat = TransferStatus.valueOf(extractValue(parts[10], "STAT"));

        return new FileTransfer(id, from, to, name, size, hash, mime, prog, stat, time);
    }

    private static String extractValue(String part, String prefix) {
        if (part.startsWith(prefix + ":")) {
            return part.substring(prefix.length() + 1);
        }
        return part;
    }
}
