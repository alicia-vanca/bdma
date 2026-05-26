package com.app.common.repositories;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.app.common.exceptions.RepositoryException;
import com.app.common.models.PatchApplyRecord;

@Repository
public class PatchApplyRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public PatchApplyRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsByPatchId(UUID patchId) {
        String sql = "SELECT COUNT(*) FROM patch_apply_record WHERE patch_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, patchId.toString());
        return count != null && count > 0;
    }

    public void save(PatchApplyRecord patchApplyRecord) {
        String sql = """
                INSERT INTO patch_apply_record (patch_id, file_name, applied_at)
                VALUES (?, ?, datetime('now', 'localtime'))
                """;
        try {
            jdbcTemplate.update(sql,
                    patchApplyRecord.getPatchId().toString(),
                    patchApplyRecord.getFileName());
        } catch (Exception e) {
            throw new RepositoryException("save failed: " + patchApplyRecord.getPatchId(), e);
        }
    }
}
