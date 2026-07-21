package com.app.common.repositories;

import com.app.common.exceptions.RepositoryException;
import com.app.common.models.FileBookmark;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Repository
public class FileBookmarkRepository {

    private static final int CHUNK_SIZE = 500;
    private static final int SQLITE_MAX_VARIABLES = 999;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public FileBookmarkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public FileBookmark insertIfAbsent(Long userId, Long fileId) {
        try {
            String sql = """
                    INSERT INTO file_bookmark (user_id, file_id) VALUES (?, ?)
                    ON CONFLICT(user_id, file_id) DO UPDATE SET
                        is_bookmark = 1,
                        updated_at = datetime('now', 'localtime')
                    RETURNING *
                    """;
            return jdbcTemplate.queryForObject(sql, this::mapRow, userId, fileId);
        } catch (Exception e) {
            throw new RepositoryException("Failed to upsert bookmark: userId=" + userId + ", fileId=" + fileId, e);
        }
    }

    public void unbookmark(Long userId, Long fileId) {
        try {
            String sql = """
                    UPDATE file_bookmark SET is_bookmark = 0,
                           updated_at = datetime('now', 'localtime')
                    WHERE user_id = ? AND file_id = ? AND is_bookmark = 1
                    """;
            jdbcTemplate.update(sql, userId, fileId);
        } catch (Exception e) {
            throw new RepositoryException("Failed to unbookmark: userId=" + userId + ", fileId=" + fileId, e);
        }
    }

    /**
     * Check if active (non-deleted) bookmark exists.
     */
    public boolean isBookmarked(Long userId, Long fileId) {
        try {
            String sql = """
                    SELECT COUNT(*) FROM file_bookmark
                    WHERE user_id = ? AND file_id = ? AND is_bookmark = 1
                    """;
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId, fileId);
            return count != null && count > 0;
        } catch (Exception e) {
            throw new RepositoryException("Failed to check if bookmarked: userId=" + userId + ", fileId=" + fileId, e);
        }
    }

    /**
     * Find bookmark timestamp for active bookmark.
     */
    public Optional<String> findBookmarkTimestamp(Long userId, Long fileId) {
        try {
            String sql = """
                    SELECT updated_at FROM file_bookmark
                    WHERE user_id = ? AND file_id = ? AND is_bookmark = 1
                    """;
            String createdAt = jdbcTemplate.queryForObject(sql, String.class, userId, fileId);
            return Optional.ofNullable(createdAt);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new RepositoryException("Failed to find bookmark timestamp: userId=" + userId + ", fileId=" + fileId,
                    e);
        }
    }

    /**
     * Batch bookmark. First restores any soft-deleted records, then inserts new
     * ones.
     * Handles previously soft-deleted records that would otherwise be skipped by
     * INSERT OR IGNORE.
     */
    public int[] bookmarkAll(Long userId, List<Long> fileIds) {
        try {
            String restoreSql = """
                    UPDATE file_bookmark SET is_bookmark = 1,
                           updated_at = datetime('now', 'localtime')
                    WHERE user_id = ? AND file_id = ? AND is_bookmark = 0
                    """;
            List<Object[]> batchArgs = fileIds.stream()
                    .map(fid -> new Object[] { userId, fid })
                    .toList();
            jdbcTemplate.batchUpdate(restoreSql, batchArgs);

            String insertSql = "INSERT OR IGNORE INTO file_bookmark (user_id, file_id, is_bookmark) VALUES (?, ?, 1)";
            return jdbcTemplate.batchUpdate(insertSql, batchArgs);
        } catch (Exception e) {
            throw new RepositoryException("Failed to bookmark all: userId=" + userId + ", count=" + fileIds.size(), e);
        }
    }

    /**
     * Batch soft unbookmark.
     */
    public int[] unbookmarkAll(Long userId, List<Long> fileIds) {
        try {
            String sql = """
                    UPDATE file_bookmark SET is_bookmark = 0,
                           updated_at = datetime('now', 'localtime')
                    WHERE user_id = ? AND file_id = ? AND is_bookmark = 1
                    """;
            List<Object[]> batchArgs = fileIds.stream()
                    .map(fid -> new Object[] { userId, fid })
                    .toList();
            return jdbcTemplate.batchUpdate(sql, batchArgs);
        } catch (Exception e) {
            throw new RepositoryException("Failed to unbookmark all: userId=" + userId + ", count=" + fileIds.size(),
                    e);
        }
    }

    /**
     * Filter bookmarked IDs from given file IDs. Uses chunking if list exceeds
     * SQLite variable limit.
     */
    public Set<Long> filterBookmarkedIds(Long userId, List<Long> fileIds) {
        try {
            if (fileIds.isEmpty()) {
                return new HashSet<>();
            }

            // Chunk if exceeds SQLite limit
            if (fileIds.size() > SQLITE_MAX_VARIABLES) {
                return filterBookmarkedIdsChunked(userId, fileIds);
            }

            String sql = """
                    SELECT file_id FROM file_bookmark
                    WHERE user_id = :userId AND file_id IN (:fileIds) AND is_bookmark = 1
                    """;
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("fileIds", fileIds);
            return new HashSet<>(namedParameterJdbcTemplate.queryForList(sql, params, Long.class));
        } catch (Exception e) {
            throw new RepositoryException(
                    "Failed to filter bookmarked ids: userId=" + userId + ", count=" + fileIds.size(), e);
        }
    }

    /**
     * Chunk large file ID lists to avoid SQLite variable limit.
     */
    private Set<Long> filterBookmarkedIdsChunked(Long userId, List<Long> fileIds) {
        Set<Long> result = new HashSet<>();
        for (int i = 0; i < fileIds.size(); i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, fileIds.size());
            List<Long> chunk = fileIds.subList(i, end);
            result.addAll(filterBookmarkedIds(userId, chunk));
        }
        return result;
    }

    private FileBookmark mapRow(ResultSet rs, int rowNum) throws SQLException {
        FileBookmark b = new FileBookmark();
        b.setId(rs.getLong("id"));
        b.setUserId(rs.getLong("user_id"));
        b.setFileId(rs.getLong("file_id"));
        b.setCreatedAt(rs.getString("created_at"));
        b.setUpdatedAt(rs.getString("updated_at"));
        b.setBookmarked(rs.getInt("is_bookmark") == 1);
        return b;
    }
}
