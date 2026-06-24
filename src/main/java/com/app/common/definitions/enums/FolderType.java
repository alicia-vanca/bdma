package com.app.common.definitions.enums;

import com.app.common.definitions.AppConstants;
import com.app.common.modules.i18n.I18n;

public enum FolderType {
    SYNC,
    BACKUP,
    EXPORT,
    DECRYPT;

    public String toLocalizedString() {
        return I18n.get("storage.type." + this.name().toLowerCase());
    }

    public String getPhysicalFolderName() {
        return switch (this) {
            case SYNC -> AppConstants.SYNC_FOLDER_NAME;
            case BACKUP -> AppConstants.BACKUP_FOLDER_NAME;
            case EXPORT -> AppConstants.EXPORT_FOLDER_NAME;
            case DECRYPT -> "decrypt";
        };
    }
}
