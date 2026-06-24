package com.app.common.modules.datarestore.events;

/**
 * Published after a backup file is restored into the sync folder.
 */
public record FileRestoredEvent(String syncedPath) {
}
