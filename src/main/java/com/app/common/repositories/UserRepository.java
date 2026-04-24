package com.app.common.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.app.common.definitions.enums.Role;
import com.app.common.models.User;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Query ────────────────────────────────────────────────────────────────

    /**
     * Retrieve all users from the database.
     *
     * @return list of all users
     */
    public List<User> findAll() {
        return jdbcTemplate.query(
                "SELECT id, username, password, role, is_active FROM user",
                this::mapRow);
    }

    /**
     * Find a user by their unique ID.
     *
     * @param id the user ID
     * @return Optional containing the user if found, empty otherwise
     */
    public Optional<User> findById(Long id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT id, username, password, role, is_active FROM user WHERE id = ?",
                    this::mapRow, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find a user by their username.
     *
     * @param username the username to search for
     * @return Optional containing the user if found, empty otherwise
     */
    public Optional<User> findByUsername(String username) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT id, username, password, role, is_active FROM user WHERE username = ?",
                    this::mapRow, username));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Check if a user with the given username exists.
     *
     * @param username the username to check
     * @return true if a user with this username exists, false otherwise
     */
    @SuppressWarnings("java:S2259")
    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?",
                Integer.class, username);
        return count > 0;
    }

    /**
     * Count the number of users with a specific role.
     *
     * @param role the role to count
     * @return the number of users with the specified role
     */
    @SuppressWarnings("java:S2259")
    public long countByRole(Role role) {
        String sql = "SELECT COUNT(*) FROM user WHERE role = ?";
        return jdbcTemplate.queryForObject(sql, Long.class, role.name());
    }

    // ── Persist ──────────────────────────────────────────────────────────────

    /**
     * Save or update a user. If the user has no ID, inserts a new record.
     * Otherwise, updates the existing record.
     *
     * @param user the user to save or update
     * @return the saved user with ID populated
     */
    public User save(User user) {
        if (user.getId() == null) {
            jdbcTemplate.update(
                    "INSERT INTO user(username, password, role, is_active) VALUES (?, ?, ?, ?)",
                    user.getUsername(), user.getPassword(), user.getRole().name(), user.isActive());
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM user WHERE username = ?",
                    Long.class,
                    user.getUsername());
            if (id != null && id > 0) {
                user.setId(id);
            }
        } else {
            jdbcTemplate.update(
                    "UPDATE user SET password = ?, role = ?, is_active = ? WHERE id = ?",
                    user.getPassword(), user.getRole().name(), user.isActive(), user.getId());
        }
        return user;
    }

    /**
     * Find all users with a specific role.
     *
     * @param role the role to filter by
     * @return list of users with the specified role
     */
    public List<User> findByRole(Role role) {
        return jdbcTemplate.query(
                "SELECT id, username, password, role, is_active FROM user WHERE role = ?",
                this::mapRow,
                role.name());
    }

    /**
     * Permanently delete a user by ID.
     *
     * @param id the ID of the user to delete
     */
    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM user WHERE id = ?", id);
    }

    /**
     * Deactivate user instead of deleting.
     *
     * @param userId the ID of the user to deactivate
     */
    public void deactivate(Long userId) {
        jdbcTemplate.update(
                "UPDATE user SET is_active = ? WHERE id = ?",
                false, userId);
    }

    /**
     * Reactivate user.
     *
     * @param userId the ID of the user to reactivate
     */
    public void reactivate(Long userId) {
        jdbcTemplate.update(
                "UPDATE user SET is_active = ? WHERE id = ?",
                true, userId);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private User mapRow(ResultSet rs, int rowNum) throws SQLException {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPassword(rs.getString("password"));
        u.setRole(Role.valueOf(rs.getString("role")));
        u.setActive(rs.getBoolean("is_active"));
        return u;
    }
}
