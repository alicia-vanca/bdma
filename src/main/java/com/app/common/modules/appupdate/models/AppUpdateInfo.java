package com.app.common.modules.appupdate.models;

public record AppUpdateInfo(String latestVersion, String downloadUrl, String fallbackDownloadUrl, boolean hasUpdate) {
}