package com.app.common.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileRecord {
    private Long id;
    private Long userId;
    private Long deviceId;
    private String createDate;
    private String name;
    private String syncedPath;
    private long fileSize;
    private String type;
    private String status;
    private String syncedAt;
    private String backedUpPath;
    private String backedUpAt;
}
