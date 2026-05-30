-- Install default developer account so dev login is validated by user table.
INSERT OR IGNORE INTO user (username, password, role, is_active)
VALUES ('dev', '$2a$10$NaN5EONZPp9HwiCqcqmJCOjijw/XUTo.uur.VyCWIaZbgr1JUQP9y', 'DEV', TRUE);

-- Move whitelist identities from hand-written text keys to numeric autoincrement IDs.
CREATE TABLE model_whitelist_new
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    model_name TEXT    NOT NULL UNIQUE,
    is_active  INTEGER NOT NULL DEFAULT 1,
    created_at TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO model_whitelist_new (model_name, is_active, created_at)
SELECT model_name, is_active, created_at
FROM model_whitelist
ORDER BY id;

CREATE TABLE model_whitelist_rule_new
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    whitelist_id   INTEGER NOT NULL,
    prop_key       TEXT    NOT NULL,
    expected_value TEXT    NOT NULL,
    FOREIGN KEY (whitelist_id) REFERENCES model_whitelist_new (id) ON DELETE CASCADE,
    UNIQUE (whitelist_id, prop_key)
);

INSERT INTO model_whitelist_rule_new (whitelist_id, prop_key, expected_value)
SELECT new_whitelist.id, rule.prop_key, rule.expected_value
FROM model_whitelist_rule rule
JOIN model_whitelist old_whitelist ON old_whitelist.id = rule.whitelist_id
JOIN model_whitelist_new new_whitelist
  ON new_whitelist.model_name = old_whitelist.model_name
 AND new_whitelist.created_at = old_whitelist.created_at;

CREATE TABLE validated_device_new
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    device_name  TEXT NOT NULL,
    hardware_id  TEXT,
    whitelist_id INTEGER,
    camera_id    TEXT UNIQUE,
    validated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (whitelist_id) REFERENCES model_whitelist_new (id) ON DELETE SET NULL
);

INSERT INTO validated_device_new (id, device_name, hardware_id, whitelist_id, camera_id, validated_at, last_seen_at)
SELECT device.id,
       device.device_name,
       device.hardware_id,
       new_whitelist.id,
       device.camera_id,
       device.validated_at,
       device.last_seen_at
FROM validated_device device
LEFT JOIN model_whitelist old_whitelist ON old_whitelist.id = device.whitelist_id
LEFT JOIN model_whitelist_new new_whitelist
  ON new_whitelist.model_name = old_whitelist.model_name
 AND new_whitelist.created_at = old_whitelist.created_at;

DROP TABLE validated_device;
DROP TABLE model_whitelist_rule;
DROP TABLE model_whitelist;

ALTER TABLE model_whitelist_new RENAME TO model_whitelist;
ALTER TABLE model_whitelist_rule_new RENAME TO model_whitelist_rule;
ALTER TABLE validated_device_new RENAME TO validated_device;

CREATE INDEX IF NOT EXISTS idx_model_whitelist_rule_whitelist_id ON model_whitelist_rule (whitelist_id);
CREATE INDEX IF NOT EXISTS idx_validated_model_whitelist_id ON validated_device (whitelist_id);
