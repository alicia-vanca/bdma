package com.app.common.repositories;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Repository

public class AppConfigRepository {
    private final JdbcTemplate jdbcTemplate;

    public AppConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> findValue(String key) {
        try {
            String sql = "SELECT value FROM app_config WHERE key = ?";
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, String.class, key));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void saveValue(String key, String value) {
        String sql = "INSERT INTO app_config (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        jdbcTemplate.update(sql, key, value);
    }

    public Map<String, String> findAll() {
        String sql = "SELECT key, value FROM app_config";
        return jdbcTemplate.query(sql, rs -> {
            Map<String, String> map = new HashMap<>();
            while (rs.next()) {
                map.put(rs.getString("key"), rs.getString("value"));
            }
            return map;
        });
    }
}
