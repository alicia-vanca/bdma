package com.app.device.repository;

import com.app.device.model.ValidatedDevice;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

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
        device.setWhitelistId(rs.getString("whitelist_id"));
        device.setValidatedAt(rs.getString("validated_at"));
        device.setLastSeenAt(rs.getString("last_seen_at"));
        return device;
    }

    public Optional<ValidatedDevice> findByHardwareId(String hardwareId) {
        String sql = "SELECT * FROM validated_device WHERE hardware_id = ? LIMIT 1";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::mapRow, hardwareId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public ValidatedDevice saveOrUpdate(String deviceName, String hardwareId, String whitelistId) {
        Optional<ValidatedDevice> existingOpt = findByHardwareId(hardwareId);
        if (existingOpt.isPresent()) {
            jdbcTemplate.update(
                    "UPDATE validated_device SET device_name = ?, whitelist_id = ?, last_seen_at = datetime('now', 'localtime') WHERE hardware_id = ?",
                    deviceName,
                    whitelistId,
                    hardwareId);
            return findByHardwareId(hardwareId).orElse(existingOpt.get());
        }

        jdbcTemplate.update(
                "INSERT INTO validated_device (device_name, hardware_id, whitelist_id, validated_at, last_seen_at) VALUES (?, ?, ?, datetime('now', 'localtime'), datetime('now', 'localtime'))",
                deviceName,
                hardwareId,
                whitelistId);

        return findByHardwareId(hardwareId)
                .orElse(new ValidatedDevice(null, deviceName, hardwareId, whitelistId, null, null));
    }

    public Optional<Long> findDeviceIdByName(String deviceName) {
        String sql = """
                    SELECT id
                    FROM validated_device
                    WHERE device_name = ?
                    LIMIT 1
                """;

        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(sql, Long.class, deviceName)
            );
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean isAllowed(String serial) {
        return findByHardwareId(serial).isPresent();
    }
    
    public List<ValidatedDevice> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM validated_device ORDER BY last_seen_at DESC",
                this::mapRow);
    }
}
