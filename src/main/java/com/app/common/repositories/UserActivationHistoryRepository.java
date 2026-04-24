package com.app.common.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.app.common.models.UserActivationHistory;

@Repository
public class UserActivationHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserActivationHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Record when a user account is activated or deactivated, tracking who made the
     * change for audit purposes.
     *
     * @param userId    the ID of the user whose activation status changed
     * @param isActive  true if the user was activated, false if deactivated
     * @param changedBy the ID of the user who made the change
     */
    public void recordChange(Long userId, boolean isActive, Long changedBy) {
        jdbcTemplate.update(
                "INSERT INTO user_activation_history(user_id, is_active, changed_by) VALUES (?, ?, ?)",
                userId, isActive, changedBy);
    }

    /**
     * Get activation history for a user.
     *
     * @param userId the ID of the user
     * @return list of activation changes ordered by most recent first
     */
    public List<UserActivationHistory> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "SELECT id, user_id, is_active, changed_by, changed_at FROM user_activation_history WHERE user_id = ? ORDER BY changed_at DESC",
                this::mapRow,
                userId);
    }

    /**
     * Get latest activation change for a user.
     *
     * @param userId the ID of the user
     * @return the most recent activation history record, or null if none exists
     */
    public UserActivationHistory findLatestByUserId(Long userId) {
        List<UserActivationHistory> history = jdbcTemplate.query(
                "SELECT id, user_id, is_active, changed_by, changed_at FROM user_activation_history WHERE user_id = ? ORDER BY changed_at DESC LIMIT 1",
                this::mapRow,
                userId);
        return history.isEmpty() ? null : history.get(0);
    }

    private UserActivationHistory mapRow(ResultSet rs, int rowNum) throws SQLException {
        UserActivationHistory history = new UserActivationHistory();
        history.setId(rs.getLong("id"));
        history.setUserId(rs.getLong("user_id"));
        history.setActive(rs.getBoolean("is_active"));
        history.setChangedBy(rs.getLong("changed_by"));

        java.sql.Timestamp changedAt = rs.getTimestamp("changed_at");
        if (changedAt != null) {
            history.setChangedAt(changedAt.toLocalDateTime());
        }

        return history;
    }
}
