package com.app.common.repositories;

import com.app.common.definitions.enums.Role;
import com.app.common.models.User;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Query ────────────────────────────────────────────────────────────────

    public List<User> findAll() {
        return jdbcTemplate.query(
                "SELECT id, username, password, role FROM user",
                this::mapRow);
    }

    public Optional<User> findById(Long id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT id, username, password, role FROM user WHERE id = ?",
                    this::mapRow, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<User> findByUsername(String username) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT id, username, password, role FROM user WHERE username = ?",
                    this::mapRow, username));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("java:S2259")
    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?",
                Integer.class, username);
        return count > 0;
    }

    @SuppressWarnings("java:S2259")
    public long countByRole(Role role) {
        String sql = "SELECT COUNT(*) FROM user WHERE role = ?";
        return jdbcTemplate.queryForObject(sql, Long.class, role.name());
    }

    // ── Persist ──────────────────────────────────────────────────────────────

    public User save(User user) {
        if (user.getId() == null) {
            jdbcTemplate.update(
                    "INSERT INTO user(username, password, role) VALUES (?, ?, ?)",
                    user.getUsername(), user.getPassword(), user.getRole().name());
            Long id = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
            user.setId(id);
        } else {
            jdbcTemplate.update(
                    "UPDATE user SET password = ?, role = ? WHERE id = ?",
                    user.getPassword(), user.getRole().name(), user.getId());
        }
        return user;
    }

    public List<User> findByRole(Role role) {
        return jdbcTemplate.query(
                "SELECT id, username, password, role FROM user WHERE role = ?",
                this::mapRow,
                role.name()
        );
    }

    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM user WHERE id = ?", id);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private User mapRow(ResultSet rs, int rowNum) throws SQLException {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPassword(rs.getString("password"));
        u.setRole(Role.valueOf(rs.getString("role")));
        return u;
    }
}
