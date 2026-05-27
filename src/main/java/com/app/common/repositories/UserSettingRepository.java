package com.app.common.repositories;

import com.app.common.models.UserSetting;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class UserSettingRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserSettingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Generic key-value access ─────────────────────────────────────────────

    /** Returns the stored value for a per-user key, or empty if not set. */
    public Optional<String> findValue(Long userId, String key) {
        try {
            String value = jdbcTemplate.queryForObject(
                    "SELECT value FROM user_config WHERE user_id = ? AND key = ?",
                    String.class, userId, key);
            return Optional.ofNullable(value);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Insert or overwrite a per-user key-value entry. */
    public void upsertValue(Long userId, String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO user_config (user_id, key, value) VALUES (?, ?, ?)"
                        + " ON CONFLICT(user_id, key) DO UPDATE SET value = excluded.value",
                userId, key, value);
    }

    // ── UserSetting access ───────────────────────────────────────────────────

    /**
     * Load all rows for the user in one query and return them as a UserSetting.
     * The model maps keys to typed values; no field-by-field extraction needed
     * here.
     */
    public Optional<UserSetting> findByUserId(Long userId) {
        Map<String, String> values = jdbcTemplate
                .queryForList("SELECT key, value FROM user_config WHERE user_id = ?", userId)
                .stream()
                .collect(Collectors.toMap(
                        r -> (String) r.get("key"),
                        r -> (String) r.get("value")));

        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new UserSetting(userId, values));
    }

    /** Persist the typed settings from a UserSetting object. */
    public void save(UserSetting userSetting) {
        upsertValue(userSetting.getUserId(), UserSetting.KEY_THEME, userSetting.getTheme().name());
        upsertValue(userSetting.getUserId(), UserSetting.KEY_LANGUAGE, userSetting.getLanguage().name());
    }

    /** Alias for save — upsert semantics make insert and update identical. */
    public void update(UserSetting userSetting) {
        save(userSetting);
    }
}
