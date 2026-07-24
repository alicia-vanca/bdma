package com.app.common.modules.foldermanager.dtos;

public record StorageVolume(
        int diskNumber, 
        String driveLetter, 
        long freeBytes, 
        String busType, 
        String driveType,
        boolean offline, 
        boolean readOnly, 
        String operationalStatus
) {}
