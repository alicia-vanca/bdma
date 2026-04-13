package com.app.common.repositories;

import com.app.common.exceptions.RepositoryException;
import com.app.common.models.File;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class FileRepository {

    private final JdbcTemplate jdbcTemplate;

    public FileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Loads paths of files already synced or backed up.
     * Used for in-memory deduplication to avoid re-syncing existing files.
     * Files with status PENDING_LARGE or FAILED are excluded.
     */
    public Set<String> loadSyncedPaths(Long deviceId) {
        String sql = """
                    SELECT path FROM files
                    WHERE device_id = ? AND status IN ('SYNCED', 'BACKUP')
                """;

        return new HashSet<>(
                jdbcTemplate.queryForList(sql, String.class, deviceId));
    }

    /**
     * Loads files with status SYNCED but not yet backed up.
     * Used on application startup to re-enqueue pending backups.
     */
    public List<String> loadPendingBackup() {
        String sql = """
                    SELECT path FROM files
                    WHERE status = 'SYNCED'
                """;

        return jdbcTemplate.queryForList(sql, String.class);
    }

    /**
     * Updates file status by path.
     */
    public void updateStatus(String path, String status) {
        String sql = """
                    UPDATE files SET status = ? WHERE path = ?
                """;

        try {
            jdbcTemplate.update(sql, status, path);
        } catch (Exception e) {
            throw new RepositoryException("updateStatus failed: " + path, e);
        }
    }

    /**
     * Inserts or updates a file record.
     * - create_date is derived from file name.
     * - On conflict (device_id, path), updates status, file_size, create_date, and
     * type.
     */
    public void insert(File file) {
        String sql = """
                    INSERT INTO files (user_id, device_id, create_date, name, path, file_size, type, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(device_id, path) DO UPDATE SET
                        status      = excluded.status,
                        file_size   = excluded.file_size,
                        create_date = excluded.create_date,
                        type        = excluded.type
                """;

        try {
            jdbcTemplate.update(
                    sql,
                    file.getUserId(),
                    file.getDeviceId(),
                    file.getCreateDate(),
                    file.getName(),
                    file.getPath(),
                    file.getFileSize(),
                    file.getType(),
                    file.getStatus());
        } catch (Exception e) {
            throw new RepositoryException("insert failed: " + file.getPath(), e);
        }
    }

    /**
     * Inserts a failed file record.
     * - status = FAILED
     * - file_size = -1
     * - On conflict, only updates status to FAILED.
     */
    public void insertFailed(Long deviceId, String path) {
        String sql = """
                    INSERT INTO files (device_id, create_date, name, path, file_size, status)
                    VALUES (?, datetime('now'), ?, ?, -1, 'FAILED')
                    ON CONFLICT(device_id, path) DO UPDATE SET
                        status = 'FAILED'
                """;

        try {
            jdbcTemplate.update(sql, deviceId, name(path), path);
        } catch (Exception e) {
            throw new RepositoryException("insertFailed: " + path, e);
        }
    }

    private String name(String path) {
        return path.substring(path.lastIndexOf("\\") + 1);
    }
}
