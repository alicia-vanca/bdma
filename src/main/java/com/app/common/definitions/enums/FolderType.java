package com.app.common.definitions.enums;

import com.app.common.modules.i18n.I18n;

public enum FolderType {
    SAVE,
    EXPORT,
    BACKUP;

    public String toLocalizedString() {
        return I18n.get("storage.type." + this.name().toLowerCase());
    }
}
