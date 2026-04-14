package com.app.common.models;

import com.app.common.definitions.enums.Language;
import com.app.common.definitions.enums.Theme;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserSetting {
    private Long id;
    private Long userId;
    private Theme theme;
    private Language language;
}
