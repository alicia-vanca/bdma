package com.app.common.repositories;

import com.app.common.definitions.enums.Language;
import com.app.common.definitions.enums.Theme;
import com.app.common.models.UserSetting;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Repository
public class UserSettingRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserSettingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private UserSetting mapRow(ResultSet rs, int rowNum) throws SQLException {
        UserSetting userSetting = new UserSetting();
        userSetting.setId(rs.getLong("id"));
        userSetting.setUserId(rs.getLong("user_id"));
        userSetting.setTheme(Theme.valueOf(rs.getString("theme")));
        userSetting.setLanguage(Language.valueOf(rs.getString("language")));
        return userSetting;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public Optional<UserSetting> findByUserId(Long userId) {
        String sql = "SELECT * FROM user_config WHERE user_id = ? LIMIT 1";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::mapRow, userId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    public void save(UserSetting userSetting) {
        String sql = "INSERT INTO user_config (user_id, theme, language) VALUES (?, ?, ?)";
        jdbcTemplate.update(sql,
                userSetting.getUserId(),
                userSetting.getTheme().name(),
                userSetting.getLanguage().name());
    }

    public void update(UserSetting userSetting) {
        String sql = "UPDATE user_config SET theme = ?, language = ? WHERE user_id = ?";
        jdbcTemplate.update(sql,
                userSetting.getTheme().name(),
                userSetting.getLanguage().name(),
                userSetting.getUserId());
    }
}
