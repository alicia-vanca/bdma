package com.app.auth.login.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.models.User;
import com.app.common.modules.session.Session;

@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final Session session;

    public LoginService(Session session) {
        this.session = session;
    }

    public void onLoginSuccess() {

        User user = session.getUser();
        if (user == null) {
            log.warn("onLoginSuccess called but session is empty.");
            return;
        }

        log.info("Login init [{}] [{}] finished.",
                user.getUsername(),
                user.getRole());
    }
}
