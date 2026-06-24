package com.app.common.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.app.common.models.DeviceActivationHistory;

@Repository
public class DeviceActivationHistoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public DeviceActivationHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Records each activation change so admins can see when a device was
     * deactivated and who changed it.
     *
     * @param deviceId  saved device ID
     * @param active    new activation state
     * @param changedBy admin user ID that changed the state
     */
    public void recordChange(Long deviceId, boolean active, Long changedBy) {
        jdbcTemplate.update(
                "INSERT INTO device_activation_history (device_id, is_active, changed_by) VALUES (?, ?, ?)",
                deviceId,
                active,
                changedBy);
    }

    public Optional<DeviceActivationHistory> findLatestDeactivation(Long deviceId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT * FROM device_activation_history "
                            + "WHERE device_id = ? AND is_active = FALSE "
                            + "ORDER BY changed_at DESC LIMIT 1",
                    this::mapRow,
                    deviceId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private DeviceActivationHistory mapRow(ResultSet rs, int rowNum) throws SQLException {
        DeviceActivationHistory history = new DeviceActivationHistory();
        history.setId(rs.getLong("id"));
        history.setDeviceId(rs.getLong("device_id"));
        history.setActive(rs.getBoolean("is_active"));
        history.setChangedBy(rs.getLong("changed_by"));
        history.setChangedAt(rs.getString("changed_at"));
        return history;
    }
}
