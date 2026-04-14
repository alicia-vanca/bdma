package com.app.admin.controller;

import com.app.common.i18n.I18n;
import com.app.device.model.DeviceSummary;
import com.app.sync.tracker.SyncProgressTracker;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import lombok.Setter;

import java.util.function.Consumer;

public class DeviceListCell extends ListCell<DeviceSummary> {

    private static final String KEY_DEVICE_CONNECTED = "dashboard.device.connected";

    private final Consumer<DeviceSummary> onValidate;
    @Setter
    private Consumer<DeviceSummary> onSync;

    public DeviceListCell(Consumer<DeviceSummary> onValidate) {
        this.onValidate = onValidate;
    }

    @Override
    protected void updateItem(DeviceSummary summary, boolean empty) {
        super.updateItem(summary, empty);

        if (empty || summary == null) {
            clearCell();
            return;
        }

        Circle dot = new Circle(5);
        Label name = new Label(summary.getDisplayName());
        Label sub = new Label();

        applyStatusStyle(summary, dot, sub);

        name.getStyleClass().add("device-cell-name");

        VBox text = new VBox(2, name, sub);
        HBox row = new HBox(8, dot, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("device-cell-row");

        setGraphic(row);
        setText(null);

        setupClickHandler(summary);
    }

    private void clearCell() {
        setText(null);
        setGraphic(null);
        setOnMouseClicked(null);
    }

    private void applyStatusStyle(DeviceSummary summary, Circle dot, Label sub) {
        switch (summary.getStatus()) {
            case CONNECTED -> applyConnectedStyle(summary, dot, sub);
            case OFFLINE -> applyOfflineStyle(dot, sub);
            case UNVALIDATED -> applyUnvalidatedStyle(dot, sub);
        }
    }

    private void applyConnectedStyle(DeviceSummary summary, Circle dot, Label sub) {
        dot.getStyleClass().add("dot-connected");

        SyncProgressTracker.SyncProgress progress = summary.getSyncProgress();
        SyncProgressTracker.SyncStatus status = progress.status();

        sub.setText(resolveConnectedText(status, progress));
        sub.getStyleClass().add(resolveConnectedStyle(status));
    }

    private String resolveConnectedText(SyncProgressTracker.SyncStatus status,
                                        SyncProgressTracker.SyncProgress progress) {

        return switch (status) {
            case QUEUED -> I18n.get("dashboard.device.queued");
            case SYNCING -> I18n.get("dashboard.device.syncing") + " " + formatProgress(progress);
            case COMPLETED -> progress.total() > 0
                    ? I18n.get(KEY_DEVICE_CONNECTED) + " " + formatProgress(progress)
                    : I18n.get(KEY_DEVICE_CONNECTED);
            default -> I18n.get(KEY_DEVICE_CONNECTED);
        };
    }

    private String resolveConnectedStyle(SyncProgressTracker.SyncStatus status) {
        return switch (status) {
            case QUEUED -> "device-cell-queued";
            case SYNCING -> "device-cell-syncing";
            case COMPLETED -> "device-cell-connected";
            default -> "device-cell-connected";
        };
    }

    private void applyOfflineStyle(Circle dot, Label sub) {
        dot.getStyleClass().add("dot-offline");
        sub.setText(I18n.get("dashboard.device.offline"));
        sub.getStyleClass().add("device-cell-offline");
    }

    private void applyUnvalidatedStyle(Circle dot, Label sub) {
        dot.getStyleClass().add("dot-unvalidated");
        sub.setText(I18n.get("dashboard.device.unvalidated"));
        sub.getStyleClass().add("device-cell-unvalidated");
    }

    private void setupClickHandler(DeviceSummary summary) {
        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                if (summary.isUnvalidated() && onValidate != null) {
                    onValidate.accept(summary);
                } else if (summary.isConnected() && onSync != null) {
                    onSync.accept(summary);
                }
            }
        });
    }

    private String formatProgress(SyncProgressTracker.SyncProgress p) {
        return "(" + p.total() + " : " + p.passed() + " ✓  " + p.failed() + " ✗)";
    }

    public DeviceSummary getDevice() {
        return getItem();
    }
}