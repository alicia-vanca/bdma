package com.app.common.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.app.common.models.FileRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.app.common.definitions.AppConstants;
import com.app.common.dtos.FileFilter;
import com.app.common.dtos.FileView;
import com.app.common.exceptions.RepositoryException;

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
    public List<FileRecord> loadSyncedFiles(Long deviceId) {
        return jdbcTemplate.query(
                "SELECT * FROM files WHERE device_id = ?",
                this::fileRowMapper,
                deviceId);
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
     * Finds legacy synced files whose stored local path still uses the temporary
     * _enc filename marker. Used only by the startup transition migration.
     */
    public List<FileRecord> findSyncedEncryptedTransitionFiles() {
        String sql = """
                    SELECT * FROM files
                    WHERE synced_path LIKE ?
                      AND status IN (?, ?)
                """;

        return jdbcTemplate.query(sql,
                this::fileRowMapper,
                "%" + AppConstants.BODYCAM_ENCRYPTED_FILENAME_MARKER + ".%",
                AppConstants.FILE_STATUS_SYNCED,
                AppConstants.FILE_STATUS_BACKEDUP);
    }

    /**
     * Updates a legacy encrypted sync record after the decrypted normal file
     * exists.
     * Backup metadata is cleared because old encrypted backup files are discarded.
     */
    public void transitionEncryptedSyncedFile(Long fileId, String normalName, String normalSyncedPath, long fileSize) {
        String sql = """
                    UPDATE files
                    SET name = ?,
                        synced_path = ?,
                        file_size = ?,
                        status = ?,
                        backed_up_path = NULL,
                        backed_up_at = NULL
                    WHERE file_id = ?
                """;

        try {
            jdbcTemplate.update(sql,
                    normalName,
                    normalSyncedPath,
                    fileSize,
                    AppConstants.FILE_STATUS_SYNCED,
                    fileId);
        } catch (Exception e) {
            throw new RepositoryException("transitionEncryptedSyncedFile failed: " + normalSyncedPath, e);
        }
    }

    /**
     * Deletes a file record by primary key.
     */
    public void deleteById(Long fileId) {
        try {
            jdbcTemplate.update("DELETE FROM files WHERE file_id = ?", fileId);
        } catch (Exception e) {
            throw new RepositoryException("deleteById failed: " + fileId, e);
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
     * On conflict (name), updates synced_path, status, file_size,
     * create_date, and type.
     * This ensures files are updated when synced to a different path.
     */
    public void insert(FileRecord fileRecord) {
        String sql = """
                    INSERT INTO files (user_id, device_id, create_date, name, synced_path, file_size, type, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(name) DO UPDATE SET
                        user_id     = excluded.user_id,
                        device_id   = excluded.device_id,
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
                    fileRecord.getUserId(),
                    fileRecord.getDeviceId(),
                    fileRecord.getCreateDate(),
                    fileRecord.getName(),
                    fileRecord.getSyncedPath(),
                    fileRecord.getFileSize(),
                    fileRecord.getType(),
                    fileRecord.getStatus());
        } catch (Exception e) {
            throw new RepositoryException("insert failed: " + fileRecord.getSyncedPath(), e);
        }
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
        if (filter.getCameraId() != null) {
            sql.append(" AND vd.camera_id = ?");
            params.add(filter.getCameraId());
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
        FileRecord f = new FileRecord();
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
     * Upserts a single file into database.
     * Used during restore/rebuild to update paths and status.
     */
    public void upsert(FileRecord fileRecord) {
        String sql = """
            INSERT INTO files (
                user_id, device_id, create_date, name,
                synced_path, backed_up_path, file_size, type, status
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET
                user_id        = excluded.user_id,
                device_id      = excluded.device_id,
                synced_path    = excluded.synced_path,
                backed_up_path = excluded.backed_up_path,
                file_size      = excluded.file_size,
                type           = excluded.type,
                status         = excluded.status,
                create_date    = excluded.create_date
            """;

        try {
            jdbcTemplate.update(sql,
                    fileRecord.getUserId(),
                    fileRecord.getDeviceId(),
                    fileRecord.getCreateDate(),
                    fileRecord.getName(),
                    fileRecord.getSyncedPath(),
                    fileRecord.getBackedUpPath(),
                    fileRecord.getFileSize(),
                    fileRecord.getType(),
                    fileRecord.getStatus());
        } catch (Exception e) {
            throw new RepositoryException("upsert failed: " + fileRecord.getName(), e);
        }
    }

    private FileRecord fileRowMapper(ResultSet rs, int rowNum) throws SQLException {
        FileRecord f = new FileRecord();
        f.setId(rs.getLong("file_id"));
        f.setUserId(rs.getLong("user_id"));
        f.setDeviceId(rs.getLong("device_id"));
        f.setCreateDate(rs.getString("create_date"));
        f.setName(rs.getString("name"));
        f.setSyncedPath(rs.getString("synced_path"));
        f.setFileSize(rs.getLong("file_size"));
        f.setType(rs.getString("type"));
        f.setStatus(rs.getString("status"));
        f.setBackedUpPath(rs.getString("backed_up_path"));
        return f;
    }
}
