package com.app.common.services;

import com.app.common.definitions.AppDataPaths;
import com.app.common.exceptions.AppException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdbRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AdbRuntimeService.class);

    private static final String ADB_EXE = "adb.exe";

    private static final List<String> ADB_RESOURCE_CANDIDATES = List.of(
            "adb/adb.exe",
            "bin/adb.exe",
            ADB_EXE);

    private static final List<String> ADB_OPTIONAL_DEPENDENCY_RESOURCES = List.of(
            "adb/AdbWinApi.dll",
            "adb/AdbWinUsbApi.dll",
            "bin/AdbWinApi.dll",
            "bin/AdbWinUsbApi.dll");

    public String resolveAdbExecutable() {
        Path adbDir = Path.of(AppDataPaths.adbTmpDir());
        Path adbExePath = adbDir.resolve(ADB_EXE);

        if (Files.exists(adbExePath)) {
            return adbExePath.toString();
        }

        try {
            Files.createDirectories(adbDir);
            boolean copied = copyFirstAvailable(ADB_RESOURCE_CANDIDATES, adbExePath);
            if (copied) {
                copyOptionalDependencies(adbDir);
                return adbExePath.toString();
            }
        } catch (IOException e) {
            log.warn("Failed preparing adb runtime binary: {}", e.getMessage());
        }

        throw new AppException("ADB binary not found. Put adb.exe in resources/adb");
    }

    private boolean copyFirstAvailable(List<String> resourceCandidates, Path destPath) throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        for (String resourcePath : resourceCandidates) {
            try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    continue;
                }
                Files.copy(in, destPath, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        }
        return false;
    }

    private void copyOptionalDependencies(Path adbDir) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        List<String> copiedFileNames = new ArrayList<>();
        for (String resourcePath : ADB_OPTIONAL_DEPENDENCY_RESOURCES) {
            String fileName = Path.of(resourcePath).getFileName().toString();
            Path dest = adbDir.resolve(fileName);
            if (!Files.exists(dest)) {
                try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
                    if (in != null) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                        copiedFileNames.add(fileName);
                    }
                } catch (IOException e) {
                    log.debug("Skipping optional adb dependency {}: {}", resourcePath, e.getMessage());
                }
            }
        }

        if (!copiedFileNames.isEmpty()) {
            log.info("Prepared adb dependencies: {}", copiedFileNames);
        }
    }
}
