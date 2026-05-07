-- =========================
-- ALTER FILES TABLE
-- =========================
-- 1. Add unique constraint on (device_id, name) to prevent duplicate entries
-- 2. Rename created_at to synced_at for clarity
-- 3. Add backed_up_at timestamp column

-- First, delete duplicate entries, keeping only the most recent one for each (device_id, name)
DELETE FROM files
WHERE file_id NOT IN (
    SELECT MAX(file_id)
    FROM files
    GROUP BY device_id, name
);

-- Create unique index on (device_id, name)
CREATE UNIQUE INDEX idx_files_unique_name ON files (device_id, name);

-- Rename created_at to synced_at
ALTER TABLE files RENAME COLUMN created_at TO synced_at;

-- Add backed_up_at column
ALTER TABLE files ADD COLUMN backed_up_at TEXT;
