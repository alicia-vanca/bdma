package com.app.common.modules.media.helpers;

import com.app.common.dtos.FileView;
import com.app.common.modules.i18n.I18n;
import com.app.common.modules.media.dtos.GpsCoordinate;

import javafx.scene.control.Label;

public final class MediaDetailsPanelHelper {

  private static final String EMPTY_VALUE = "—";

  private final Label detailName;
  private final Label detailType;
  private final Label detailSize;
  private final Label detailDate;
  private final Label detailDevice;
  private final Label detailUser;
  private final Label detailStatus;
  private final Label detailDimension;
  private final Label locationGps;
  private final Label locationDate;
  private final Label locationDevice;

  public MediaDetailsPanelHelper(Dependencies dependencies) {
    this.detailName = dependencies.detailName();
    this.detailType = dependencies.detailType();
    this.detailSize = dependencies.detailSize();
    this.detailDate = dependencies.detailDate();
    this.detailDevice = dependencies.detailDevice();
    this.detailUser = dependencies.detailUser();
    this.detailStatus = dependencies.detailStatus();
    this.detailDimension = dependencies.detailDimension();
    this.locationGps = dependencies.locationGps();
    this.locationDate = dependencies.locationDate();
    this.locationDevice = dependencies.locationDevice();
  }

  public record Dependencies(Label detailName, Label detailType, Label detailSize, Label detailDate,
      Label detailDevice, Label detailUser, Label detailStatus,
      Label detailDimension, Label locationGps, Label locationDate,
      Label locationDevice) {
  }

  public void clear() {
    detailName.setText(EMPTY_VALUE);
    detailType.setText(EMPTY_VALUE);
    detailSize.setText(EMPTY_VALUE);
    detailDate.setText(EMPTY_VALUE);
    detailDevice.setText(EMPTY_VALUE);
    detailUser.setText(EMPTY_VALUE);
    detailStatus.setText(EMPTY_VALUE);
    detailDimension.setText(EMPTY_VALUE);
    clearLocation();
  }

  public void clearLocation() {
    locationGps.setText(EMPTY_VALUE);
    locationDate.setText(EMPTY_VALUE);
    locationDevice.setText(EMPTY_VALUE);
  }

  public void updateDetails(FileView file) {
    detailName.setText(file.name() != null ? file.name() : EMPTY_VALUE);
    detailType.setText(formatType(file.type()));
    detailSize.setText(formatSize(file.fileSize()));
    detailDate.setText(file.createDate() != null ? file.createDate() : EMPTY_VALUE);
    detailDevice.setText(file.deviceName() != null ? file.deviceName() : EMPTY_VALUE);
    detailUser.setText(file.username() != null ? file.username() : EMPTY_VALUE);
    detailStatus.setText(formatStatus(file.status()));
  }

  public void setDimension(String value) {
    detailDimension.setText(value);
  }

  public void updateLocation(FileView file, GpsCoordinate gps) {
    locationDate.setText(file.createDate() != null ? file.createDate() : EMPTY_VALUE);
    locationDevice.setText(file.deviceName() != null ? file.deviceName() : EMPTY_VALUE);
    setCurrentGps(gps);
  }

  public void setCurrentGps(GpsCoordinate gps) {
    locationGps.setText(gps != null ? gps.toString() : EMPTY_VALUE);
  }

  public static String formatSize(long bytes) {
    if (bytes < 0) {
      return "-";
    }
    if (bytes < 1024) {
      return bytes + " B";
    }
    if (bytes < 1024 * 1024) {
      return (bytes / 1024) + " KB";
    }
    return String.format("%.1f MB", bytes / (1024.0 * 1024));
  }

  private String formatStatus(String status) {
    if (status == null || status.isEmpty()) {
      return EMPTY_VALUE;
    }
    return I18n.get("file.status." + status);
  }

  private String formatType(String type) {
    if (type == null || type.isEmpty()) {
      return EMPTY_VALUE;
    }
    return I18n.get("file.type." + type);
  }
}
