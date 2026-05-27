package com.app.user.controller;

import com.app.common.enums.Role;
import com.app.common.exception.AppException;
import com.app.common.exception.LastAdminException;
import com.app.common.i18n.I18n;
import com.app.user.model.User;
import com.app.user.service.UserService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UserFormController {

    private static final Logger log = LoggerFactory.getLogger(UserFormController.class);

    @FXML
    private TextField txtUsername;
    @FXML
    private PasswordField txtPassword;
    @FXML
    private ComboBox<Role> cbRole;
    @FXML
    private Label lblError;

    private final UserService userService;

    private User user;
    private Runnable onSuccess;

    public UserFormController(UserService userService) {
        this.userService = userService;
    }

    @FXML
    public void initialize() {
        cbRole.setItems(FXCollections.observableArrayList(Role.values()));
        lblError.setVisible(false);
    }

    public void setUser(User user) {
        this.user = user;

        if (user != null) {
            txtUsername.setText(user.getUsername());
            txtUsername.setDisable(true);
            cbRole.setValue(user.getRole());
        }
    }

    public void setOnSuccess(Runnable onSuccess) {
        this.onSuccess = onSuccess;
    }

    @FXML
    private void onSave() {
        try {
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

    private void saveUser() {
        if (user == null) {
            User newUser = new User();
            newUser.setUsername(txtUsername.getText());
            newUser.setPassword(txtPassword.getText());
            newUser.setRole(cbRole.getValue());
            userService.create(newUser);
            log.info("Created user '{}'", newUser.getUsername());
        } else {
            user.setRole(cbRole.getValue());
            user.setPassword(txtPassword.getText());
            userService.update(user);
            log.info("Updated user '{}'", user.getUsername());
        }
    }

    private void runSuccessCallback() {
        if (onSuccess != null) {
            onSuccess.run();
        }
    }

    private void showError(String text) {
        lblError.setText(text);
        lblError.setVisible(true);
    }

    @FXML
    private void onCancel() {
        close();
    }

    private void close() {
        Stage stage = (Stage) txtUsername.getScene().getWindow();
        stage.close();
    }
}