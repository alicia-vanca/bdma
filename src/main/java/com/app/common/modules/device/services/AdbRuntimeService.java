package com.app.common.modules.device.services;

import com.app.common.definitions.AppDataPaths;
import com.app.common.exceptions.AppException;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Service
public class AdbRuntimeService {

    private static final String ADB_EXE = "adb.exe";
    private static final List<String> ADB_RESOURCE_PATHS = List.of(
            "adb/adb.exe",
            "adb/AdbWinApi.dll",
            "adb/AdbWinUsbApi.dll");

    public String resolveAdbExecutable() {
        Path adbDir = Path.of(AppDataPaths.adbTmpDir());
        Path adbExePath = adbDir.resolve(ADB_EXE);
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        try {
            Files.createDirectories(adbDir);

            for (String resourcePath : ADB_RESOURCE_PATHS) {
                String fileName = Path.of(resourcePath).getFileName().toString();
                Path destPath = adbDir.resolve(fileName);
                if (Files.exists(destPath)) {
                    continue;
                }

                try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new AppException("ADB resource not found: " + resourcePath);
                    }
                    Files.copy(in, destPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new AppException("Failed preparing adb runtime binary", e);
        }

        return adbExePath.toString();
    }
}
