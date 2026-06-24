package com.app.common.modules.device.helpers;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.enums.DeviceStatus;
import com.app.common.modules.device.dtos.DeviceSpec;
import com.app.common.modules.device.dtos.DeviceSummary;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.device.services.DeviceMiniStatus;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

public class DeviceCardStyleHelper {

    private static final String CSS_MAX_STORAGE = "max-storage";

    private DeviceCardStyleHelper() {
    }

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
        if (progress == null) {
            return "";
        }

        return "(" + progress.total() + " : " + progress.passed() + " ✓  " + progress.failed() + " ✗)";
    }

    public static VBox buildAdminDeviceCard(DeviceSummary summary) {
        HBox header = buildAdminCardHeader(summary);

        VBox card;
        if (summary.getDeviceSpec() != null) {
            VBox specLines = buildAdminCardSpecLines(summary);
            card = new VBox(6, header, specLines);
        } else {
            card = new VBox(6, header);
        }
        card.getStyleClass().add("device-card");
        applyCardStatusStyle(summary, card);
        card.setMaxWidth(Double.MAX_VALUE);

        return card;
    }

    private static HBox buildAdminCardHeader(DeviceSummary summary) {
        HBox titleRow = buildAdminTitleRow(summary);
        HBox statusRow = buildAdminStatusRow(summary);

        VBox nameInfoBox = new VBox(2, titleRow, statusRow);
        HBox.setHgrow(nameInfoBox, Priority.ALWAYS);

        HBox header = new HBox(nameInfoBox);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMaxWidth(Double.MAX_VALUE);
        return header;
    }

    private static HBox buildAdminTitleRow(DeviceSummary summary) {
        Label name = new Label(summary.getDeviceName());
        name.getStyleClass().add("device-cell-name");

        Label cameraIdLabel = new Label(summary.getCameraId());
        cameraIdLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        cameraIdLabel.getStyleClass().add("device-cell-camera-id");

        Label separator = new Label("·");
        separator.getStyleClass().add("device-cell-separator");

        StackPane nameBox = new StackPane(name);
        nameBox.setAlignment(Pos.CENTER_LEFT);
        nameBox.setMaxWidth(110);

        FontIcon cameraIcon = new FontIcon(FontAwesomeSolid.CAMERA);
        cameraIcon.getStyleClass().add("camera-icon");
        if (summary.getStatus() == DeviceStatus.OFFLINE) {
            cameraIcon.getStyleClass().add("offline");
        }

        HBox titleRow = new HBox(6, fixedIconBox(cameraIcon), nameBox, separator, cameraIdLabel);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        return titleRow;
    }

    private static HBox buildAdminStatusRow(DeviceSummary summary) {
        Circle dot = new Circle(6);
        Label sub = new Label();
        applyStatusStyle(summary, dot, sub);

        HBox statusRow = new HBox(6, fixedIconBox(dot), sub);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        return statusRow;
    }

    private static void applyCardStatusStyle(DeviceSummary summary, VBox card) {
        if (summary.getStatus() == DeviceStatus.CONNECTED) {
            card.getStyleClass().add("connected");
        } else if (summary.getStatus() == DeviceStatus.UNVALIDATED) {
            card.getStyleClass().add("unvalidated");
        }
    }

    public static GridPane buildSpecificationRow(DeviceSummary summary) {
        DeviceSpec info = summary.getDeviceSpec();

        FontIcon batteryIcon = getBatteryIcon(
                DeviceSpec.parsePercent(info != null ? info.getBatteryLevel() : null));
        FontIcon storageIcon = new FontIcon(FontAwesomeSolid.SD_CARD);

        if (summary.getStatus() == DeviceStatus.OFFLINE) {
            batteryIcon.getStyleClass().add("battery-icon-offline");
            storageIcon.getStyleClass().add("storage-icon-offline");
        } else {
            batteryIcon.getStyleClass().add("battery-icon");
            if (info != null && info.isLowBattery()) {
                batteryIcon.getStyleClass().add("low");
            }
            storageIcon.getStyleClass().add("storage-icon");
            if (info != null && info.isMaxUsage()) {
                storageIcon.getStyleClass().add(CSS_MAX_STORAGE);
            }
        }

        VBox batteryCell = buildSpecCell(batteryIcon, I18n.get("device.spec.battery.label"),
                batteryValueLabel(info), batterySubText(info));

        VBox storageCell = buildSpecCell(storageIcon, I18n.get("device.spec.storage.label"),
                storageValueLabel(info), storageSubText(info));

        batteryCell.getStyleClass().add("battery-cell");

        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        half.setFillWidth(true);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("device-spec-row");
        grid.getColumnConstraints().addAll(half, half);
        grid.add(batteryCell, 0, 0);
        grid.add(storageCell, 1, 0);

        // Let each cell fill its column.
        GridPane.setFillWidth(batteryCell, true);
        GridPane.setFillWidth(storageCell, true);
        batteryCell.setMaxWidth(Double.MAX_VALUE);
        storageCell.setMaxWidth(Double.MAX_VALUE);

        return grid;
    }

    private static FontIcon getBatteryIcon(Integer batteryLevel) {
        if (batteryLevel == null) {
            return new FontIcon(FontAwesomeSolid.BATTERY_EMPTY);
        }

        if (batteryLevel <= 20) {
            return new FontIcon(FontAwesomeSolid.BATTERY_QUARTER);
        }

        if (batteryLevel <= 50) {
            return new FontIcon(FontAwesomeSolid.BATTERY_HALF);
        }

        return new FontIcon(FontAwesomeSolid.BATTERY_FULL);
    }

    public static VBox buildSpecCell(Node icon, String headerText, Label valueLabel, String sub) {
        Label headerLabel = new Label(headerText);
        headerLabel.getStyleClass().add("device-spec-header");
        HBox headerBox = new HBox(4, icon, headerLabel);
        headerBox.setAlignment(Pos.CENTER_LEFT);

        Label subLabel = new Label(sub);
        subLabel.getStyleClass().add("device-spec-sub");

        VBox cell = new VBox(2, headerBox, valueLabel, subLabel);
        cell.getStyleClass().add("device-spec-cell");
        return cell;
    }

    public static Label batteryValueLabel(DeviceSpec info) {
        Label valueLabel = new Label(info == null ? I18n.get("device.spec.unknown") : info.batteryValueText());
        if (info == null) {
            valueLabel.getStyleClass().add("device-spec-value-unknown");
        } else {
            valueLabel.getStyleClass().addAll("device-spec-value-battery");
            if (info.isLowBattery()) {
                valueLabel.getStyleClass().add("low");
            }
        }
        return valueLabel;
    }

    public static String batterySubText(DeviceSpec info) {
        return info == null ? "" : info.batterySubText();
    }

    public static Label storageValueLabel(DeviceSpec info) {
        Label valueLabel = new Label(info == null ? I18n.get("device.spec.unknown") : info.storageValueText());
        if (info == null) {
            valueLabel.getStyleClass().add("device-spec-value-unknown");
        } else {
            valueLabel.getStyleClass().addAll("device-spec-value-storage");
            if (info.isMaxUsage()) {
                valueLabel.getStyleClass().add(CSS_MAX_STORAGE);
            }
        }
        return valueLabel;
    }

    public static String storageSubText(DeviceSpec info) {
        return info == null ? "" : info.storageSubText();
    }

    public static VBox buildAdminCardSpecLines(DeviceSummary summary) {
        DeviceSpec info = summary.getDeviceSpec();

        FontIcon batteryIcon = getBatteryIcon(
                DeviceSpec.parsePercent(info != null ? info.getBatteryLevel() : null));
        batteryIcon.getStyleClass().add("battery-icon");
        if (info != null && info.isLowBattery()) {
            batteryIcon.getStyleClass().add("low");
        }

        FontIcon storageIcon = new FontIcon(FontAwesomeSolid.SD_CARD);
        storageIcon.getStyleClass().add("storage-icon");
        if (info != null && info.isMaxUsage()) {
            storageIcon.getStyleClass().add(CSS_MAX_STORAGE);
        }

        HBox batteryLine = buildSpecLine(batteryIcon, batteryValueLabel(info), batterySubText(info));

        HBox storageLine = buildSpecLine(storageIcon, storageValueLabel(info), storageSubText(info));

        return new VBox(3, batteryLine, storageLine);
    }

    private static HBox buildSpecLine(FontIcon icon, Label valueLabel, String subText) {
        Label subLabel = new Label(subText);
        subLabel.getStyleClass().add("device-spec-sub");

        HBox line = new HBox(6, fixedIconBox(icon), valueLabel, subLabel);
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    private static StackPane fixedIconBox(Node icon) {
        StackPane box = new StackPane(icon);
        box.setAlignment(Pos.CENTER);
        box.setMinWidth(15);
        box.setPrefWidth(15);
        box.setMaxWidth(15);
        return box;
    }
}
