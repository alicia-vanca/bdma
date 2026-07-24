CREATE TABLE IF NOT EXISTS file_bookmark (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL,
    file_id    INTEGER NOT NULL,
    created_at TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT    NOT NULL DEFAULT (datetime('now', 'localtime')),
    is_bookmark INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES files(file_id) ON DELETE NO ACTION,
    UNIQUE(user_id, file_id)
);

CREATE INDEX IF NOT EXISTS idx_file_bookmark_user_id ON file_bookmark(user_id);
CREATE INDEX IF NOT EXISTS idx_file_bookmark_file_id ON file_bookmark(file_id);
CREATE INDEX IF NOT EXISTS idx_file_bookmark_is_bookmark ON file_bookmark(is_bookmark);
