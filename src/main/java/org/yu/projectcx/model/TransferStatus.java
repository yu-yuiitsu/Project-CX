package org.yu.projectcx.model;

/**
 * Lifecycle status of a peer-to-peer file transfer.
 */
public enum TransferStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}
