-- =========================
-- ALTER FILES TABLE
-- =========================
-- Rename path column to synced_path
ALTER TABLE files
    RENAME COLUMN path TO synced_path;
-- Add new backed_up_path column
ALTER TABLE files
ADD COLUMN backed_up_path TEXT;
-- Update status values: change BACKUP to BACKEDUP
UPDATE files
SET status = 'BACKEDUP'
WHERE status = 'BACKUP';
-- Drop old unique index
DROP INDEX idx_files_unique;
-- Recreate unique index with new column name
CREATE UNIQUE INDEX idx_files_unique ON files (device_id, synced_path);