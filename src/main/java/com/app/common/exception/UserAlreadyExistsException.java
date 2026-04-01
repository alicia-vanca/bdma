package com.app.common.exception;

public class UserAlreadyExistsException extends AppException {
    public UserAlreadyExistsException(String username) {
        super("Username already exists: " + username);
    }
}
