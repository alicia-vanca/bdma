CREATE TABLE IF NOT EXISTS model_whitelist (
    id TEXT PRIMARY KEY,
    model_name TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS model_whitelist_rule (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    whitelist_id TEXT NOT NULL,
    prop_key TEXT NOT NULL,
    expected_value TEXT NOT NULL,
    FOREIGN KEY (whitelist_id) REFERENCES model_whitelist(id) ON DELETE CASCADE,
    UNIQUE (whitelist_id, prop_key)
);
CREATE TABLE IF NOT EXISTS validated_device (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_name TEXT NOT NULL,
    hardware_id TEXT NOT NULL UNIQUE,
    whitelist_id TEXT,
    validated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (whitelist_id) REFERENCES model_whitelist(id) ON DELETE
    SET NULL
);
CREATE INDEX IF NOT EXISTS idx_validated_model_whitelist_id ON validated_device (whitelist_id);
CREATE INDEX IF NOT EXISTS idx_model_whitelist_rule_whitelist_id ON model_whitelist_rule (whitelist_id);
INSERT
    OR IGNORE INTO model_whitelist (id, model_name, is_active)
VALUES ('body_camera_default', 'BodyCamera', 1);
INSERT
    OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES (
        'body_camera_default',
        'ro.product.model',
        'BodyCamera'
    );
INSERT
    OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES (
        'body_camera_default',
        'ro.product.device',
        'k69v1_64_k419'
    );
INSERT
    OR IGNORE INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)
VALUES (
        'body_camera_default',
        'ro.board.platform',
        'mt6768'
    );