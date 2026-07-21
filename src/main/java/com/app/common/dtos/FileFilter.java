package com.app.common.dtos;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class FileFilter {
    private String cameraId;
    private Long userId;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private String type;
    private Boolean bookmarkedOnly;
    private Long bookmarkUserId;
}
