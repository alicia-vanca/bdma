-- Add soft activation state for saved BodyCam devices.
ALTER TABLE validated_device
ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE IF NOT EXISTS device_activation_history
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  INTEGER   NOT NULL,
    is_active  BOOLEAN   NOT NULL,
    changed_by INTEGER   NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (device_id) REFERENCES validated_device (id),
    FOREIGN KEY (changed_by) REFERENCES user (id)
);

CREATE INDEX IF NOT EXISTS idx_device_activation_history_device_id
    ON device_activation_history (device_id);
