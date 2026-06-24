package com.app.common.dtos;

import com.app.common.models.FileRecord;

public record FileView(
        Long fileId,
        Long userId,
        Long deviceId,
        String createDate,
        String name,
        String syncedPath,
        String backedUpPath,
        String status,
        Long fileSize,
        String type,
        String syncedAt,
        String backedUpAt,
        String username,
        String deviceName) {
    public static FileView from(FileRecord f, String username, String deviceName) {
        return new FileView(
                f.getId(),
                f.getUserId(),
                f.getDeviceId(),
                f.getCreateDate(),
                f.getName(),
                f.getSyncedPath(),
                f.getBackedUpPath(),
                f.getStatus(),
                f.getFileSize(),
                f.getType(),
                f.getSyncedAt(),
                f.getBackedUpAt(),
                username,
                deviceName);
    }
}
