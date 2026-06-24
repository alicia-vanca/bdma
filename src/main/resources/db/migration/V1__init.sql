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

INSERT OR IGNORE INTO user (username, password, role, is_active)
VALUES ('admin', '$2a$10$S8wX5fJ/SbyEEX7ptgOd2eQ3srztCZds55nEoFAoatrSX0JSSIYSu', 'ADMIN', TRUE);

-- Install default developer account so dev login is validated by user table.
INSERT OR IGNORE INTO user (username, password, role, is_active)
VALUES ('dev', '$2a$10$NaN5EONZPp9HwiCqcqmJCOjijw/XUTo.uur.VyCWIaZbgr1JUQP9y', 'DEV', TRUE);

-- =========================
-- USER CONFIG TABLE
-- =========================
CREATE TABLE IF NOT EXISTS user_config
(
    user_id INTEGER NOT NULL,
    key     TEXT    NOT NULL,
    value   TEXT    NOT NULL,
    PRIMARY KEY (user_id, key),
    FOREIGN KEY (user_id) REFERENCES user (id) ON DELETE CASCADE
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
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    model_name TEXT    NOT NULL UNIQUE,
    is_active  INTEGER NOT NULL DEFAULT 1,
    created_at TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS model_whitelist_rule
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    whitelist_id   INTEGER NOT NULL,
    prop_key       TEXT    NOT NULL,
    expected_value TEXT    NOT NULL,
    FOREIGN KEY (whitelist_id) REFERENCES model_whitelist (id) ON DELETE CASCADE,
    UNIQUE (whitelist_id, prop_key)
);

CREATE INDEX IF NOT EXISTS idx_model_whitelist_rule_whitelist_id ON model_whitelist_rule (whitelist_id);

INSERT OR IGNORE INTO model_whitelist (model_name, is_active)
VALUES ('BodyCamera', 1);

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
SELECT id, 'ro.product.model', 'BodyCamera'
FROM model_whitelist
WHERE model_name = 'BodyCamera';

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
SELECT id, 'ro.product.device', 'k69v1_64_k419'
FROM model_whitelist
WHERE model_name = 'BodyCamera';

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
SELECT id, 'ro.board.platform', 'mt6768'
FROM model_whitelist
WHERE model_name = 'BodyCamera';

INSERT OR IGNORE INTO model_whitelist (model_name, is_active)
VALUES ('BWC', 1);

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
SELECT id, 'ro.product.model', 'BWC'
FROM model_whitelist
WHERE model_name = 'BWC';

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
SELECT id, 'ro.product.device', 'k69v1_64_k419'
FROM model_whitelist
WHERE model_name = 'BWC';

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
SELECT id, 'ro.board.platform', 'mt6768'
FROM model_whitelist
WHERE model_name = 'BWC';

INSERT OR IGNORE INTO model_whitelist (model_name, is_active)
VALUES ('M780', 1);

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
SELECT id, 'ro.product.model', 'M780'
FROM model_whitelist
WHERE model_name = 'M780';

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
SELECT id, 'ro.product.device', 'k69v1_64_k419'
FROM model_whitelist
WHERE model_name = 'M780';

INSERT OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
SELECT id, 'ro.board.platform', 'mt6768'
FROM model_whitelist
WHERE model_name = 'M780';

-- =========================
-- VALIDATED DEVICE TABLE
-- =========================
CREATE TABLE IF NOT EXISTS validated_device
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    device_name  TEXT NOT NULL,
    hardware_id  TEXT,
    whitelist_id INTEGER,
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

-- =========================
-- RESTORE FAILURES TABLE
-- =========================
CREATE TABLE IF NOT EXISTS restore_failures
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    src_path      TEXT NOT NULL,
    error_message TEXT
);

-- =========================
-- PATCH APPLY LOG TABLE
-- =========================
CREATE TABLE IF NOT EXISTS patch_apply_log
(
    id         INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    patch_id   VARCHAR(36)  NOT NULL,
    file_name  VARCHAR(512) NOT NULL,
    applied_at TEXT         NOT NULL,
    UNIQUE (patch_id)
);
