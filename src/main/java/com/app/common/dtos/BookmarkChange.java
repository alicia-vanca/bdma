package com.app.common.dtos;

public record BookmarkChange(Long userId, Long fileId, boolean bookmarked, String updatedAt) {
}
