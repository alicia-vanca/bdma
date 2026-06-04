package com.app.dev.patchmanager.controllers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.Role;
import com.app.common.exceptions.AppException;
import com.app.common.helpers.NoticeStackRenderer;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.services.UserSettingService;
import com.app.common.utils.SecurityUtil;
import com.app.dev.patchmanager.services.PatchCryptoService;

import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

@Component
public class CreatePatchController {

    private enum PatchBuilderScenario {
        ADD_USER,
        UPDATE_USER,
        ADD_WHITELIST_MODEL,
        UPDATE_WHITELIST_MODEL
    }

    private record RuleInputs(TextField keyField, TextField valueField, Button removeButton) {
    }

    private static final Logger log = LoggerFactory.getLogger(CreatePatchController.class);
    private static final String ENCRYPTED_SQL_EXTENSION = ".sql.enc";
    private static final String STYLE_STATUS_SUCCESS = "status-success";
    private static final String STYLE_STATUS_ERROR = "status-error";
    private static final String KEY_BUILDER_USERNAME = "dev.patch.builder.username";
    private static final String KEY_BUILDER_PASSWORD = "dev.patch.builder.password";
    private static final String KEY_BUILDER_MODEL_NAME = "dev.patch.builder.modelName";
    private static final String KEY_BUILDER_NEW_MODEL_NAME = "dev.patch.builder.newModelName";
    private static final String KEY_BUILDER_KEEP_CURRENT = "dev.patch.builder.keepCurrentIfBlank";
    private static final String KEY_BUILDER_STATUS = "dev.patch.builder.status";
    private static final String KEY_BUILDER_CURRENT_USERNAME = "dev.patch.builder.currentUsername";
    private static final String KEY_BUILDER_CURRENT_MODEL_NAME = "dev.patch.builder.currentModelName";
    private static final String SQL_VALUES_PREFIX = "VALUES (";

    private final PatchCryptoService patchCryptoService;
    private final UserSettingService userSettingService;
    private final Session session;
    private final List<RuleInputs> whitelistRuleInputs = new ArrayList<>();

    @FXML
    private StackPane root;
    @FXML
    private Button btnLoadPatchSql;
    @FXML
    private Button btnPackTypedPatch;
    @FXML
    private Button btnTogglePatchBuilder;
    @FXML
    private Button btnExpandPatchBuilder;
    @FXML
    private ComboBox<PatchBuilderScenario> cbPatchBuilderScenario;
    @FXML
    private Label lblPatchBuilderStatus;
    @FXML
    private Label lblPatchValidationStatus;
    @FXML
    private TextArea txtPatchBuilderPreview;
    @FXML
    private TextArea txtPatchSql;
    @FXML
    private GridPane patchBuilderForm;
    @FXML
    private VBox noticeContainer;
    @FXML
    private VBox patchBuilderPanel;

    private NoticeStackRenderer noticeRenderer;
    private TextField txtBuilderUsername;
    private TextField txtBuilderCurrentUsername;
    private TextField txtBuilderNewUsername;
    private TextField txtBuilderPassword;
    private TextField txtBuilderModelName;
    private TextField txtBuilderCurrentModelName;
    private TextField txtBuilderNewModelName;
    private ComboBox<Role> cbBuilderRole;
    private RadioButton rbBuilderActive;
    private VBox whitelistRulesBox;
    private boolean patchBuilderCollapsed;
    private boolean patchBuilderTouched;
    private boolean patchBuilderRebuilding;

    public CreatePatchController(
            PatchCryptoService patchCryptoService,
            UserSettingService userSettingService,
            Session session) {
        this.patchCryptoService = patchCryptoService;
        this.userSettingService = userSettingService;
        this.session = session;
    }

    @FXML
    public void initialize() {
        noticeRenderer = new NoticeStackRenderer(noticeContainer);
        setupPatchBuilder();
        refreshTypedPatchState(false, null);
        ChangeListener<String> validator = (obs, oldValue, newValue) -> validateTypedSql(false);
        txtPatchSql.textProperty().addListener(validator);
    }

    private void setupPatchBuilder() {
        cbPatchBuilderScenario = new ComboBox<>(FXCollections.observableArrayList(PatchBuilderScenario.values()));
        cbPatchBuilderScenario.setMaxWidth(Double.MAX_VALUE);
        cbPatchBuilderScenario.setConverter(new StringConverter<>() {
            @Override
            public String toString(PatchBuilderScenario scenario) {
                return scenario == null ? "" : builderScenarioLabel(scenario);
            }

            @Override
            public PatchBuilderScenario fromString(String value) {
                return null;
            }
        });
        cbPatchBuilderScenario.valueProperty().addListener((obs, oldValue, newValue) -> rebuildPatchBuilderForm());
        cbPatchBuilderScenario.setValue(PatchBuilderScenario.ADD_USER);
    }

    @FXML
    public void onTogglePatchBuilder() {
        patchBuilderCollapsed = !patchBuilderCollapsed;
        patchBuilderPanel.setVisible(!patchBuilderCollapsed);
        patchBuilderPanel.setManaged(!patchBuilderCollapsed);
        btnExpandPatchBuilder.setVisible(patchBuilderCollapsed);
        btnExpandPatchBuilder.setManaged(patchBuilderCollapsed);
    }

    @FXML
    public void onClearPatchBuilder() {
        rebuildPatchBuilderForm();
    }

    @FXML
    public void onAddPatchStatement() {
        String sql = buildPatchBuilderSql();
        if (sql == null || sql.isBlank()) {
            return;
        }
        String existing = txtPatchSql.getText();
        txtPatchSql.setText((existing == null || existing.isBlank()) ? sql
                : existing.stripTrailing() + System.lineSeparator() + System.lineSeparator() + sql);
        txtPatchSql.positionCaret(txtPatchSql.getText().length());
        showPatchBuilderStatus(I18n.get("dev.patch.builder.statementAdded"), true);
    }

    @FXML
    public void onLoadPatchSql() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("dev.patch.chooser.loadSql.title"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.get("dev.patch.chooser.sql.files"), "*.sql"));
        applyLastChooserDirectory(chooser);
        File selected = chooser.showOpenDialog(getStage());
        if (selected == null) {
            return;
        }

        saveChooserDirectory(selected);
        try {
            txtPatchSql.setText(Files.readString(selected.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("Failed to load patch SQL file", e);
            showNotice(I18n.get("dev.patch.loadSql.error"), false);
        }
    }

    @FXML
    public void onPackTypedPatch() {
        if (!validateTypedSql(false)) {
            return;
        }

        UUID patchId = UUID.randomUUID();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("dev.patch.chooser.save.title"));
        chooser.setInitialFileName("BDMA_Patch_" + patchId);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.get("dev.patch.chooser.encrypted.files"), "*" + ENCRYPTED_SQL_EXTENSION));
        applyLastChooserDirectory(chooser);
        File selected = chooser.showSaveDialog(getStage());
        if (selected == null) {
            return;
        }

        saveChooserDirectory(selected);
        try {
            Path encFile = normalizeEncryptedPatchPath(selected.toPath());
            patchCryptoService.encryptSql(txtPatchSql.getText(), encFile, patchId);
            showNotice(I18n.get("dev.patch.save.success", encFile.getFileName()), true);
        } catch (Exception e) {
            log.error("Failed to save encrypted patch", e);
            showNotice(I18n.get("dev.patch.packTyped.encrypt.error"), false);
        }
    }

    private Path normalizeEncryptedPatchPath(Path selectedPath) {
        String fileName = selectedPath.getFileName().toString();
        while (fileName.endsWith(ENCRYPTED_SQL_EXTENSION + ENCRYPTED_SQL_EXTENSION)) {
            fileName = fileName.substring(0, fileName.length() - ENCRYPTED_SQL_EXTENSION.length());
        }
        if (!fileName.endsWith(ENCRYPTED_SQL_EXTENSION)) {
            fileName = fileName + ENCRYPTED_SQL_EXTENSION;
        }
        return selectedPath.resolveSibling(fileName);
    }

    private boolean validateTypedSql(boolean showNotice) {
        return validatePatchSqlText(txtPatchSql.getText(), showNotice);
    }

    private boolean validatePatchSqlText(String sql, boolean showNotice) {
        if (sql == null || sql.isBlank()) {
            refreshTypedPatchState(false, null);
            return false;
        }
        try {
            List<String> statements = patchCryptoService.validateSql(sql);
            refreshTypedPatchState(true, I18n.get("dev.patch.validation.valid", statements.size()));
            if (showNotice) {
                showNotice(I18n.get("dev.patch.validation.sqlValid"), true);
            }
            return true;
        } catch (Exception e) {
            refreshTypedPatchState(false, e.getMessage());
            if (showNotice) {
                showNotice(e.getMessage(), false);
            }
            return false;
        }
    }

    private void refreshTypedPatchState(boolean valid, String message) {
        btnPackTypedPatch.setDisable(false);
        boolean hasMessage = message != null && !message.isBlank();
        lblPatchValidationStatus.setText(hasMessage ? message : "");
        lblPatchValidationStatus.setVisible(hasMessage);
        lblPatchValidationStatus.setManaged(hasMessage);
        lblPatchValidationStatus.getStyleClass().removeAll(STYLE_STATUS_SUCCESS, STYLE_STATUS_ERROR);
        if (hasMessage) {
            lblPatchValidationStatus.getStyleClass().add(valid ? STYLE_STATUS_SUCCESS : STYLE_STATUS_ERROR);
        }
    }

    private void rebuildPatchBuilderForm() {
        patchBuilderTouched = false;
        patchBuilderRebuilding = true;
        patchBuilderForm.getChildren().clear();
        whitelistRuleInputs.clear();
        addBuilderRow(0, I18n.get("dev.patch.builder.scenario"), cbPatchBuilderScenario);
        addBuilderSeparator(1);
        PatchBuilderScenario scenario = cbPatchBuilderScenario.getValue();
        if (scenario == PatchBuilderScenario.ADD_USER || scenario == PatchBuilderScenario.UPDATE_USER) {
            buildUserFields(scenario == PatchBuilderScenario.ADD_USER);
        } else {
            buildWhitelistFields();
        }
        patchBuilderRebuilding = false;
        updatePatchBuilderPreview(false);
        clearPatchBuilderStatus(true);
    }

    private void buildUserFields(boolean createMode) {
        txtBuilderUsername = builderTextField(I18n.get(KEY_BUILDER_USERNAME));
        txtBuilderPassword = builderTextField(createMode
                ? I18n.get(KEY_BUILDER_PASSWORD)
                : I18n.get(KEY_BUILDER_KEEP_CURRENT));
        cbBuilderRole = new ComboBox<>(FXCollections.observableArrayList(Role.ADMIN, Role.USER, Role.DEV));
        cbBuilderRole.setConverter(new StringConverter<>() {
            @Override
            public String toString(Role role) {
                return role == null ? "" : role.getLocalizedName();
            }

            @Override
            public Role fromString(String value) {
                return null;
            }
        });
        cbBuilderRole.setValue(Role.USER);
        cbBuilderRole.setMaxWidth(Double.MAX_VALUE);
        Node statusSelector = createStatusSelector();
        cbBuilderRole.valueProperty().addListener((obs, oldValue, newValue) -> markPatchBuilderTouchedAndRefresh());
        if (createMode) {
            addBuilderRow(2, I18n.get(KEY_BUILDER_USERNAME), txtBuilderUsername);
            addBuilderRow(3, I18n.get(KEY_BUILDER_PASSWORD), txtBuilderPassword);
            addBuilderRow(4, I18n.get("dev.patch.builder.role"), cbBuilderRole);
            addBuilderInlineRow(5, I18n.get(KEY_BUILDER_STATUS), statusSelector);
        } else {
            txtBuilderCurrentUsername = builderTextField(I18n.get(KEY_BUILDER_CURRENT_USERNAME));
            txtBuilderNewUsername = builderTextField(I18n.get(KEY_BUILDER_KEEP_CURRENT));
            addBuilderRow(2, I18n.get(KEY_BUILDER_CURRENT_USERNAME), txtBuilderCurrentUsername);
            addBuilderSeparator(3);
            addBuilderRow(4, I18n.get("dev.patch.builder.newUsername"), txtBuilderNewUsername);
            addBuilderRow(5, I18n.get(KEY_BUILDER_PASSWORD), txtBuilderPassword);
            addBuilderRow(6, I18n.get("dev.patch.builder.role"), cbBuilderRole);
            addBuilderInlineRow(7, I18n.get(KEY_BUILDER_STATUS), statusSelector);
        }
    }

    private void buildWhitelistFields() {
        boolean updateMode = cbPatchBuilderScenario.getValue() == PatchBuilderScenario.UPDATE_WHITELIST_MODEL;
        txtBuilderModelName = builderTextField(I18n.get(KEY_BUILDER_MODEL_NAME));
        if (updateMode) {
            txtBuilderCurrentModelName = builderTextField(I18n.get(KEY_BUILDER_CURRENT_MODEL_NAME));
            txtBuilderNewModelName = builderTextField(I18n.get(KEY_BUILDER_KEEP_CURRENT));
            addBuilderRow(2, I18n.get(KEY_BUILDER_CURRENT_MODEL_NAME), txtBuilderCurrentModelName);
            addBuilderSeparator(3);
            addBuilderRow(4, I18n.get(KEY_BUILDER_NEW_MODEL_NAME), txtBuilderNewModelName);
        } else {
            addBuilderRow(2, I18n.get(KEY_BUILDER_MODEL_NAME), txtBuilderModelName);
        }
        Node statusSelector = createStatusSelector();
        whitelistRulesBox = new VBox(8);
        whitelistRulesBox.getStyleClass().add("patch-builder-rules");
        addBuilderInlineRow(updateMode ? 5 : 3, I18n.get(KEY_BUILDER_STATUS), statusSelector);
        addBuilderRow(updateMode ? 6 : 4, I18n.get("dev.patch.builder.rules"), whitelistRulesBox);
        addWhitelistRule(AppConstants.ADB_PROP_PRODUCT_MODEL, "");
        addWhitelistRule(AppConstants.ADB_PROP_PRODUCT_DEVICE, "");
        addWhitelistRule(AppConstants.ADB_PROP_BOARD_PLATFORM, "");
        Button addRuleButton = new Button(I18n.get("dev.patch.builder.addRule"));
        addRuleButton.getStyleClass().add("btn-secondary");
        addRuleButton.setOnAction(event -> addWhitelistRule("", ""));
        whitelistRulesBox.getChildren().add(addRuleButton);
    }

    private Node createStatusSelector() {
        ToggleGroup statusGroup = new ToggleGroup();
        rbBuilderActive = new RadioButton(I18n.get("dev.patch.builder.active"));
        RadioButton rbBuilderInactive = new RadioButton(I18n.get("dev.patch.builder.inactive"));
        rbBuilderActive.setToggleGroup(statusGroup);
        rbBuilderInactive.setToggleGroup(statusGroup);
        rbBuilderActive.setSelected(true);
        statusGroup.selectedToggleProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null) {
                rbBuilderActive.setSelected(true);
                return;
            }
            markPatchBuilderTouchedAndRefresh();
        });
        HBox selector = new HBox(12, rbBuilderActive, rbBuilderInactive);
        selector.setAlignment(Pos.CENTER_LEFT);
        return selector;
    }

    private TextField builderTextField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setMaxWidth(Double.MAX_VALUE);
        field.textProperty().addListener((obs, oldValue, newValue) -> markPatchBuilderTouchedAndRefresh());
        return field;
    }

    private void markPatchBuilderTouchedAndRefresh() {
        if (patchBuilderRebuilding) {
            updatePatchBuilderPreview(false);
            return;
        }
        patchBuilderTouched = true;
        updatePatchBuilderPreview();
    }

    private void addBuilderRow(int row, String label, Node field) {
        Label rowLabel = new Label(label);
        rowLabel.getStyleClass().add("patch-builder-field-label");
        VBox fieldGroup = new VBox(4, rowLabel, field);
        fieldGroup.getStyleClass().add("patch-builder-field-group");
        patchBuilderForm.add(fieldGroup, 0, row);
        GridPane.setHgrow(fieldGroup, Priority.ALWAYS);
    }

    private void addBuilderInlineRow(int row, String label, Node field) {
        Label rowLabel = new Label(label);
        rowLabel.getStyleClass().add("patch-builder-field-label");
        HBox fieldGroup = new HBox(10, rowLabel, field);
        fieldGroup.getStyleClass().add("patch-builder-inline-field-group");
        patchBuilderForm.add(fieldGroup, 0, row);
        GridPane.setHgrow(fieldGroup, Priority.ALWAYS);
    }

    private void addBuilderSeparator(int row) {
        Separator separator = new Separator();
        separator.getStyleClass().add("patch-builder-separator");
        patchBuilderForm.add(separator, 0, row);
        GridPane.setHgrow(separator, Priority.ALWAYS);
    }

    private void addWhitelistRule(String key, String value) {
        TextField keyField = builderTextField(I18n.get("dev.patch.builder.ruleKey"));
        TextField valueField = builderTextField(I18n.get("dev.patch.builder.ruleValue"));
        keyField.setText(key);
        valueField.setText(value);
        Button removeButton = new Button("×");
        removeButton.getStyleClass().add("patch-builder-rule-remove");
        RuleInputs inputs = new RuleInputs(keyField, valueField, removeButton);
        removeButton.setOnAction(event -> {
            whitelistRuleInputs.remove(inputs);
            whitelistRulesBox.getChildren().remove(removeButton.getParent());
            updatePatchBuilderPreview();
        });
        whitelistRuleInputs.add(inputs);
        HBox row = new HBox(6, keyField, valueField, removeButton);
        row.getStyleClass().add("patch-builder-rule-row");
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(keyField, Priority.ALWAYS);
        HBox.setHgrow(valueField, Priority.ALWAYS);
        int insertIndex = Math.max(whitelistRulesBox.getChildren().size() - 1, 0);
        whitelistRulesBox.getChildren().add(insertIndex, row);
        updatePatchBuilderPreview(false);
    }

    private void updatePatchBuilderPreview() {
        updatePatchBuilderPreview(patchBuilderTouched);
    }

    private void updatePatchBuilderPreview(boolean showErrors) {
        txtPatchBuilderPreview.setText(buildPatchBuilderSql(showErrors));
    }

    private String buildPatchBuilderSql() {
        patchBuilderTouched = true;
        return buildPatchBuilderSql(true);
    }

    private String buildPatchBuilderSql(boolean showErrors) {
        try {
            PatchBuilderScenario scenario = cbPatchBuilderScenario.getValue();
            String sql = switch (scenario) {
                case ADD_USER -> buildAddUserSql();
                case UPDATE_USER -> buildUpdateUserSql();
                case ADD_WHITELIST_MODEL -> buildAddWhitelistSql();
                case UPDATE_WHITELIST_MODEL -> buildUpdateWhitelistSql();
            };
            clearPatchBuilderStatus(showErrors);
            return sql;
        } catch (AppException e) {
            if (showErrors) {
                showPatchBuilderStatus(e.getMessage(), false);
            }
            return "";
        }
    }

    private String buildAddUserSql() {
        String username = required(txtBuilderUsername, I18n.get(KEY_BUILDER_USERNAME));
        String password = required(txtBuilderPassword, I18n.get(KEY_BUILDER_PASSWORD));
        Role role = cbBuilderRole.getValue();
        return "INSERT INTO user (username, password, role, is_active)" + System.lineSeparator()
                + SQL_VALUES_PREFIX + sqlString(username) + ", " + sqlString(SecurityUtil.hash(password)) + ", "
                + sqlString(role.name()) + ", " + sqlBoolean(isBuilderActive()) + ");";
    }

    private String buildUpdateUserSql() {
        String username = required(txtBuilderCurrentUsername, I18n.get(KEY_BUILDER_CURRENT_USERNAME));
        String newUsername = value(txtBuilderNewUsername);
        Role role = cbBuilderRole.getValue();
        String password = value(txtBuilderPassword);
        List<String> assignments = new ArrayList<>();
        if (!newUsername.isBlank()) {
            assignments.add("username = " + sqlString(newUsername));
        }
        if (!password.isBlank()) {
            assignments.add("password = " + sqlString(SecurityUtil.hash(password)));
        }
        assignments.add("role = " + sqlString(role.name()));
        assignments.add("is_active = " + sqlBoolean(isBuilderActive()));
        return "UPDATE user" + System.lineSeparator()
                + "SET " + String.join("," + System.lineSeparator() + "    ", assignments) + System.lineSeparator()
                + "WHERE username = " + sqlString(username) + ";";
    }

    private String buildAddWhitelistSql() {
        String modelName = required(txtBuilderModelName, I18n.get(KEY_BUILDER_MODEL_NAME));
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO model_whitelist (model_name, is_active)").append(System.lineSeparator())
                .append(SQL_VALUES_PREFIX).append(sqlString(modelName)).append(", ")
                .append(isBuilderActive() ? "1" : "0").append(");");
        appendWhitelistRuleSql(sql,
                "(SELECT MAX(id) FROM model_whitelist WHERE model_name = " + sqlString(modelName) + ")", false);
        return sql.toString();
    }

    private String buildUpdateWhitelistSql() {
        String modelName = required(txtBuilderCurrentModelName, I18n.get(KEY_BUILDER_CURRENT_MODEL_NAME));
        String newModelName = value(txtBuilderNewModelName);
        String whitelistIdExpression = "(SELECT MAX(id) FROM model_whitelist WHERE model_name = " + sqlString(modelName)
                + ")";
        List<String> assignments = new ArrayList<>();
        if (!newModelName.isBlank()) {
            assignments.add("model_name = " + sqlString(newModelName));
        }
        assignments.add("is_active = " + (isBuilderActive() ? "1" : "0"));
        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE model_whitelist").append(System.lineSeparator())
                .append("SET ").append(String.join("," + System.lineSeparator() + "    ", assignments))
                .append(System.lineSeparator())
                .append("WHERE id = ").append(whitelistIdExpression).append(";");
        appendWhitelistRuleSql(sql, whitelistIdExpression, true);
        return sql.toString();
    }

    private void appendWhitelistRuleSql(StringBuilder sql, String whitelistIdExpression, boolean upsert) {
        for (RuleInputs inputs : whitelistRuleInputs) {
            String key = value(inputs.keyField());
            String expectedValue = value(inputs.valueField());
            if (key.isBlank() && expectedValue.isBlank()) {
                continue;
            }
            if (key.isBlank() || expectedValue.isBlank()) {
                throw new AppException(I18n.get("dev.patch.builder.ruleIncomplete"));
            }
            sql.append(System.lineSeparator()).append(System.lineSeparator())
                    .append("INSERT INTO model_whitelist_rule (whitelist_id, prop_key, expected_value)")
                    .append(System.lineSeparator())
                    .append("SELECT ").append(whitelistIdExpression).append(", ").append(sqlString(key)).append(", ")
                    .append(sqlString(expectedValue));
            if (upsert) {
                sql.append(System.lineSeparator())
                        .append("ON CONFLICT (whitelist_id, prop_key) DO UPDATE SET").append(System.lineSeparator())
                        .append("    expected_value = excluded.expected_value");
            }
            sql.append(";");
        }
    }

    private String required(TextField field, String label) {
        String value = value(field);
        if (value.isBlank()) {
            throw new AppException(I18n.get("dev.patch.builder.required", label));
        }
        return value;
    }

    private String value(TextField field) {
        return field == null || field.getText() == null ? "" : field.getText().trim();
    }

    private boolean isBuilderActive() {
        return rbBuilderActive == null || rbBuilderActive.isSelected();
    }

    private String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String sqlBoolean(boolean value) {
        return value ? "TRUE" : "FALSE";
    }

    private String builderScenarioLabel(PatchBuilderScenario scenario) {
        return switch (scenario) {
            case ADD_USER -> I18n.get("dev.patch.builder.scenario.addUser");
            case UPDATE_USER -> I18n.get("dev.patch.builder.scenario.updateUser");
            case ADD_WHITELIST_MODEL -> I18n.get("dev.patch.builder.scenario.addWhitelistModel");
            case UPDATE_WHITELIST_MODEL -> I18n.get("dev.patch.builder.scenario.updateWhitelistModel");
        };
    }

    private void showPatchBuilderStatus(String message, boolean success) {
        lblPatchBuilderStatus.setText(message);
        lblPatchBuilderStatus.setVisible(true);
        lblPatchBuilderStatus.setManaged(true);
        lblPatchBuilderStatus.getStyleClass().removeAll(STYLE_STATUS_SUCCESS, STYLE_STATUS_ERROR);
        lblPatchBuilderStatus.getStyleClass().add(success ? STYLE_STATUS_SUCCESS : STYLE_STATUS_ERROR);
    }

    private void clearPatchBuilderStatus(boolean shouldClear) {
        if (!shouldClear || lblPatchBuilderStatus == null) {
            return;
        }
        lblPatchBuilderStatus.setText("");
        lblPatchBuilderStatus.setVisible(false);
        lblPatchBuilderStatus.setManaged(false);
        lblPatchBuilderStatus.getStyleClass().removeAll(STYLE_STATUS_SUCCESS, STYLE_STATUS_ERROR);
    }

    private void applyLastChooserDirectory(FileChooser chooser) {
        File lastDir = new File(userSettingService.getLastOpenPath(session.getCurrentUserId()));
        if (lastDir.exists() && lastDir.isDirectory()) {
            chooser.setInitialDirectory(lastDir);
        }
    }

    private void saveChooserDirectory(File selected) {
        File parent = selected.getParentFile();
        if (parent != null && parent.exists() && parent.isDirectory()) {
            userSettingService.saveLastOpenPath(session.getCurrentUserId(), parent.getAbsolutePath());
        }
    }

    private Stage getStage() {
        return (Stage) root.getScene().getWindow();
    }

    private void showNotice(String message, boolean success) {
        if (success) {
            noticeRenderer.showSuccess(message);
        } else {
            noticeRenderer.showError(message);
        }
    }
}