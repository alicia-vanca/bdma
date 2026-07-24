package com.app.common.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileBookmark {
    private Long id;
    private Long userId;
    private Long fileId;
    private String createdAt;
    private String updatedAt;
    private boolean bookmarked;
}
