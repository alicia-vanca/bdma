package com.app.common.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BackupRestoreFailure {
    private String fileName;
    private String operation;
    private String errorMessage;
    private int retryCount;
    private String lastAttemptedAt;
    private String status;
}
