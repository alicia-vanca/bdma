package com.app.common.modules.foldermanager.exceptions;

import java.io.IOException;

/**
 * Thrown when a file cannot be found on any available drive during path
 * resolution.
 * This distinguishes the expected "not found" case from other I/O errors like
 * permission issues, lock failures, or file system errors.
 */
public class FileNotFoundOnAnyDriveException extends IOException {

    public FileNotFoundOnAnyDriveException(String path) {
        super("File not found on any drive: " + path);
    }

    public FileNotFoundOnAnyDriveException(String path, Throwable cause) {
        super("File not found on any drive: " + path, cause);
    }
}
