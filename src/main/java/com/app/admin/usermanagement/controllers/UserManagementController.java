package com.app.admin.usermanagement.controllers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.MainApp;
import com.app.common.definitions.AppConstants;
import com.app.common.definitions.ViewPaths;
import com.app.common.definitions.enums.Role;
import com.app.common.definitions.enums.UserStatus;
import com.app.common.exceptions.CannotDeleteSelfException;
import com.app.common.helpers.AlertHelper;
import com.app.common.helpers.DialogHelper;
import com.app.common.helpers.ViewLoader;
import com.app.common.models.User;
import com.app.common.modules.datasync.services.DataSyncService;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.session.Session;
import com.app.common.services.AppNoticeService;
import com.app.common.services.UserService;
import com.app.user.userdetail.controllers.UserInfoController;

import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

@Component
public class UserManagementController {

    private static final Logger log = LoggerFactory.getLogger(UserManagementController.class);

    @FXML
    private TableView<User> table;
    @FXML
    private TableColumn<User, Number> colSTT;
    @FXML
    private TableColumn<User, String> colUsername;
    @FXML
    private TableColumn<User, String> colRole;
    @FXML
    private TableColumn<User, String> colStatus;
    @FXML
    private TableColumn<User, Void> colAction;
    @FXML
    private TextField txtSearch;
    @FXML
    private ComboBox<String> cbRole;
    @FXML
    private ComboBox<String> cbStatus;
    @FXML
    private StackPane root;
    @FXML
    private Button btnPrev;
    @FXML
    private Button btnNext;
    @FXML
    private Label lblPageInfo;
    @FXML
    private ComboBox<Integer> cbPageSize;

    private final UserService userService;
    private final ViewLoader viewLoader;
    private final Session session;
    private final AppNoticeService appNoticeService;
    private final DataSyncService dataSyncService;

    private List<User> allUsers = new ArrayList<>();
    private List<User> filteredUsers = new ArrayList<>();

    private int pageSize = AppConstants.DEFAULT_PAGE_SIZE;

    private int currentPageIndex = 0;

    // Persist filter state across language-change reloads. @FXML fields are
    // replaced
    // on each FXMLLoader cycle, so we keep the user's last inputs in plain fields
    // and restore them in initialize() instead of always defaulting to empty/all.
    private String savedSearchText = "";
    private String savedRoleFilter = null; // null = use locale-default "All"
    private String savedStatusFilter = null; // null = use locale-default "All"

    public UserManagementController(UserService userService,
            ViewLoader viewLoader,
            Session session,
            AppNoticeService appNoticeService,
            DataSyncService dataSyncService) {
        this.userService = userService;
        this.viewLoader = viewLoader;
        this.session = session;
        this.appNoticeService = appNoticeService;
        this.dataSyncService = dataSyncService;
    }

    // ── Init ────────────────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        if (!session.isAdmin()) {
            return;
        }
        setupRoleComboBox();
        setupStatusComboBox();
        setupFilterListeners();
        setupTableColumns();
        setupPageSizeComboBox();
        addActionColumn();
        table.setPlaceholder(new Label(I18n.get("user.empty")));
        loadData();
        restoreFilterState();
        // Re-apply filters after restoring UI state to keep table consistent with
        // filter controls
        onSearch();
        // Refresh user list when sync auto-creates a new account
        dataSyncService.setOnUserAutoCreated(username -> Platform.runLater(this::loadData));
    }

    private void setupRoleComboBox() {
        cbRole.getItems().addAll(
                I18n.get(AppConstants.FILTER_ALL_ROLES),
                Role.ADMIN.getLocalizedName(),
                Role.USER.getLocalizedName());
        // Restore previous role selection if the user had a non-default filter active,
        // translating the "All" sentinel to the current locale's string.
        String roleToRestore = (savedRoleFilter == null) ? I18n.get(AppConstants.FILTER_ALL_ROLES) : savedRoleFilter;

        cbRole.setValue(roleToRestore);
    }

    private void setupStatusComboBox() {
        cbStatus.getItems().addAll(
                I18n.get(AppConstants.FILTER_ALL_STATUS),
                UserStatus.ACTIVE.getLocalizedName(),
                UserStatus.INACTIVE.getLocalizedName());
        String statusToRestore = (savedStatusFilter == null) ? I18n.get(AppConstants.FILTER_ALL_STATUS)
                : savedStatusFilter;
        cbStatus.setValue(statusToRestore);
    }

    // Keep username filtering responsive while typing without requiring
    // explicit Search button clicks.
    private void setupFilterListeners() {
        txtSearch.textProperty().addListener((obs, oldValue, newValue) -> {
            savedSearchText = newValue == null ? "" : newValue;
            onSearch();
        });
        cbRole.valueProperty().addListener((obs, oldValue, newValue) -> {
            // Store null for the "All" sentinel so it re-translates correctly on reload.
            savedRoleFilter = (newValue == null || newValue.equals(I18n.get(AppConstants.FILTER_ALL_ROLES))) ? null
                    : newValue;
            onSearch();
        });
        cbStatus.valueProperty().addListener((obs, oldValue, newValue) -> {
            savedStatusFilter = (newValue == null || newValue.equals(I18n.get(AppConstants.FILTER_ALL_STATUS))) ? null
                    : newValue;
            onSearch();
        });
    }

    private void setupTableColumns() {
        colSTT.setCellValueFactory(c -> new SimpleIntegerProperty(
                currentPageIndex * pageSize + table.getItems().indexOf(c.getValue()) + 1));

        colUsername.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsername()));
        colUsername.setCellFactory(col -> new TableCell<>() {
            private final Label label = new Label();
            {
                label.setStyle("-fx-cursor: hand; -fx-text-fill: -fx-text-base-color; -fx-underline: false;");
                label.setOnMouseClicked(e -> {
                    if (!isEmpty() && getItem() != null) {
                        User user = getTableView().getItems().get(getIndex());
                        openUserInfo(user);
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    label.setText(item);
                    setGraphic(label);
                }
            }
        });

        colRole.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getRole().getLocalizedName()));

        colStatus.setCellValueFactory(c -> {
            boolean isActive = c.getValue().isActive();
            UserStatus status = isActive ? UserStatus.ACTIVE : UserStatus.INACTIVE;
            return new SimpleStringProperty(status.getLocalizedName());
        });
    }

    private void setupPageSizeComboBox() {
        cbPageSize.getItems().setAll(AppConstants.PAGE_SIZE_THRESHOLDS);
        cbPageSize.setValue(AppConstants.DEFAULT_PAGE_SIZE);

        cbPageSize.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null || Objects.equals(newValue, pageSize)) {
                return;
            }
            pageSize = newValue;
            currentPageIndex = 0;
            setupPagination();
        });
    }

    private void restoreFilterState() {
        if (!savedSearchText.isEmpty()) {
            txtSearch.setText(savedSearchText);
        }
    }

    // ── Data ────────────────────────────────────────────────────────────────

    private void loadData() {
        allUsers = userService.findAll();
        filteredUsers = new ArrayList<>(allUsers);
        setupPagination();
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    @FXML
    private void onAdd() {
        openForm(null);
    }

    @FXML
    private void onSearch() {
        String keyword = txtSearch.getText() == null ? "" : txtSearch.getText().toLowerCase().trim();
        String role = cbRole.getValue();
        String status = cbStatus.getValue();

        filteredUsers = allUsers.stream()
                .filter(u -> {
                    boolean matchUsername = u.getUsername().toLowerCase().contains(keyword);
                    boolean matchRole = role == null || role.equals(I18n.get(AppConstants.FILTER_ALL_ROLES)) ||
                            u.getRole().getLocalizedName().equals(role);
                    boolean matchStatus = matchesStatusFilter(u, status);
                    return matchUsername && matchRole && matchStatus;
                })
                .toList();

        currentPageIndex = 0;
        setupPagination();
    }

    private boolean matchesStatusFilter(User user, String statusFilter) {
        if (statusFilter == null || statusFilter.equals(I18n.get(AppConstants.FILTER_ALL_STATUS))) {
            return true;
        }
        if (statusFilter.equals(UserStatus.ACTIVE.getLocalizedName())) {
            return user.isActive();
        }
        if (statusFilter.equals(UserStatus.INACTIVE.getLocalizedName())) {
            return !user.isActive();
        }
        return false;
    }

    @FXML
    private void onReset() {
        txtSearch.clear();
        cbRole.setValue(I18n.get(AppConstants.FILTER_ALL_ROLES));
        cbStatus.setValue(I18n.get(AppConstants.FILTER_ALL_STATUS));
        filteredUsers = new ArrayList<>(allUsers);
        currentPageIndex = 0;
        setupPagination();
    }

    @FXML
    private void onPrevPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--;
            setupPagination();
        }
    }

    @FXML
    private void onNextPage() {
        if (currentPageIndex < getPageCount() - 1) {
            currentPageIndex++;
            setupPagination();
        }
    }

    // ── Table ────────────────────────────────────────────────────────────────

    private void addActionColumn() {
        colAction.setCellFactory(param -> new TableCell<>() {

            private final Button btnEdit = new Button(I18n.get("common.edit"));
            private final Button btnToggleActive = new Button();
            private final HBox actions = new HBox(10, btnEdit, btnToggleActive);

            {
                btnEdit.getStyleClass().add("btn-edit");
                actions.setAlignment(Pos.CENTER_LEFT);

                btnEdit.setOnAction(e -> openForm(getTableView().getItems().get(getIndex())));
                btnToggleActive.setOnAction(e -> onToggleActiveClicked(getTableView().getItems().get(getIndex())));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    int index = getIndex();
                    if (index < 0 || index >= getTableView().getItems().size()) {
                        setGraphic(null);
                        return;
                    }

                    User user = getTableView().getItems().get(index);
                    boolean isActive = user.isActive();

                    if (isActive) {
                        btnToggleActive.setText(I18n.get("user.deactivate"));
                        btnToggleActive.getStyleClass().setAll("button", "btn-delete");
                    } else {
                        btnToggleActive.setText(I18n.get("user.reactivate"));
                        btnToggleActive.getStyleClass().setAll("button", "btn-reactivate");
                    }

                    setGraphic(actions);
                }
            }
        });
    }

    private void onToggleActiveClicked(User user) {
        if (user == null) {
            return;
        }

        if (isCurrentSessionUser(user)) {
            showError(I18n.get("user.deactivate.self.error"));
            return;
        }

        boolean isActive = user.isActive();
        String confirmKey = isActive ? "user.deactivate.confirm" : "user.reactivate.confirm";

        Alert confirm = AlertHelper.createConfirmation(
                I18n.get("common.confirm"),
                null,
                I18n.get(confirmKey));

        confirm.showAndWait()
                .filter(type -> type == ButtonType.OK)
                .ifPresent(type -> toggleUserActive(user, isActive));
    }

    private void toggleUserActive(User user, boolean currentlyActive) {
        try {
            if (currentlyActive) {
                userService.deactivate(user.getId());
                log.info("Deactivated user '{}'", user.getUsername());
                showSuccess(I18n.get("user.deactivate.success"));
            } else {
                userService.reactivate(user.getId());
                log.info("Reactivated user '{}'", user.getUsername());
                showSuccess(I18n.get("user.reactivate.success"));
            }
            loadData();
        } catch (CannotDeleteSelfException ex) {
            showError(I18n.get("user.deactivate.self.error"));
        } catch (Exception ex) {
            log.error("Failed to toggle active status for user '{}'", user.getUsername(), ex);
            showError(I18n.get("user.toggle.error"));
        }
    }

    private void setupPagination() {
        int pageCount = getPageCount();
        if (currentPageIndex >= pageCount) {
            currentPageIndex = pageCount - 1;
        }
        updateTablePage();
        updatePagerControls(pageCount);
        table.refresh();
    }

    private void updateTablePage() {
        int from = currentPageIndex * pageSize;
        int to = Math.min(from + pageSize, filteredUsers.size());

        table.setItems(FXCollections.observableArrayList(
                from < to ? filteredUsers.subList(from, to) : List.of()));
    }

    private void updatePagerControls(int pageCount) {
        btnPrev.setDisable(currentPageIndex <= 0);
        btnNext.setDisable(currentPageIndex >= pageCount - 1);
        lblPageInfo.setText(I18n.get("common.page") + " " + (currentPageIndex + 1) + " / " + pageCount);
    }

    private int getPageCount() {
        return Math.max((int) Math.ceil((double) filteredUsers.size() / pageSize), 1);
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private void openForm(User user) {
        try {
            boolean isEdit = user != null;
            boolean isEditingCurrentUser = isEdit && isCurrentSessionUser(user);
            Role originalRole = null;
            if (user != null) {
                originalRole = user.getRole();
            }

            var dialog = DialogHelper.<UserEditFormController>createDialog(
                    ViewPaths.USER_FORM,
                    isEdit ? I18n.get("user.edit.title") : I18n.get("user.add.title"));

            prepareDialogForMode(dialog.controller(), user, isEdit);
            configureDialogCallbacks(dialog.controller(), user, isEdit, isEditingCurrentUser, originalRole);

            dialog.stage().setMinWidth(460);
            dialog.stage().setResizable(false);
            dialog.stage().showAndWait();

            refreshAfterDialog();

        } catch (Exception e) {
            log.error("Failed to open user form", e);
        }
    }

    // Keep create/edit setup explicit so openForm() stays focused on orchestration.
    private void prepareDialogForMode(UserEditFormController controller, User user, boolean isEdit) {
        if (isEdit) {
            controller.prepareForEdit(user);
            return;
        }
        controller.prepareForCreate();
    }

    private void configureDialogCallbacks(UserEditFormController controller,
            User user,
            boolean isEdit,
            boolean isEditingCurrentUser,
            Role originalRole) {
        controller.setOnSuccess(() -> {
            String key = isEdit ? "user.edit.success" : "user.add.success";
            showSuccess(I18n.get(key));

            if (shouldDowngradeCurrentSessionRole(user, isEditingCurrentUser, originalRole)) {
                downgradeCurrentSessionRole();
                navigateToDashboard();
            }
        });
        controller.setOnNoChange(() -> showSuccess(I18n.get("user.update.nochange")));
    }

    private void refreshAfterDialog() {
        loadData();
        // Re-apply current UI filters so table results stay consistent with the
        // visible filter controls after dialog closes.
        onSearch();
    }

    private boolean isCurrentSessionUser(User user) {
        return user != null && Objects.equals(user.getId(), session.getCurrentUserId());
    }

    private boolean shouldDowngradeCurrentSessionRole(User user,
            boolean isEditingCurrentUser,
            Role originalRole) {
        return user != null
                && isEditingCurrentUser
                && originalRole == Role.ADMIN
                && user.getRole() == Role.USER;
    }

    private void downgradeCurrentSessionRole() {
        User sessionUser = session.getUser();
        if (sessionUser != null) {
            sessionUser.setRole(Role.USER);
        }
    }

    private void navigateToDashboard() {
        MainApp.showAdmin();
    }

    private void openUserInfo(User user) {
        var result = viewLoader.loadView(ViewPaths.USER_INFO);
        if (result == null) {
            log.error("Failed to load user-info view");
            return;
        }

        UserInfoController controller = (UserInfoController) result.controller();
        controller.setUser(user);

        Node previousView = root.getChildren().isEmpty() ? null : root.getChildren().get(0);
        controller.setOnBack(() -> {
            if (previousView != null) {
                root.getChildren().setAll(previousView);
            }
        });

        root.getChildren().setAll(result.node());
    }

    private void showSuccess(String text) {
        appNoticeService.showSuccess(text);
    }

    private void showError(String text) {
        appNoticeService.showError(text);
    }
}
