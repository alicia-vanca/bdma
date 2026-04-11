package com.app.sync.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Files {
    private Long id;
    private Long userId;
    private Long deviceId;
    private String createDate;
    private String name;
    private String path;
    private long fileSize;
    private String type;
    private String status;
}