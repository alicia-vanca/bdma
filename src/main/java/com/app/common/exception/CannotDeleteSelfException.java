package com.app.common.exception;

public class CannotDeleteSelfException extends AppException {
    public CannotDeleteSelfException() {
        super("Cannot delete your own account");
    }
}