package com.app.common.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRuleSet {
    private Long id;
    private String name;
    private boolean active;
    private String createdAt;
}
