package com.app.common.repositories;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RecentUsernameRepository {

    private static final int MAX_DISPLAY = 5;
    private final JdbcTemplate jdbcTemplate;

    public RecentUsernameRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> findRecent() {
        return jdbcTemplate.queryForList(
                "SELECT username FROM recent_usernames ORDER BY last_login_at DESC LIMIT ?",
                String.class, MAX_DISPLAY);
    }

    public void upsert(String username) {
        jdbcTemplate.update("""
        INSERT INTO recent_usernames (username, last_login_at)
        VALUES (?, datetime('now', 'localtime'))
        ON CONFLICT(username) DO UPDATE SET last_login_at = datetime('now', 'localtime')
        """, username);
    }
}
