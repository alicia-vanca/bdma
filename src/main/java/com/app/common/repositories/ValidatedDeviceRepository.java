package com.app.common.repositories;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import com.app.common.models.ValidatedDevice;

@Repository
public class ValidatedDeviceRepository {

    private final JdbcTemplate jdbcTemplate;

    public ValidatedDeviceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private ValidatedDevice mapRow(ResultSet rs, int rowNum) throws SQLException {
        ValidatedDevice device = new ValidatedDevice();
        device.setId(rs.getLong("id"));
        device.setDeviceName(rs.getString("device_name"));
        device.setHardwareId(rs.getString("hardware_id"));
        long whitelistId = rs.getLong("whitelist_id");
        device.setWhitelistId(rs.wasNull() ? null : whitelistId);
        device.setValidatedAt(rs.getString("validated_at"));
        device.setLastSeenAt(rs.getString("last_seen_at"));
        device.setLastSyncAt(rs.getString("last_sync_at"));
        device.setCameraId(rs.getString("camera_id"));
        device.setActive(rs.getBoolean("is_active"));
        return device;
    }

    public ValidatedDevice saveOrUpdate(String cameraId, String hardwareId, Long whitelistId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO validated_device (device_name, hardware_id, whitelist_id, validated_at, last_seen_at, camera_id, is_active) "
                        +
                        "VALUES (?, ?, ?, datetime('now', 'localtime'), datetime('now', 'localtime'), ?, TRUE) " +
                        "ON CONFLICT (camera_id) DO UPDATE SET " +
                        "hardware_id = excluded.hardware_id, " +
                        "whitelist_id = excluded.whitelist_id, " +
                        "last_seen_at = datetime('now', 'localtime') " +
                        "RETURNING *",
                this::mapRow,
                cameraId, hardwareId, whitelistId, cameraId);
    }

    public Long insert(ValidatedDevice validatedDevice) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO validated_device (device_name, hardware_id, whitelist_id, last_seen_at, camera_id, is_active) "
                            +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, validatedDevice.getDeviceName());
            ps.setObject(2, validatedDevice.getHardwareId());
            ps.setObject(3, validatedDevice.getWhitelistId());
            ps.setString(4, validatedDevice.getLastSeenAt());
            ps.setString(5, validatedDevice.getCameraId());
            ps.setBoolean(6, validatedDevice.isActive());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : null;
    }

    public Optional<ValidatedDevice> findByCameraId(String cameraId) {
        String sql = "SELECT * FROM validated_device WHERE camera_id = ? LIMIT 1";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::mapRow, cameraId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ValidatedDevice> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM validated_device ORDER BY CASE WHEN device_name IS NULL OR device_name = '' THEN camera_id ELSE device_name END",
                this::mapRow);
    }

    public void updateDeviceName(Long id, String deviceName) {
        jdbcTemplate.update(
                "UPDATE validated_device SET device_name = ? WHERE id = ?",
                deviceName,
                id);
    }

    /**
     * Records completion time for a device sync while preserving connection
     * history.
     *
     * @param cameraId stable camera identifier for the synced device
     */
    public void markLastSync(String cameraId) {
        jdbcTemplate.update(
                "UPDATE validated_device SET last_sync_at = datetime('now', 'localtime') WHERE camera_id = ?",
                cameraId);
    }

    public void deactivate(Long id) {
        jdbcTemplate.update(
                "UPDATE validated_device SET is_active = FALSE WHERE id = ?",
                id);
    }

    public void reactivate(Long id) {
        jdbcTemplate.update(
                "UPDATE validated_device SET is_active = TRUE WHERE id = ?",
                id);
    }
}
