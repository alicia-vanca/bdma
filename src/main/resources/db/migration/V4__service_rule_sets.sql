-- DB-backed Android service rules for sync preparation and restoration.
CREATE TABLE IF NOT EXISTS service_rule_set
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL UNIQUE,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TEXT    NOT NULL DEFAULT (datetime('now', 'localtime'))
);

ALTER TABLE model_whitelist
ADD COLUMN service_rule_set_id INTEGER REFERENCES service_rule_set (id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS service_rule
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_set_id   INTEGER NOT NULL,
    phase         TEXT    NOT NULL,
    execute_order INTEGER NOT NULL,
    action        TEXT    NOT NULL,
    service_name  TEXT    NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    FOREIGN KEY (rule_set_id) REFERENCES service_rule_set (id) ON DELETE CASCADE,
    UNIQUE (rule_set_id, phase, execute_order, action, service_name)
);

CREATE INDEX IF NOT EXISTS idx_service_rule_rule_set_phase_order
    ON service_rule (rule_set_id, phase, execute_order);

INSERT OR IGNORE INTO service_rule_set (name, is_active)
VALUES ('default', TRUE);

INSERT OR IGNORE INTO service_rule (rule_set_id, phase, execute_order, action, service_name, is_active)
WITH default_rule_set AS (
    SELECT id
    FROM service_rule_set
    WHERE name = 'default' AND is_active
)
SELECT id,
       'BEFORE_SYNC',
       10,
       'STOP',
       'com.bodycamera.nettysocket/com.recoda.bodycamera.service.CameraService',
       TRUE
FROM default_rule_set
UNION ALL
SELECT id,
       'AFTER_SYNC',
       10,
       'START',
       'com.bodycamera.nettysocket/com.recoda.bodycamera.service.CameraService',
       TRUE
FROM default_rule_set;
