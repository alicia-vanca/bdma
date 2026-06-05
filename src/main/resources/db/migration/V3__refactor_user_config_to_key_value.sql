-- Refactor user_config from fixed-column (user_id, theme, language)
-- to a generic key-value store (user_id, key, value).
-- This allows storing any per-user preference without schema changes.

ALTER TABLE user_config RENAME TO user_config_old;

CREATE TABLE IF NOT EXISTS user_config
(
    user_id INTEGER NOT NULL,
    key     TEXT    NOT NULL,
    value   TEXT    NOT NULL,
    PRIMARY KEY (user_id, key),
    FOREIGN KEY (user_id) REFERENCES user (id) ON DELETE CASCADE
);

-- Migrate existing theme and language rows
INSERT INTO user_config (user_id, key, value)
SELECT user_id, 'theme', theme
FROM user_config_old;

INSERT INTO user_config (user_id, key, value)
SELECT user_id, 'language', language
FROM user_config_old;

DROP TABLE user_config_old;
