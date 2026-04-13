package com.app.common.utils;

import org.springframework.security.crypto.bcrypt.BCrypt;

public class SecurityUtil {

    private SecurityUtil() {
    }

    public static String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }

    public static boolean verify(String raw, String hashed) {
        return BCrypt.checkpw(raw, hashed);
    }
}
