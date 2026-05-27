package com.app.common.repositories;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.app.common.exceptions.RepositoryException;
import com.app.common.models.PatchApply;

@Repository
public class PatchApplyRepository {

    private final JdbcTemplate jdbcTemplate;

    public PatchApplyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsByPatchId(UUID patchId) {
        String sql = "SELECT COUNT(*) FROM patch_apply WHERE patch_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, patchId.toString());
        return count != null && count > 0;
    }

    public void save(PatchApply patchApply) {
        String sql = """
                INSERT INTO patch_apply (patch_id, file_name, applied_at)
                VALUES (?, ?, datetime('now', 'localtime'))
                """;
        try {
            jdbcTemplate.update(sql,
                    patchApply.getPatchId().toString(),
                    patchApply.getFileName());
        } catch (Exception e) {
            throw new RepositoryException("save failed: " + patchApply.getPatchId(), e);
        }
    }
}
