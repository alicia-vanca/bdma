package com.app.common.models;

import com.app.common.definitions.enums.ServiceRuleAction;
import com.app.common.definitions.enums.ServiceRulePhase;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRule {
    private Long id;
    private Long ruleSetId;
    private ServiceRulePhase phase;
    private int executeOrder;
    private ServiceRuleAction action;
    private String serviceName;
    private boolean active;
}
