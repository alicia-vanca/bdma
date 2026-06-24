package com.app.common.services;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.LoginResult;
import com.app.common.definitions.enums.Role;
import com.app.common.events.UserAutoCreatedEvent;
import com.app.common.exceptions.CannotDeleteSelfException;
import com.app.common.exceptions.LastAdminException;
import com.app.common.exceptions.UserAlreadyExistsException;
import com.app.common.exceptions.ValidationException;
import com.app.common.models.User;
import com.app.common.modules.databaserecovery.services.DatabaseRecoveryService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.repositories.UserActivationHistoryRepository;
import com.app.common.repositories.UserRepository;
import com.app.common.utils.SecurityUtil;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserActivationHistoryRepository activationHistoryRepository;
    private final DatabaseRecoveryService databaseRecoveryService;
    private final AppNoticeService appNoticeService;
    private final ApplicationEventPublisher eventPublisher;

    private final Session session;

    public UserService(UserRepository userRepository, UserActivationHistoryRepository activationHistoryRepository,
            DatabaseRecoveryService databaseRecoveryService, AppNoticeService appNoticeService,
            ApplicationEventPublisher eventPublisher, Session session) {
        this.userRepository = userRepository;
        this.activationHistoryRepository = activationHistoryRepository;
        this.databaseRecoveryService = databaseRecoveryService;
        this.appNoticeService = appNoticeService;
        this.eventPublisher = eventPublisher;
        this.session = session;
    }

    /**
     * Encapsulates the outcome of a login attempt.
     *
     * @param result the authentication status indicating whether login succeeded,
     *               failed due to invalid credentials, or was rejected because the
     *               account is deactivated
     * @param user   the authenticated user when {@code result} is
     *               {@link LoginResult#SUCCESS}; {@code null} for all other results
     */
    public record LoginResponse(LoginResult result, User user) {
    }

    /**
     * Validates the supplied credentials and checks whether the matched account is
     * active.
     *
     * @param username the username to authenticate; it is normalized before lookup
     * @param password the plain-text password to verify against the stored
     *                 credential
     * @return a {@link LoginResponse} containing the {@link LoginResult} for the
     *         attempt
     *         and, when authentication succeeds, the authenticated {@link User};
     *         otherwise the user value is {@code null}
     */
    public LoginResponse loginWithStatus(String username, String password) {
        String normalized = normalizeUsername(username);
        if (normalized == null || normalized.isBlank()) {
            return new LoginResponse(LoginResult.INVALID_CREDENTIALS, null);
        }

        var userOpt = userRepository.findByUsername(normalized);
        if (userOpt.isEmpty()) {
            return new LoginResponse(LoginResult.INVALID_CREDENTIALS, null);
        }

        User user = userOpt.get();
        if (!SecurityUtil.verify(password, user.getPassword())) {
            return new LoginResponse(LoginResult.INVALID_CREDENTIALS, null);
        }

        if (!user.isActive()) {
            return new LoginResponse(LoginResult.ACCOUNT_DEACTIVATED, null);
        }

        return new LoginResponse(LoginResult.SUCCESS, user);
    }

    /**
     * Retrieve all users from the database.
     *
     * @return list of all users
     */
    public List<User> findAll() {
        return userRepository.findAll();
    }

    /**
     * Check if a username already exists in the database.
     *
     * @param username the username to check
     * @return true if the username exists, false otherwise
     */
    public boolean usernameExists(String username) {
        String normalizedUsername = normalizeUsername(username);
        return normalizedUsername != null
                && !normalizedUsername.isBlank()
                && userRepository.existsByUsername(normalizedUsername);
    }

    /**
     * Validate whether a username meets minimum length requirements.
     *
     * @param username the username to validate
     * @return true if the username is invalid, false if valid
     */
    public boolean isInvalidUsername(String username) {
        String normalized = normalizeUsername(username);
        return normalized == null || normalized.length() < AppConstants.USERNAME_MIN_LENGTH;
    }

    /**
     * Treat every DEV account username as reserved for normal user creation.
     * DEV users are installed by migration and hidden from user management.
     *
     * @param username the candidate username to compare against DEV accounts
     * @return true when the normalized username belongs to a DEV account
     */
    public boolean isReservedUsername(String username) {
        String normalized = normalizeUsername(username);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }

        return userRepository.findByRole(Role.DEV).stream()
                .map(User::getUsername)
                .map(this::normalizeUsername)
                .anyMatch(normalized::equals);
    }

    /**
     * Create a new user with validation. Username is normalized and password is
     * hashed.
     *
     * @param user the user to create
     * @return the created user with ID populated
     * @throws ValidationException        if validation fails
     * @throws UserAlreadyExistsException if username already exists
     */
    public User create(User user) {
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new ValidationException("Username is required");
        }

        String username = normalizeUsername(user.getUsername());

        if (isInvalidUsername(username)) {
            throw new ValidationException(I18n.get("user.username.invalid"));
        }

        if (isReservedUsername(username)) {
            throw new ValidationException(I18n.get("user.username.reserved"));
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
        databaseRecoveryService.backupSourceToBackup();
        log.info("User created: '{}'", username);

        return saved;
    }

    /**
     * Update an existing user. Username cannot be changed. If role is downgraded
     * from ADMIN,
     * ensures at least one admin remains in the system.
     *
     * @param user the user with updated fields
     * @throws ValidationException if validation fails
     * @throws LastAdminException  if attempting to downgrade the last admin
     */
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
        databaseRecoveryService.backupSourceToBackup();
        log.info("User updated: '{}'", username);
    }

    /**
     * Deactivate a user account. Cannot deactivate the current session user.
     * Records the change in activation history.
     *
     * @param userId the ID of the user to deactivate
     * @throws CannotDeleteSelfException if attempting to deactivate current session
     *                                   user
     */
    public void deactivate(Long userId) {
        if (!session.isAdmin()) {
            throw new ValidationException("Only admins can deactivate users");
        }

        if (userId.equals(session.getCurrentUserId())) {
            throw new CannotDeleteSelfException();
        }

        Long currentUserId = session.getCurrentUserId();
        userRepository.deactivate(userId);
        activationHistoryRepository.recordChange(userId, false, currentUserId);
        databaseRecoveryService.backupSourceToBackup();
        log.info("User deactivated: id={} by user={}", userId, currentUserId);
    }

    /**
     * Creates a default sync user with standard hash.
     * Used by restore and sync services for auto-creating users from filenames.
     * Shows notification and triggers callback if registered.
     */
    public User createDefaultSyncUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(AppConstants.DEFAULT_SYNC_USER_HASH);
        user.setRole(Role.USER);

        User saved = userRepository.save(user);
        log.info("Auto-created sync user '{}'", username);

        // Notify UI layers only for newly-created users.
        appNoticeService.showSuccess(I18n.get("user.auto.created", username));
        eventPublisher.publishEvent(new UserAutoCreatedEvent(username));

        return saved;
    }

    /**
     * Gets existing user ID or creates a new sync user if not found.
     * Returns null if user doesn't exist and session is not admin.
     * Username is normalized before lookup.
     */
    public Long getOrCreateSyncUser(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }

        String normalized = normalizeUsername(username);
        User existing = userRepository.findByUsername(normalized).orElse(null);
        if (existing != null) {
            return existing.getId();
        }

        if (!session.isAdmin()) {
            return null;
        }

        User created = createDefaultSyncUser(normalized);
        return created.getId();
    }

    /**
     * Reactivate a previously deactivated user account.
     * Records the change in activation history.
     *
     * @param userId the ID of the user to reactivate
     */
    public void reactivate(Long userId) {
        if (!session.isAdmin()) {
            throw new ValidationException("Only admins can reactivate users");
        }

        Long currentUserId = session.getCurrentUserId();
        userRepository.reactivate(userId);
        activationHistoryRepository.recordChange(userId, true, currentUserId);
        databaseRecoveryService.backupSourceToBackup();
        log.info("User reactivated: id={} by user={}", userId, currentUserId);
    }

    private String normalizeUsername(String username) {
        if (username == null)
            return null;
        return username.trim().toLowerCase();
    }

    /**
     * Find all users with the USER role (excludes admins).
     *
     * @return list of users with USER role
     */
    public List<User> findUsersOnly() {
        return userRepository.findByRole(Role.USER);
    }
}
