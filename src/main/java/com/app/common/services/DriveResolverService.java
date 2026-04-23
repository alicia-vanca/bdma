package com.app.common.services;

import org.springframework.stereotype.Service;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DriveResolverService {

    private final Map<String, Path> cache = new ConcurrentHashMap<>();

    public Optional<Path> resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return Optional.empty();

        Path cached = cache.get(relativePath);
        if (cached != null) {
            if (Files.exists(cached)) return Optional.of(cached);
            cache.remove(relativePath);
        }

        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            Path candidate = root.resolve(relativePath);
            if (Files.exists(candidate)) {
                cache.put(relativePath, candidate);
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public void invalidateCache() {
        cache.clear();
    }
}
