CREATE TABLE user_config
(
    id          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    theme       TEXT    NOT NULL,
    language    INTEGER NOT NULL
);
