CREATE TABLE IF NOT EXISTS patch_apply (
    id         INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    patch_id   VARCHAR(36)  NOT NULL,
    file_name  VARCHAR(512) NOT NULL,
    applied_at TEXT         NOT NULL,
    UNIQUE (patch_id)
    );