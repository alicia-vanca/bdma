package com.app.common.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceActivationHistory {
    private Long id;
    private Long deviceId;
    private boolean active;
    private Long changedBy;
    private String changedAt;
}
