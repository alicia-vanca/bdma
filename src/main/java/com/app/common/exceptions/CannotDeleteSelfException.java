package com.app.common.exceptions;

public class CannotDeleteSelfException extends AppException {
    public CannotDeleteSelfException() {
        super("Cannot delete your own account");
    }
}