package com.app.common.dtos;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class FileFilter {
    private String hardwareId;
    private Long userId;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private String type;
}
