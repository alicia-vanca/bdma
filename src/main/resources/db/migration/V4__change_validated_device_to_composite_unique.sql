-- Keep camera_id as the unique validated device identity.
CREATE TABLE validated_device_new
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    device_name  TEXT NOT NULL,
    hardware_id  TEXT,
    whitelist_id TEXT,
    camera_id    TEXT UNIQUE,
    validated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (whitelist_id) REFERENCES model_whitelist (id) ON DELETE SET NULL
);

INSERT INTO validated_device_new (id,
                                  device_name,
                                  hardware_id,
                                  whitelist_id,
                                  camera_id,
                                  validated_at,
                                  last_seen_at)
SELECT id,
       device_name,
       hardware_id,
       whitelist_id,
       camera_id,
       validated_at,
       last_seen_at
FROM validated_device;

DROP TABLE validated_device;
ALTER TABLE validated_device_new RENAME TO validated_device;

CREATE INDEX IF NOT EXISTS idx_validated_model_whitelist_id ON validated_device (whitelist_id);
