package com.app.common.models;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserActivationHistory {
    private Long id;
    private Long userId;
    private boolean isActive;
    private Long changedBy;
    private LocalDateTime changedAt;
}
