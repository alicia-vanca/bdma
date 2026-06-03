package com.app.guest.services;

import com.app.common.definitions.enums.NavigationTarget;
import org.springframework.stereotype.Component;

@Component
public class NavigationIntentService {
    private NavigationTarget pendingTarget;

    public void setPendingTarget(NavigationTarget target) {
        this.pendingTarget = target;
    }

    public NavigationTarget consumePendingTarget() {
        NavigationTarget result = pendingTarget;
        pendingTarget = null;
        return result;
    }
}
