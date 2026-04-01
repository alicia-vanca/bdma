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
import javafx.animation.PauseTransition;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
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
    private static final String MESSAGE_ERROR = "message-error";
    private static final String MESSAGE_SUCCESS = "message-success";

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
    private Label message;
    @FXML
    private ComboBox<String> cbRole;
    @FXML
    private VBox root;
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
    private final PauseTransition hideMessageTransition = new PauseTransition(Duration.seconds(5));

    private BaseLayoutController layoutController;
    private Node currentView;

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
        message.setVisible(false);
        message.setManaged(false);
        hideMessageTransition.setOnFinished(e -> {
            message.setVisible(false);
            message.setManaged(false);
        });

        cbRole.getItems().addAll(I18n.get(COMMON_ALL), Role.ADMIN.toString(), Role.USER.toString());
        cbRole.setValue(I18n.get(COMMON_ALL));

        colSTT.setCellValueFactory(c -> new SimpleIntegerProperty(
                currentPageIndex * PAGE_SIZE
                        + table.getItems().indexOf(c.getValue()) + 1));

        colUsername.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsername()));
        colUsername.setCellFactory(col -> new TableCell<>() {

            private final Hyperlink link = new Hyperlink();

            {
                link.setOnAction(e -> {
                    User user = getTableView().getItems().get(getIndex());
                    openUserInfo(user);
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    link.setText(item);
                    setGraphic(link);
                }
            }
        });

        colRole.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getRole().toString()));

        addActionColumn();
        loadData();
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
        String keyword = txtSearch.getText().toLowerCase().trim();
        String role = cbRole.getValue();

        filteredUsers = allUsers.stream()
                .filter(u -> {
                    boolean matchUsername = u.getUsername().toLowerCase().contains(keyword);
                    boolean matchRole = role.equals(I18n.get(COMMON_ALL)) ||
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
                from < to ? filteredUsers.subList(from, to) : List.of()
        ));
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
            boolean isEditingCurrentUser = isEdit
                    && Objects.equals(user.getId(), Session.getCurrentUserId());
            Role originalRole = isEdit ? user.getRole() : null;

            var dialog = DialogHelper.<UserFormController>openWithController(
                    ViewPaths.USER_FORM,
                    user == null ? I18n.get("user.add.title") : I18n.get("user.edit.title"));

            dialog.controller().setUser(user);
            dialog.controller().setOnSuccess(() -> {
                String key = user == null ? "user.add.success" : "user.edit.success";
                showSuccess(I18n.get(key));

                boolean downgradedSelfFromAdmin = isEditingCurrentUser
                        && originalRole == Role.ADMIN
                        && user.getRole() == Role.USER;
                if (downgradedSelfFromAdmin) {
                    User sessionUser = Session.getUser();
                    if (sessionUser != null) {
                        sessionUser.setRole(Role.USER);
                    }

                    if (layoutController instanceof AdminLayoutController adminLayoutController) {
                        adminLayoutController.goDashboard();
                    }
                }
            });

            dialog.stage().setMinWidth(460);
            dialog.stage().setMinHeight(360);
            dialog.stage().showAndWait();

            loadData();

        } catch (Exception e) {
            log.error("Failed to open user form", e);
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
        message.setText(text);
        message.getStyleClass().removeAll(MESSAGE_ERROR, MESSAGE_SUCCESS);
        message.getStyleClass().add(MESSAGE_SUCCESS);
        message.setVisible(true);
        message.setManaged(true);
        scheduleHide();
    }

    private void showError(String text) {
        message.setText(text);
        message.getStyleClass().removeAll(MESSAGE_ERROR, MESSAGE_SUCCESS);
        message.getStyleClass().add(MESSAGE_ERROR);
        message.setVisible(true);
        message.setManaged(true);
        scheduleHide();
    }

    private void scheduleHide() {
        hideMessageTransition.stop();
        hideMessageTransition.playFromStart();
    }
}