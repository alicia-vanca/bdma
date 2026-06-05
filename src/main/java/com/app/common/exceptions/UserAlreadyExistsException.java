package com.app.common.exceptions;

public class UserAlreadyExistsException extends AppException {
    public UserAlreadyExistsException(String username) {
        super("Username already exists: " + username);
    }
}
