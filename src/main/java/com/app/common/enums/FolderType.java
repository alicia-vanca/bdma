package com.app.common.enums;

import com.app.common.i18n.I18n;

public enum FolderType {
    SAVE,
    BACKUP;

    public String getLockedName() {
        return switch (this) {
            case SAVE -> "System Data.{21EC2020-3AEA-1069-A2DD-08002B30309D}";
            case BACKUP -> "System Cache.{645FF040-5081-101B-9F08-00AA002F954E}";
        };
    }

    @Override
    public String toString() {
        return I18n.get("storage.type." + this.name().toLowerCase());
    }
}
