package com.app.user.controller;

import com.app.common.ui.BaseLayoutController;
import com.app.common.ui.LayoutAware;
import com.app.user.model.User;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope("prototype")
public class UserInfoController implements LayoutAware {

    private static final Logger log = LoggerFactory.getLogger(UserInfoController.class);

    @FXML
    private Label lblUsername;
    @FXML
    private Label lblRole;
    @FXML
    private Button btnBack;
    @FXML
    private javafx.scene.layout.HBox hboxBack;

    @Setter
    private Runnable onBack;
    private boolean showBack = true;

    // ── LayoutAware ─────────────────────────────────────────────────────────

    @Override
    public void setLayoutController(BaseLayoutController layoutController) {
        // No layout controller needed for this view
    }

    // ── Init ────────────────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        applyUI();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setUser(User user) {
        if (user == null) {
            log.warn("setUser called with null");
            return;
        }

        lblUsername.setText(user.getUsername());
        lblRole.setText(user.getRole().name());

        applyUI();
    }

    public void setShowBack(boolean showBack) {
        this.showBack = showBack;
        applyUI();
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    @FXML
    private void onBack() {
        if (onBack != null) {
            onBack.run();
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void applyUI() {
        if (btnBack == null)
            return;

        btnBack.setVisible(showBack);
        btnBack.setManaged(showBack);
        // Toggle the container too — it was hardcoded hidden in FXML, so the button
        // would never appear without also un-hiding its parent row.
        if (hboxBack != null) {
            hboxBack.setVisible(showBack);
            hboxBack.setManaged(showBack);
        }
    }
}