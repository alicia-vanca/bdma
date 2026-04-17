package com.app.common.dtos;

import com.app.common.models.File;

public record FileView(
        Long fileId,
        Long userId,
        Long deviceId,
        String createDate,
        String name,
        String path,
        String status,
        Long fileSize,
        String type,
        String createdAt,
        String username,
        String deviceName
) {
    public static FileView from(File f, String username, String deviceName) {
        return new FileView(
                f.getId(),
                f.getUserId(),
                f.getDeviceId(),
                f.getCreateDate(),
                f.getName(),
                f.getPath(),
                f.getStatus(),
                f.getFileSize(),
                f.getType(),
                f.getCreatedAt(),
                username,
                deviceName
        );
    }
}
