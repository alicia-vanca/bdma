CREATE TABLE IF NOT EXISTS restore_failures
(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    src_path TEXT NOT NULL,
    error_message TEXT
);