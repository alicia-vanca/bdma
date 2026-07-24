package com.app.common.modules.device.services;

import com.app.common.exceptions.AppException;
import com.app.common.helpers.NativeRuntimePathResolver;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class AdbRuntimeService {

    private static final String ADB_EXE = "adb.exe";

    public String resolveAdbExecutable() {
        try {
            Path adbDirectory = NativeRuntimePathResolver.resolve(
                    AdbRuntimeService.class, "adb", "ADB", AdbRuntimeService::isCompleteAdbDirectory);
            return adbDirectory.resolve(ADB_EXE).toString();
        } catch (IllegalStateException e) {
            throw new AppException("Bundled ADB runtime is unavailable", e);
        }
    }

    private static boolean isCompleteAdbDirectory(Path directory) {
        return Files.isRegularFile(directory.resolve(ADB_EXE))
                && Files.isRegularFile(directory.resolve("AdbWinApi.dll"))
                && Files.isRegularFile(directory.resolve("AdbWinUsbApi.dll"));
    }
}
