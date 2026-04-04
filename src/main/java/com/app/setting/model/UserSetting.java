package com.app.setting.model;

import com.app.common.enums.Language;
import com.app.common.enums.Theme;
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