package com.app.user.controller;

import com.app.admin.controller.AdminLayoutController;
import com.app.common.enums.Role;
import com.app.common.exception.CannotDeleteSelfException;
import com.app.common.i18n.I18n;
import com.app.common.session.Session;
import com.app.common.ui.BaseLayoutController;
import com.app.common.ui.DialogHelper;
import com.app.common.ui.LayoutAware;
import com.app.common.ui.ViewLoader;
import com.app.common.ui.ViewPaths;
import com.app.user.model.User;
import com.app.user.service.UserService;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class UserController implements LayoutAware {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private static final String COMMON_ALL = "common.all";

    @FXML
    private TableView<User> table;
    @FXML
    private TableColumn<User, Number> colSTT;
    @FXML
    private TableColumn<User, String> colUsername;
    @FXML
    private TableColumn<User, String> colRole;
    @FXML
    private TableColumn<User, Void> colAction;
    @FXML
    private TextField txtSearch;
    @FXML
    private ComboBox<String> cbRole;
    @FXML
    private StackPane root;
    @FXML
    private Button btnPrev;
    @FXML
    private Button btnNext;
    @FXML
    private Label lblPageInfo;

    private final UserService userService;
    private final ViewLoader viewLoader;

    private List<User> allUsers = new ArrayList<>();
    private List<User> filteredUsers = new ArrayList<>();

    private static final int PAGE_SIZE = 10;
    private int currentPageIndex = 0;

    private BaseLayoutController layoutController;
    private Node currentView;

    // Persist filter state across language-change reloads. @FXML fields are
    // replaced
    // on each FXMLLoader cycle, so we keep the user's last inputs in plain fields
    // and restore them in initialize() instead of always defaulting to empty/all.
    private String savedSearchText = "";
    private String savedRoleFilter = null; // null = use locale-default "All"

    public UserController(UserService userService, ViewLoader viewLoader) {
        this.userService = userService;
        this.viewLoader = viewLoader;
    }

    // ── LayoutAware ─────────────────────────────────────────────────────────

    @Override
    public void setLayoutController(BaseLayoutController layoutController) {
        this.layoutController = layoutController;
    }

    // ── Init ────────────────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        if (!Session.isAdmin()) {
            return;
        }
        currentView = root;
        setupRoleComboBox();
        setupFilterListeners();
        setupTableColumns();
        addActionColumn();
        loadData();
        restoreFilterState();
    }

    private void setupRoleComboBox() {
        cbRole.getItems().addAll(I18n.get(COMMON_ALL), Role.ADMIN.toString(), Role.USER.toString());
        // Restore previous role selection if the user had a non-default filter active,
        // translating the "All" sentinel to the current locale's string.
        String roleToRestore = (savedRoleFilter == null) ? I18n.get(COMMON_ALL) : savedRoleFilter;
        cbRole.setValue(roleToRestore);
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
            savedRoleFilter = (newValue == null || newValue.equals(I18n.get(COMMON_ALL))) ? null : newValue;
            onSearch();
        });
    }

    private void setupTableColumns() {
        colSTT.setCellValueFactory(c -> new SimpleIntegerProperty(
                currentPageIndex * PAGE_SIZE + table.getItems().indexOf(c.getValue()) + 1));

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

        colRole.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getRole().toString()));
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

        filteredUsers = allUsers.stream()
                .filter(u -> {
                    boolean matchUsername = u.getUsername().toLowerCase().contains(keyword);
                    boolean matchRole = role == null || role.equals(I18n.get(COMMON_ALL)) ||
                            u.getRole().toString().equals(role);
                    return matchUsername && matchRole;
                })
                .toList();

        currentPageIndex = 0;
        setupPagination();
    }

    @FXML
    private void onReset() {
        txtSearch.clear();
        cbRole.setValue(I18n.get(COMMON_ALL));
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
            private final Button btnDelete = new Button(I18n.get("common.delete"));

            {
                btnEdit.setStyle("-fx-background-color:#2980b9; -fx-text-fill:white;");
                btnDelete.setStyle("-fx-background-color:#c0392b; -fx-text-fill:white;");

                btnEdit.setOnAction(e -> openForm(getTableView().getItems().get(getIndex())));
                btnDelete.setOnAction(e -> onDeleteClicked(getTableView().getItems().get(getIndex())));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : new HBox(10, btnEdit, btnDelete));
            }
        });
    }

    private void onDeleteClicked(User user) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("common.confirm"));
        confirm.setHeaderText(null);
        confirm.setContentText(I18n.get("user.delete.confirm"));

        confirm.showAndWait()
                .filter(type -> type == ButtonType.OK)
                .ifPresent(type -> deleteUser(user));
    }

    private void deleteUser(User user) {
        try {
            userService.delete(user.getId());
            log.info("Deleted user '{}'", user.getUsername());
            loadData();
            showSuccess(I18n.get("user.delete.success"));
        } catch (CannotDeleteSelfException ex) {
            showError(I18n.get("user.delete.self.error"));
        } catch (Exception ex) {
            log.error("Failed to delete user '{}'", user.getUsername(), ex);
            showError(I18n.get("user.delete.error"));
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
        int from = currentPageIndex * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, filteredUsers.size());

        table.setItems(FXCollections.observableArrayList(
                from < to ? filteredUsers.subList(from, to) : List.of()));
    }

    private void updatePagerControls(int pageCount) {
        btnPrev.setDisable(currentPageIndex <= 0);
        btnNext.setDisable(currentPageIndex >= pageCount - 1);
        lblPageInfo.setText(I18n.get("common.page") + " " + (currentPageIndex + 1) + " / " + pageCount);
    }

    private int getPageCount() {
        return Math.max((int) Math.ceil((double) filteredUsers.size() / PAGE_SIZE), 1);
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

            var dialog = DialogHelper.<UserFormController>openWithController(
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
    private void prepareDialogForMode(UserFormController controller, User user, boolean isEdit) {
        if (isEdit) {
            controller.prepareForEdit(user);
            return;
        }
        controller.prepareForCreate();
    }

    private void configureDialogCallbacks(UserFormController controller,
            User user,
            boolean isEdit,
            boolean isEditingCurrentUser,
            Role originalRole) {
        controller.setOnSuccess(() -> {
            String key = isEdit ? "user.edit.success" : "user.add.success";
            showSuccess(I18n.get(key));

            if (shouldDowngradeCurrentSessionRole(user, isEditingCurrentUser, originalRole)) {
                downgradeCurrentSessionRole();
                navigateToDashboardIfAdminLayout();
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
        return user != null && Objects.equals(user.getId(), Session.getCurrentUserId());
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
        User sessionUser = Session.getUser();
        if (sessionUser != null) {
            sessionUser.setRole(Role.USER);
        }
    }

    private void navigateToDashboardIfAdminLayout() {
        if (layoutController instanceof AdminLayoutController adminLayoutController) {
            adminLayoutController.goDashboard();
        }
    }

    private void openUserInfo(User user) {
        var result = viewLoader.loadWithController(ViewPaths.USER_INFO, layoutController);
        if (result == null) {
            log.error("Failed to load user-info view");
            return;
        }

        UserInfoController controller = (UserInfoController) result.controller();
        controller.setUser(user);

        Node previousView = currentView;
        controller.setOnBack(() -> layoutController.setContent(previousView));

        layoutController.setContent(result.node());
    }

    private void showSuccess(String text) {
        if (layoutController != null) {
            layoutController.showNoticeSuccess(text);
        }
    }

    private void showError(String text) {
        if (layoutController != null) {
            layoutController.showNoticeError(text);
        }
    }
}