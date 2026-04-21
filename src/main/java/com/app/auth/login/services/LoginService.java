package com.app.auth.login.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.app.common.models.User;
import com.app.common.modules.datasync.DataSyncRunner;
import com.app.common.modules.session.Session;

@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final Session session;
    private final DataSyncRunner syncRunner;

    public LoginService(Session session,
            DataSyncRunner syncRunner) {
        this.session = session;
        this.syncRunner = syncRunner;
    }

    public void onLoginSuccess() {

        User user = session.getUser();
        if (user == null) {
            log.warn("onLoginSuccess called but session is empty.");
            return;
        }

        syncRunner.startSyncWorker();

        log.info("Login [{}] [{}] — sync runner started.",
                user.getUsername(),
                user.getRole());
    }
}
