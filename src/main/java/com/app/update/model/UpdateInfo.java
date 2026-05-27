package com.app.update.model;

public record UpdateInfo(String latestVersion, String downloadUrl, boolean hasUpdate) {
}