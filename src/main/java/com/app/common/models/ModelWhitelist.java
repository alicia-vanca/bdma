package com.app.common.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelWhitelist {
    private Long id;
    private String modelName;
    private boolean active;
    private String createdAt;
    private List<ModelWhitelistRule> rules;
}
