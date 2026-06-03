-- =========================
-- 1. sync_log TABLE
-- =========================

CREATE TABLE sync_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    table_name TEXT NOT NULL,
    operation TEXT NOT NULL,
    record_id TEXT NOT NULL,
    synced INTEGER DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);