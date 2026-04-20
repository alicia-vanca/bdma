package com.app.admin.usermanagement.controllers;

import com.app.admin.usermanagement.validation.PasswordValidation;
import com.app.common.definitions.enums.Role;
import com.app.common.exceptions.AppException;
import com.app.common.exceptions.LastAdminException;
import com.app.common.modules.i18n.I18n;
import com.app.common.models.User;
import com.app.common.services.UserService;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

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
    private PasswordField txtPasswordConfirm;
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

    public UserEditFormController(UserService userService) {
        this.userService = userService;
    }

    @FXML
    public void initialize() {
        cbRole.setItems(FXCollections.observableArrayList(Role.values()));
        resetForm();

        txtUsername.textProperty().addListener((obs, oldValue, newValue) -> validateUsernameAsTyped());
        txtPassword.textProperty().addListener((obs, oldValue, newValue) -> hideError());
        txtPasswordConfirm.textProperty().addListener((obs, oldValue, newValue) -> hideError());
        cbRole.valueProperty().addListener((obs, oldValue, newValue) -> hideError());
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
        if (username.length() < UserService.USERNAME_MIN_LENGTH) {
            showError(I18n.get("user.username.invalid"));
            return;
        }

        // 2) username exists check
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
        lblRole.setText(user.getRole().name());
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