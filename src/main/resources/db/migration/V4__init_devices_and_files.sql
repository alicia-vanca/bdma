-- =========================
-- FILES TABLE
-- =========================
CREATE TABLE files
(
    file_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER,
    device_id   INTEGER,
    create_date TEXT,
    name        TEXT,
    path        TEXT,
    status      TEXT DEFAULT 'SYNCED',
    file_size   INTEGER,
    type        TEXT,
    created_at  TEXT DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (device_id) REFERENCES validated_device (id),
    FOREIGN KEY (user_id) REFERENCES user (id)
);

-- Index for device-based queries
CREATE INDEX idx_files_device_id
    ON files (device_id);

-- Index for user-based queries
CREATE INDEX idx_files_user_id
    ON files (user_id);

-- Index for filtering/sorting by create_date
CREATE INDEX idx_files_create_date
    ON files (create_date);

-- Index for file type queries
CREATE INDEX idx_files_type
    ON files (type);

-- Prevent duplicate files per device + path
CREATE UNIQUE INDEX idx_files_unique
    ON files (device_id, path);