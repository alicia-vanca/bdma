package com.app.common.events;

/**
 * Published when sync or restore creates a user account from file metadata.
 */
public record UserAutoCreatedEvent(String username) {
}
