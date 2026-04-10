package com.app.user.service;

import com.app.common.enums.Role;
import com.app.common.exception.CannotDeleteSelfException;
import com.app.common.exception.LastAdminException;
import com.app.common.exception.UserAlreadyExistsException;
import com.app.common.exception.ValidationException;
import com.app.common.session.Session;
import com.app.common.util.SecurityUtil;
import com.app.common.i18n.I18n;
import com.app.user.model.User;
import com.app.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public static final int USERNAME_MIN_LENGTH = 4;

    private final Session session;

    public UserService(UserRepository userRepository, Session session) {
        this.userRepository = userRepository;
        this.session = session;
    }

    public User login(String username, String password) {
        String normalized = normalizeUsername(username);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }

        return userRepository.findByUsername(normalized)
                .filter(u -> SecurityUtil.verify(password, u.getPassword()))
                .orElse(null);
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public boolean usernameExists(String username) {
        String normalizedUsername = normalizeUsername(username);
        return normalizedUsername != null
                && !normalizedUsername.isBlank()
                && userRepository.existsByUsername(normalizedUsername);
    }

    public boolean isValidUsername(String username) {
        String normalized = normalizeUsername(username);
        return normalized != null && normalized.length() >= USERNAME_MIN_LENGTH;
    }

    public User create(User user) {
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new ValidationException("Username is required");
        }

        String username = normalizeUsername(user.getUsername());

        if (username == null || !isValidUsername(username)) {
            throw new ValidationException(I18n.get("user.username.invalid"));
        }

        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new ValidationException("Password is required");
        }

        if (user.getRole() == null) {
            throw new ValidationException("Role is required");
        }

        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException(username);
        }

        user.setUsername(username);
        user.setPassword(SecurityUtil.hash(user.getPassword()));

        User saved = userRepository.save(user);
        log.info("User created: '{}'", username);

        return saved;
    }

    public void update(User user) {
        User old = userRepository.findById(user.getId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String username = normalizeUsername(user.getUsername());
        if (!old.getUsername().equals(username)) {
            throw new ValidationException("Cannot change username");
        }

        if (user.getRole() == null) {
            throw new ValidationException("Role is required");
        }

        if (old.getRole() == Role.ADMIN && user.getRole() != Role.ADMIN) {
            long adminCount = userRepository.countByRole(Role.ADMIN);
            if (adminCount <= 1) {
                throw new LastAdminException("Cannot change role: this is the last admin account");
            }
        }

        old.setRole(user.getRole());

        if (user.getPassword() != null && !user.getPassword().isBlank()) {
            old.setPassword(SecurityUtil.hash(user.getPassword()));
        }

        userRepository.save(old);
        log.info("User updated: '{}'", username);
    }

    public void delete(Long id) {
        if (id.equals(session.getCurrentUserId())) {
            throw new CannotDeleteSelfException();
        }
        userRepository.deleteById(id);
        log.info("User deleted: id={}", id);
    }

    private String normalizeUsername(String username) {
        if (username == null)
            return null;
        return username.trim().toLowerCase();
    }
}