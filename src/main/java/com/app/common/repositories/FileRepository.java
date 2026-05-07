package com.app.common.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.app.common.definitions.AppConstants;
import com.app.common.dtos.FileFilter;
import com.app.common.dtos.FileInfo;
import com.app.common.dtos.FileView;
import com.app.common.exceptions.RepositoryException;
import com.app.common.models.File;

@Repository
public class FileRepository {

    private final JdbcTemplate jdbcTemplate;

    public FileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Loads paths of files already synced or backed up.
     * Used for in-memory deduplication to avoid re-syncing existing files.
     * Files with status FAILED are excluded.
     */
    public Set<String> loadSyncedPaths(Long deviceId) {
        String sql = """
                    SELECT synced_path FROM files
                    WHERE device_id = ? AND status IN (?, ?)
                """;

        return new HashSet<>(
                jdbcTemplate.queryForList(sql, String.class, deviceId,
                        AppConstants.FILE_STATUS_SYNCED,
                        AppConstants.FILE_STATUS_BACKEDUP));
    }

    /**
     * Loads files with status SYNCED but not yet backed up.
     * Used on application startup to re-enqueue pending backups.
     */
    public List<String> loadPendingBackup() {
        String sql = """
                    SELECT synced_path FROM files
                    WHERE status = ?
                """;

        return jdbcTemplate.queryForList(sql, String.class, AppConstants.FILE_STATUS_SYNCED);
    }

    /**
     * Updates file status by synced_path.
     */
    public void updateStatusBySyncedPath(String syncedPath, String status) {
        String sql = """
                    UPDATE files SET status = ? WHERE synced_path = ?
                """;

        try {
            jdbcTemplate.update(sql, status, syncedPath);
        } catch (Exception e) {
            throw new RepositoryException("updateStatusBySyncedPath failed: " + syncedPath, e);
        }
    }

    /**
     * Updates file status, backed_up_path, and backed_up_at after successful
     * backup.
     */
    public void updateStatusAndBackupPath(String syncedPath, String backedUpPath, String status) {
        String sql = """
                    UPDATE files
                    SET status = ?,
                        backed_up_path = ?,
                        backed_up_at = datetime('now', 'localtime')
                    WHERE synced_path = ?
                """;

        try {
            jdbcTemplate.update(sql, status, backedUpPath, syncedPath);
        } catch (Exception e) {
            throw new RepositoryException("updateStatusAndBackupPath failed: " + syncedPath, e);
        }
    }

    /**
     * Inserts or updates a file record by filename.
     * On conflict (device_id, name), updates synced_path, status, file_size,
     * create_date, and type.
     * This ensures files are updated when synced to a different path.
     */
    public void insert(File file) {
        String sql = """
                    INSERT INTO files (user_id, device_id, create_date, name, synced_path, file_size, type, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(device_id, name) DO UPDATE SET
                        synced_path = excluded.synced_path,
                        status      = excluded.status,
                        file_size   = excluded.file_size,
                        create_date = excluded.create_date,
                        type        = excluded.type,
                        synced_at   = datetime('now', 'localtime')
                """;

        try {
            jdbcTemplate.update(
                    sql,
                    file.getUserId(),
                    file.getDeviceId(),
                    file.getCreateDate(),
                    file.getName(),
                    file.getSyncedPath(),
                    file.getFileSize(),
                    file.getType(),
                    file.getStatus());
        } catch (Exception e) {
            throw new RepositoryException("insert failed: " + file.getSyncedPath(), e);
        }
    }

    /**
     * Inserts a failed file record.
     * - status = FAILED
     * - file_size = -1
     * - On conflict (device_id, name), updates synced_path, status, user_id, and
     * type.
     */
    public void insertFailed(Long userId, Long deviceId, String syncedPath, FileInfo info) {
        String sql = """
                    INSERT INTO files (user_id, device_id, create_date, name, synced_path, file_size, type, status)
                    VALUES (?, ?, ?, ?, ?, -1, ?, ?)
                    ON CONFLICT(device_id, name) DO UPDATE SET
                        synced_path = excluded.synced_path,
                        status = ?,
                        user_id = excluded.user_id,
                        type = excluded.type
                """;

        try {
            jdbcTemplate.update(sql,
                    userId,
                    deviceId,
                    info.createDate(),
                    name(syncedPath),
                    syncedPath,
                    info.type(),
                    AppConstants.FILE_STATUS_FAILED,
                    AppConstants.FILE_STATUS_FAILED);
        } catch (Exception e) {
            throw new RepositoryException("insertFailed: " + syncedPath, e);
        }
    }

    private String name(String path) {
        return path.substring(path.lastIndexOf("\\") + 1);
    }

    /**
     * Queries files by filter criteria with user and device information.
     * Returns file metadata including synced_path for file existence validation.
     */
    @SuppressWarnings("null")
    public List<FileView> findByFilter(FileFilter filter) {
        StringBuilder sql = new StringBuilder("""
                    SELECT f.file_id,
                           f.name,
                           f.synced_path,
                           f.device_id,
                           f.user_id,
                           f.file_size,
                           f.status,
                           f.create_date,
                           f.type,
                           u.username,
                           vd.device_name
                    FROM files f
                    LEFT JOIN user u ON f.user_id = u.id
                    LEFT JOIN validated_device vd ON f.device_id = vd.id
                    WHERE 1=1
                """);

        List<Object> params = new ArrayList<>();

        // device
        if (filter.getHardwareId() != null) {
            sql.append(" AND vd.hardware_id = ?");
            params.add(filter.getHardwareId());
        }

        // user
        if (filter.getUserId() != null) {
            sql.append(" AND f.user_id = ?");
            params.add(filter.getUserId());
        }

        // type
        if (filter.getType() != null) {
            sql.append(" AND f.type = ?");
            params.add(filter.getType());
        }

        // date from
        if (filter.getDateFrom() != null) {
            sql.append(" AND date(f.create_date) >= ?");
            params.add(filter.getDateFrom().toString());
        }

        // date to (inclusive)
        if (filter.getDateTo() != null) {
            sql.append(" AND date(f.create_date) <= ?");
            params.add(filter.getDateTo().toString());
        }

        sql.append(" ORDER BY f.create_date DESC");

        return jdbcTemplate.query(sql.toString(), this::mapRow, params.toArray(new Object[0]));
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private FileView mapRow(ResultSet rs, int rowNum) throws SQLException {
        File f = new File();
        f.setId(rs.getLong("file_id"));
        f.setDeviceId(rs.getLong("device_id"));
        f.setUserId(rs.getLong("user_id"));
        f.setName(rs.getString("name"));
        f.setSyncedPath(rs.getString("synced_path"));
        f.setFileSize(rs.getLong("file_size"));
        f.setType(rs.getString("type"));
        f.setStatus(rs.getString("status"));
        f.setCreateDate(rs.getString("create_date"));

        return FileView.from(
                f,
                rs.getString("username"),
                rs.getString("device_name"));
    }

    /**
     * Batch updates synced_path, backed_up_path, and status for multiple files.
     * Each batch argument array contains: [syncedPath, backedUpPath, status,
     * fileName]
     */
    public void batchUpdatePaths(List<Object[]> batchArgs) {
        String sql = """
                UPDATE files
                SET synced_path    = ?,
                    backed_up_path = ?,
                    status         = ?
                WHERE name = ?
                """;

        try {
            jdbcTemplate.batchUpdate(sql, new ArrayList<>(batchArgs));
        } catch (Exception e) {
            throw new RepositoryException("batchUpdatePaths failed", e);
        }
    }
}
