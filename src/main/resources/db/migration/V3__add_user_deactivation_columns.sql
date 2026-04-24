-- =========================
-- ADD USER DEACTIVATION SUPPORT
-- =========================
-- Add is_active column to track user activation status
ALTER TABLE user
ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- Create audit table to track activation/deactivation history
CREATE TABLE user_activation_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL,
    changed_by INTEGER NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES user(id),
    FOREIGN KEY (changed_by) REFERENCES user(id)
);