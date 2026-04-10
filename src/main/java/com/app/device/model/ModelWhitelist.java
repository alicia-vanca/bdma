package com.app.device.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelWhitelist {
    private String id;
    private String modelName;
    private boolean active;
    private String createdAt;
    private List<ModelWhitelistRule> rules;
}
