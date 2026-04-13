CREATE TABLE app_config
(
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Example keys: theme, language, dataDir, backupDir, etc.
-- Use this table for global (application-wide) configuration values.