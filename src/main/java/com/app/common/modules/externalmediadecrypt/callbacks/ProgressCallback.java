package com.app.common.modules.externalmediadecrypt.callbacks;
@FunctionalInterface
public interface ProgressCallback {
    void update(long processed, long total);
}
