package com.app.common.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelWhitelistRule {
    private Long id;
    private String whitelistId;
    private String propKey;
    private String expectedValue;
}
