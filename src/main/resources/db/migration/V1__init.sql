-- =========================
-- USER TABLE
-- =========================
CREATE TABLE IF NOT EXISTS user
(
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    username  TEXT    NOT NULL UNIQUE,
    password  TEXT    NOT NULL,
    role      TEXT    NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO user (username, password, role)
VALUES ('admin', '$2a$10$S8wX5fJ/SbyEEX7ptgOd2eQ3srztCZds55nEoFAoatrSX0JSSIYSu', 'ADMIN');

-- =========================
-- USER CONFIG TABLE
-- =========================
CREATE TABLE IF NOT EXISTS user_config
(
    id       INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    user_id  INTEGER NOT NULL,
    theme    TEXT    NOT NULL,
    language TEXT    NOT NULL
);

-- =========================
-- USER ACTIVATION HISTORY TABLE
-- =========================
CREATE TABLE IF NOT EXISTS user_activation_history
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER   NOT NULL,
    is_active  BOOLEAN   NOT NULL,
    changed_by INTEGER   NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES user (id),
    FOREIGN KEY (changed_by) REFERENCES user (id)
    );

-- =========================
-- RECENT USERNAMES TABLE
-- =========================
CREATE TABLE IF NOT EXISTS recent_usernames
(
    username      TEXT PRIMARY KEY,
    last_login_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================
-- MODEL WHITELIST TABLES
-- =========================
CREATE TABLE IF NOT EXISTS model_whitelist
(
    id         TEXT PRIMARY KEY,
    model_name TEXT    NOT NULL,
    is_active  INTEGER NOT NULL DEFAULT 1,
    created_at TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS model_whitelist_rule
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    whitelist_id   TEXT NOT NULL,
    prop_key       TEXT NOT NULL,
    expected_value TEXT NOT NULL,
    FOREIGN KEY (whitelist_id) REFERENCES model_whitelist (id) ON DELETE CASCADE,
    UNIQUE (whitelist_id, prop_key)
    );

CREATE INDEX IF NOT EXISTS idx_model_whitelist_rule_whitelist_id ON model_whitelist_rule (whitelist_id);

INSERT OR IGNORE INTO model_whitelist (id, model_name, is_active)
VALUES ('body_camera_default', 'BodyCamera', 1);

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES ('body_camera_default', 'ro.product.model', 'BodyCamera');

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES ('body_camera_default', 'ro.product.device', 'k69v1_64_k419');

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES ('body_camera_default', 'ro.board.platform', 'mt6768');

INSERT OR IGNORE INTO model_whitelist (id, model_name, is_active)
VALUES ('body_camera_bwc', 'BWC', 1);

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES ('body_camera_bwc', 'ro.product.model', 'BWC');

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES ('body_camera_bwc', 'ro.product.device', 'k69v1_64_k419');

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES ('body_camera_bwc', 'ro.board.platform', 'mt6768');

-- =========================
-- VALIDATED DEVICE TABLE
-- =========================
CREATE TABLE IF NOT EXISTS validated_device
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    device_name  TEXT NOT NULL,
    hardware_id  TEXT UNIQUE,
    whitelist_id TEXT,
    camera_id    TEXT UNIQUE,
    validated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (whitelist_id) REFERENCES model_whitelist (id) ON DELETE SET NULL
    );

CREATE INDEX IF NOT EXISTS idx_validated_model_whitelist_id ON validated_device (whitelist_id);

-- =========================
-- FILES TABLE
-- =========================
CREATE TABLE IF NOT EXISTS files
(
    file_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id        INTEGER,
    device_id      INTEGER,
    create_date    TEXT,
    name           TEXT    NOT NULL UNIQUE,
    synced_path    TEXT,
    backed_up_path TEXT,
    status         TEXT    DEFAULT 'SYNCED',
    file_size      INTEGER,
    type           TEXT,
    synced_at      TEXT    DEFAULT (datetime('now', 'localtime')),
    backed_up_at   TEXT,
    FOREIGN KEY (device_id) REFERENCES validated_device (id),
    FOREIGN KEY (user_id) REFERENCES user (id)
    );

CREATE INDEX IF NOT EXISTS idx_files_device_id ON files (device_id);
CREATE INDEX IF NOT EXISTS idx_files_user_id ON files (user_id);
CREATE INDEX IF NOT EXISTS idx_files_create_date ON files (create_date);
CREATE INDEX IF NOT EXISTS idx_files_type ON files (type);

-- =========================
-- APP CONFIG TABLE
-- =========================
CREATE TABLE IF NOT EXISTS app_config
(
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);