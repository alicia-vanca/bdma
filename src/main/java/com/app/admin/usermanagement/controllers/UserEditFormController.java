package com.app.admin.usermanagement.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import com.app.admin.usermanagement.validation.PasswordValidation;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.Role;
import com.app.common.events.ThemeChangedEvent;
import com.app.common.exceptions.AppException;
import com.app.common.exceptions.LastAdminException;
import com.app.common.models.User;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.theme.ThemeManager;
import com.app.common.services.UserService;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import lombok.Setter;

@Component
@Scope("prototype")
public class UserEditFormController {

    private enum DialogMode {
        CREATE,
        EDIT,
        ACCOUNT
    }

    private static final Logger log = LoggerFactory.getLogger(UserEditFormController.class);

    @FXML
    private Label lblTitle;
    @FXML
    private TextField txtUsername;
    @FXML
    private Label lblUsername;
    @FXML
    private PasswordField txtPassword;
    @FXML
    private TextField txtPasswordVisible;
    @FXML
    private Button btnPeekPassword;
    @FXML
    private PasswordField txtPasswordConfirm;
    @FXML
    private TextField txtPasswordConfirmVisible;
    @FXML
    private Button btnPeekPasswordConfirm;
    @FXML
    private ComboBox<Role> cbRole;
    @FXML
    private Label lblRole;
    @FXML
    private Label lblPasswordCaption;
    @FXML
    private Label lblPasswordConfirmCaption;
    @FXML
    private Label lblError;

    private final UserService userService;

    private DialogMode mode = DialogMode.CREATE;
    private User user;
    @Setter
    private Runnable onSuccess;
    @Setter
    private Runnable onNoChange;

    private ImageView passwordIconView;
    private ImageView passwordConfirmIconView;

    public UserEditFormController(UserService userService) {
        this.userService = userService;
    }

    @FXML
    public void initialize() {
        cbRole.setItems(FXCollections.observableArrayList(
                Arrays.stream(Role.values())
                        .filter(role -> role != Role.DEV)
                        .toList()));
        resetForm();

        // Setup password peek functionality
        setupPasswordPeek();

        txtUsername.textProperty().addListener((obs, oldValue, newValue) -> validateUsernameAsTyped());
        txtPassword.textProperty().addListener((obs, oldValue, newValue) -> hideError());
        txtPasswordConfirm.textProperty().addListener((obs, oldValue, newValue) -> hideError());
        cbRole.valueProperty().addListener((obs, oldValue, newValue) -> hideError());
    }

    /**
     * Sets up password peek buttons with hold-to-show functionality for both
     * password fields.
     * User can press and hold the button to temporarily reveal the password.
     */
    private void setupPasswordPeek() {
        passwordIconView = new ImageView();
        passwordIconView.setFitWidth(27);
        passwordIconView.setFitHeight(27);
        btnPeekPassword.setGraphic(passwordIconView);

        passwordConfirmIconView = new ImageView();
        passwordConfirmIconView.setFitWidth(27);
        passwordConfirmIconView.setFitHeight(27);
        btnPeekPasswordConfirm.setGraphic(passwordConfirmIconView);

        setupPeekButton(btnPeekPassword, txtPassword, txtPasswordVisible, passwordIconView);
        setupPeekButton(btnPeekPasswordConfirm, txtPasswordConfirm, txtPasswordConfirmVisible, passwordConfirmIconView);

        // Load initial icons
        updatePasswordIcon(passwordIconView, false);
        updatePasswordIcon(passwordConfirmIconView, false);
    }

    private void setupPeekButton(Button btn, PasswordField passwordField, TextField visibleField,
            ImageView iconView) {
        // Bind text fields together
        visibleField.textProperty().bindBidirectional(passwordField.textProperty());

        // Show password on mouse press, hide on release
        btn.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            passwordField.setVisible(false);
            passwordField.setManaged(false);
            visibleField.setVisible(true);
            visibleField.setManaged(true);
            updatePasswordIcon(iconView, true);
        });

        btn.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            visibleField.setVisible(false);
            visibleField.setManaged(false);
            passwordField.setVisible(true);
            passwordField.setManaged(true);
            updatePasswordIcon(iconView, false);
        });

        // Handle case when mouse exits button while pressed
        btn.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (visibleField.isVisible()) {
                visibleField.setVisible(false);
                visibleField.setManaged(false);
                passwordField.setVisible(true);
                passwordField.setManaged(true);
                updatePasswordIcon(iconView, false);
            }
        });
    }

    /**
     * Updates a password peek icon based on current theme and visibility state.
     *
     * @param iconView the ImageView to update
     * @param isOpen   true if password is currently visible, false if hidden
     */
    private void updatePasswordIcon(ImageView iconView, boolean isOpen) {
        String theme = ThemeManager.getTheme();
        boolean isDark = AppConstants.THEME_DARK.equals(theme);

        String iconPath;
        if (isOpen) {
            iconPath = isDark ? "/image/eye-open-dark.png" : "/image/eye-open-light.png";
        } else {
            iconPath = isDark ? "/image/eye-closed-dark.png" : "/image/eye-closed-light.png";
        }

        Image icon = new Image(getClass().getResourceAsStream(iconPath));
        iconView.setImage(icon);
    }

    /**
     * Called by DialogHelper when theme changes to update password peek icons
     * without full UI reload.
     * Uses reflection invocation from DialogHelper's event listener.
     *
     * @param event the theme change event containing the new theme
     */
    public void onThemeChanged(ThemeChangedEvent event) {
        // Skip if controller not yet initialized
        if (txtPasswordVisible == null || passwordIconView == null) {
            return;
        }
        boolean isPasswordVisible = txtPasswordVisible.isVisible();
        boolean isPasswordConfirmVisible = txtPasswordConfirmVisible.isVisible();
        updatePasswordIcon(passwordIconView, isPasswordVisible);
        updatePasswordIcon(passwordConfirmIconView, isPasswordConfirmVisible);
    }

    public void prepareForCreate() {
        mode = DialogMode.CREATE;
        user = null;
        resetForm();
    }

    public void prepareForEdit(User user) {
        mode = DialogMode.EDIT;
        bindUser(user);
    }

    public void prepareForAccount(User user) {
        mode = DialogMode.ACCOUNT;
        bindUser(user);
    }

    @FXML
    private void onSave() {
        try {
            boolean shouldApplyPassword = validateForm();
            if (!hasChanges(shouldApplyPassword)) {
                close();
                runNoChangeCallback();
                return;
            }

            saveUser();
            close();
            runSuccessCallback();
        } catch (LastAdminException e) {
            log.warn("Business error: {}", e.getMessage());
            showError(I18n.get("user.last.admin.error"));
        } catch (AppException e) {
            log.warn("Business error: {}", e.getMessage());
            showError(e.getMessage());
        }
    }

    // Keep create, edit, and account dialog rules centralized so all user-related
    // popups share one FXML structure without diverging validation behavior.
    private boolean validateForm() {
        String username = currentUsername();
        if (mode != DialogMode.ACCOUNT && (username == null || username.isBlank())) {
            throw new AppException(I18n.get("user.username.required"));
        }

        if (mode != DialogMode.ACCOUNT && userService.isInvalidUsername(username)) {
            throw new AppException(I18n.get("user.username.invalid"));
        }

        if (mode == DialogMode.CREATE && userService.usernameExists(username)) {
            throw new AppException(I18n.get("user.username.exists"));
        }

        boolean shouldApplyPassword = PasswordValidation.validate(
                txtPassword.getText(),
                txtPasswordConfirm.getText(),
                mode == DialogMode.CREATE);

        if (mode != DialogMode.ACCOUNT && cbRole.getValue() == null) {
            throw new AppException(I18n.get("user.role.required"));
        }

        return shouldApplyPassword;
    }

    private boolean hasChanges(boolean shouldApplyPassword) {
        if (mode == DialogMode.CREATE) {
            return true;
        }

        if (mode == DialogMode.EDIT) {
            boolean roleChanged = user != null && cbRole.getValue() != user.getRole();
            return roleChanged || shouldApplyPassword;
        }

        // Account info mode only updates password; blank password means no-op.
        return shouldApplyPassword;
    }

    // Front-end live username checks (only apply to the create flow)
    private void validateUsernameAsTyped() {
        if (mode != DialogMode.CREATE) {
            hideError();
            return;
        }

        String username = currentUsername();
        if (username == null || username.isBlank()) {
            hideError();
            return;
        }

        // 1) minimum length check
        if (username.length() < AppConstants.USERNAME_MIN_LENGTH) {
            showError(I18n.get("user.username.invalid"));
            return;
        }

        // 2) reserved username check against hidden DEV accounts in the user table.
        if (userService.isReservedUsername(username)) {
            showError(I18n.get("user.username.reserved"));
            return;
        }

        // 3) username exists check
        if (userService.usernameExists(username)) {
            showError(I18n.get("user.username.exists"));
            return;
        }

        hideError();
    }

    // Toggle between editable fields and read-only labels so create, edit, and
    // account screens share the same row-based layout.
    private void applyMode() {
        boolean showUsernameField = mode == DialogMode.CREATE;
        boolean showRoleField = mode != DialogMode.ACCOUNT;

        lblTitle.setText(switch (mode) {
            case CREATE -> I18n.get("user.add.title");
            case EDIT -> I18n.get("user.edit.title");
            case ACCOUNT -> I18n.get("user.account.title");
        });

        lblPasswordCaption.setText(mode == DialogMode.ACCOUNT
                ? I18n.get("user.account.password.label")
                : I18n.get("user.password.label"));
        lblPasswordConfirmCaption.setText(I18n.get("user.password.confirm.label"));

        txtUsername.setVisible(showUsernameField);
        txtUsername.setManaged(showUsernameField);
        lblUsername.setVisible(!showUsernameField);
        lblUsername.setManaged(!showUsernameField);

        cbRole.setVisible(showRoleField);
        cbRole.setManaged(showRoleField);
        lblRole.setVisible(!showRoleField);
        lblRole.setManaged(!showRoleField);
    }

    private void bindUser(User user) {
        this.user = user;
        txtUsername.setText(user.getUsername());
        lblUsername.setText(user.getUsername());
        cbRole.setValue(user.getRole());
        lblRole.setText(user.getRole().getLocalizedName());
        txtPassword.clear();
        txtPasswordConfirm.clear();
        hideError();
        applyMode();
    }

    private void resetForm() {
        txtUsername.clear();
        txtPassword.clear();
        txtPasswordConfirm.clear();
        lblUsername.setText("");
        lblRole.setText("");
        cbRole.setValue(Role.USER);
        hideError();
        applyMode();
    }

    private String currentUsername() {
        if (mode == DialogMode.CREATE) {
            return txtUsername.getText();
        }
        return user != null ? user.getUsername() : null;
    }

    private void saveUser() {
        if (mode == DialogMode.CREATE) {
            User newUser = new User();
            newUser.setUsername(currentUsername());
            newUser.setPassword(txtPassword.getText());
            newUser.setRole(cbRole.getValue());
            userService.create(newUser);
            log.info("Created user '{}'", newUser.getUsername());
            return;
        }

        if (mode == DialogMode.EDIT) {
            user.setRole(cbRole.getValue());
            user.setPassword(txtPassword.getText());
            userService.update(user);
            log.info("Updated user '{}'", user.getUsername());
            return;
        }

        User updateCandidate = new User();
        updateCandidate.setId(user.getId());
        updateCandidate.setUsername(user.getUsername());
        updateCandidate.setRole(user.getRole());
        updateCandidate.setPassword(txtPassword.getText());
        userService.update(updateCandidate);
        log.info("Updated password for current user '{}'", user.getUsername());
    }

    private void runSuccessCallback() {
        if (onSuccess != null) {
            onSuccess.run();
        }
    }

    private void runNoChangeCallback() {
        if (onNoChange != null) {
            onNoChange.run();
        }
    }

    private void showError(String text) {
        lblError.setText(text);
        lblError.setVisible(true);
    }

    private void hideError() {
        lblError.setText("");
        lblError.setVisible(false);
    }

    @FXML
    private void onCancel() {
        close();
    }

    private void close() {
        Stage stage = (Stage) lblTitle.getScene().getWindow();
        stage.close();
    }
}
