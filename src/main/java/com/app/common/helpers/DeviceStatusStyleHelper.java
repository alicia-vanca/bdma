package com.app.common.helpers;

import com.app.common.definitions.AppConstants;
import com.app.common.dtos.DeviceSummary;
import com.app.common.modules.i18n.I18n;
import com.app.common.services.DeviceMiniStatus;
import javafx.scene.control.Label;
import javafx.scene.shape.Circle;

public class DeviceStatusStyleHelper {

    private DeviceStatusStyleHelper() {}

    public static void applyStatusStyle(DeviceSummary summary, Circle dot, Label sub) {
        switch (summary.getStatus()) {
            case CONNECTED -> applyConnectedStyle(summary, dot, sub);
            case OFFLINE -> applyOfflineStyle(dot, sub);
            case UNVALIDATED -> applyUnvalidatedStyle(dot, sub);
        }
    }

    private static void applyConnectedStyle(DeviceSummary summary, Circle dot, Label sub) {
        dot.getStyleClass().add("dot-connected");

        DeviceMiniStatus.SyncProgress progress = summary.getSyncProgress();
        DeviceMiniStatus.SyncStatus status = progress.status();

        sub.setText(resolveConnectedText(status, progress));
        sub.getStyleClass().add(resolveConnectedStyle(status));
    }

    private static String resolveConnectedText(DeviceMiniStatus.SyncStatus status,
                                               DeviceMiniStatus.SyncProgress progress) {
        return switch (status) {
            case QUEUED -> I18n.get("dashboard.device.queued");
            case SYNCING -> I18n.get("dashboard.device.syncing") + " " + formatProgress(progress);
            case COMPLETED -> I18n.get(AppConstants.KEY_DEVICE_SYNCED) + " " + formatProgress(progress);
            default -> I18n.get(AppConstants.KEY_DEVICE_CONNECTED);
        };
    }

    private static String resolveConnectedStyle(DeviceMiniStatus.SyncStatus status) {
        return switch (status) {
            case QUEUED -> "device-cell-queued";
            case SYNCING -> "device-cell-syncing";
            default -> "device-cell-connected";
        };
    }

    private static void applyOfflineStyle(Circle dot, Label sub) {
        dot.getStyleClass().add("dot-offline");
        sub.setText(I18n.get("dashboard.device.offline"));
        sub.getStyleClass().add("device-cell-offline");
    }

    private static void applyUnvalidatedStyle(Circle dot, Label sub) {
        dot.getStyleClass().add("dot-unvalidated");
        sub.setText(I18n.get("dashboard.device.unvalidated"));
        sub.getStyleClass().add("device-cell-unvalidated");
    }

    public static String formatProgress(DeviceMiniStatus.SyncProgress progress) {
        return "(" + progress.total() + " : " + progress.passed() + " ✓  " + progress.failed() + " ✗)";
    }

}