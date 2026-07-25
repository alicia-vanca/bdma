package com.app.common.helpers;

import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class NativeRuntimePathResolver {

    private NativeRuntimePathResolver() {
    }

    public static Path resolve(Class<?> anchorClass, String directoryName, String runtimeName,
            Predicate<Path> isComplete) {
        return find(candidates(anchorClass, directoryName), runtimeName, isComplete);
    }

    public static Path find(List<Path> candidates, String runtimeName, Predicate<Path> isComplete) {
        for (Path candidate : candidates) {
            Path directory = candidate.toAbsolutePath().normalize();
            if (isComplete.test(directory)) {
                return directory;
            }
        }
        throw new IllegalStateException("Bundled " + runtimeName + " runtime not found. Checked: " + candidates);
    }

    static List<Path> candidates(Class<?> anchorClass, String directoryName) {
        List<Path> candidates = new ArrayList<>();
        Path applicationDirectory = resolveApplicationDirectory(anchorClass);
        if (applicationDirectory != null) {
            candidates.add(applicationDirectory.resolve(directoryName));
        }

        Path workingDirectory = Path.of(System.getProperty("user.dir", "."));
        candidates.add(workingDirectory.resolve("vendor").resolve(directoryName));
        return List.copyOf(candidates);
    }

    private static Path resolveApplicationDirectory(Class<?> anchorClass) {
        try {
            Path codeLocation = Path.of(anchorClass.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
        } catch (NullPointerException | SecurityException | URISyntaxException
                | IllegalArgumentException | FileSystemNotFoundException e) {
            return null;
        }
    }
}
