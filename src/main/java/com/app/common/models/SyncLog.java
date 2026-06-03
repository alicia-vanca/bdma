package com.app.common.models;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SyncLog {

    private Long id;
    private String tableName;
    private String operation;
    private String recordId;
    private Integer synced;
    private String createdAt;
}