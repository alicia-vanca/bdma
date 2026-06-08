package com.app.common.repositories;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.app.common.models.RestoreFailure;

@Repository
public class RestoreFailureRepository {

    private final JdbcTemplate jdbcTemplate;

    public RestoreFailureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(List<RestoreFailure> failures) {
        String sql = "INSERT INTO restore_failures (src_path, error_message) VALUES (?, ?)";
        jdbcTemplate.batchUpdate(sql, failures, failures.size(), (ps, failure) -> {
            ps.setString(1, failure.getSrcPath());
            ps.setString(2, failure.getErrorMessage());
        });
    }

    public List<RestoreFailure> findAll() {
        String sql = "SELECT src_path, error_message FROM restore_failures";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new RestoreFailure(
                rs.getString("src_path"),
                rs.getString("error_message")
        ));
    }

    public void deleteByPaths(List<String> srcPaths) {
        String sql = "DELETE FROM restore_failures WHERE src_path = ?";
        jdbcTemplate.batchUpdate(sql, srcPaths, srcPaths.size(),
                (ps, path) -> ps.setString(1, path));
    }

    public void clearAll() {
        jdbcTemplate.update("DELETE FROM restore_failures");
    }
}
