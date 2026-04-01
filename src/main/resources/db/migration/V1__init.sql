CREATE TABLE IF NOT EXISTS user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    role TEXT NOT NULL
);

INSERT INTO user (username, password, role)
VALUES ('admin', '$2a$10$S8wX5fJ/SbyEEX7ptgOd2eQ3srztCZds55nEoFAoatrSX0JSSIYSu','ADMIN');