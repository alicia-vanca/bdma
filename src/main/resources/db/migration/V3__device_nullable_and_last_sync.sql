-- Make validation timestamps nullable and add last_sync_at column.
-- This allows restore-created devices to start without validation timestamps.
-- Since SQLite requires table rebuild to change column constraints, we rebuild the table.

ALTER TABLE validated_device RENAME TO validated_device_old;

CREATE TABLE validated_device
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    device_name  TEXT NOT NULL,
    hardware_id  TEXT,
    whitelist_id INTEGER,
    camera_id    TEXT UNIQUE,
    validated_at TEXT DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TEXT DEFAULT CURRENT_TIMESTAMP,
    last_sync_at TEXT,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    FOREIGN KEY (whitelist_id) REFERENCES model_whitelist (id) ON DELETE SET NULL
);

INSERT INTO validated_device (
    id,
    device_name,
    hardware_id,
    whitelist_id,
    camera_id,
    validated_at,
    last_seen_at,
    last_sync_at,
    is_active
)
SELECT
    id,
    device_name,
    hardware_id,
    whitelist_id,
    camera_id,
    validated_at,
    last_seen_at,
    NULL,
    is_active
FROM validated_device_old;

DROP TABLE validated_device_old;

CREATE INDEX IF NOT EXISTS idx_validated_model_whitelist_id
    ON validated_device (whitelist_id);