package org.yu.projectcx.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents the local application user profile and identity.
 * 
 * Demonstrates:
 * - Encapsulation via private fields with controlled getters/setters.
 * - Constructor Overloading (multiple constructors for flexible initialization).
 */
public class User {

    // Encapsulated private fields
    private String userId;
    private String username;
    private String displayName;
    private String statusMessage;
    private LocalDateTime createdAt;

    /**
     * Default constructor: initializes default values and a generated unique ID.
     */
    public User() {
        this.userId = UUID.randomUUID().toString();
        this.username = "user_" + userId.substring(0, 6);
        this.displayName = this.username;
        this.statusMessage = "Available";
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Overloaded constructor 1: creates a user with a specific username.
     */
    public User(String username) {
        this();
        setUsername(username);
        this.displayName = username;
    }

    /**
     * Overloaded constructor 2: creates a user with explicit ID, username, and display name.
     */
    public User(String userId, String username, String displayName) {
        this(username);
        setUserId(userId);
        setDisplayName(displayName);
    }

    /**
     * Overloaded constructor 3: full parameter constructor (e.g. for database hydration).
     */
    public User(String userId, String username, String displayName, String statusMessage, LocalDateTime createdAt) {
        setUserId(userId);
        setUsername(username);
        setDisplayName(displayName);
        setStatusMessage(statusMessage);
        this.createdAt = (createdAt != null) ? createdAt : LocalDateTime.now();
    }

    // Getters and Setters with validation (Encapsulation)

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or blank.");
        }
        this.userId = userId.trim();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or blank.");
        }
        this.username = username.trim();
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = (displayName != null && !displayName.trim().isEmpty()) 
                ? displayName.trim() 
                : this.username;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = (statusMessage != null) ? statusMessage.trim() : "";
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User user)) return false;
        return Objects.equals(userId, user.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    @Override
    public String toString() {
        return "User{" +
                "userId='" + userId + '\'' +
                ", username='" + username + '\'' +
                ", displayName='" + displayName + '\'' +
                ", statusMessage='" + statusMessage + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
