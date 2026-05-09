package com.app.common.dtos;

import org.springframework.stereotype.Component;

@Component
public class FileListFilterState {
    private FileFilter current = new FileFilter();

    public FileFilter get() {
        return current;
    }

    public void clear() {
        current = new FileFilter();
    }
}