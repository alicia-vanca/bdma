package com.app.common.modules.databaserecovery.services;

import com.app.common.definitions.AppConstants;
import com.app.common.definitions.AppDataPaths;
import com.app.common.exceptions.AppException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Component
public class DatabaseRecoveryService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseRecoveryService.class);
    private static final String MIGRATION_RESOURCE_DIR = "db/migration";
    private static final String BACKUP_EXTENSION = ".bak";
    private static final int BACKUP_HASH_LENGTH = 16;

    private static String cachedMigrationHash;

    /**
     * Restores the source database from the runtime backup location after a
     * connection-loss failure is detected.
     */
    public void restoreSourceFromBackup() {
        try {
            restoreDB();
        } catch (Exception ex) {
            logger.error("Database restore failed", ex);
        }
    }

    /**
     * Copies the application database to the backup location during shutdown.
     */
    public void backupSourceToBackup() {
        try {
            backupDB();
        } catch (Exception ex) {
            logger.error("Database backup failed", ex);
        }
    }

    /**
     * Restores the source database before runtime initialization, Spring Boot, and
     * Flyway open it, but only when the source file is missing or empty.
     *
     * @return source database path used by the application DataSource
     */
    @SuppressWarnings("UnusedReturnValue")
    public Path prepareDatabase() {
        try {
            restoreDB();
            return sourceDbPath();
        } catch (IOException e) {
            throw new AppException("Failed to prepare database", e);
        }
    }

    /**
     * Copies the source database into a migration-hashed backup first, then
     * replaces the real backup only after a non-empty file is written. The method
     * is synchronized to serialize backup and restore operations from threads
     * inside the current app process.
     */
    private synchronized void backupDB() throws IOException {
        Path sourceDb = sourceDbPath();
        Path backupDb = backupDbPath();
        Path temporaryBackupDb = temporaryDbPath(backupDb);
        ensureParentFolders(sourceDb, backupDb);
        if (!Files.exists(sourceDb) || Files.size(sourceDb) == 0) {
            return;
        }

        Files.deleteIfExists(temporaryBackupDb);
        Files.copy(sourceDb, temporaryBackupDb, StandardCopyOption.REPLACE_EXISTING);
        Files.move(temporaryBackupDb, backupDb, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Restores only the backup created for the current bundled SQL migrations. If
     * migrations change, missing source databases are left for Flyway to initialize
     * instead of restoring an incompatible backup. The method is synchronized to
     * serialize backup and restore operations from threads inside the current app
     * process.
     */
    private synchronized void restoreDB() throws IOException {
        Path sourceDb = sourceDbPath();
        Path backupDb = backupDbPath();
        Path temporarySourceDb = temporaryDbPath(sourceDb);
        ensureParentFolders(sourceDb, backupDb);
        if (!shouldRestoreSource(sourceDb) || !Files.exists(backupDb) || Files.size(backupDb) == 0) {
            return;
        }

        Files.deleteIfExists(temporarySourceDb);
        Files.copy(backupDb, temporarySourceDb, StandardCopyOption.REPLACE_EXISTING);
        Files.move(temporarySourceDb, sourceDb, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Database restored from backup: {}", backupDb.getFileName());
    }

    private boolean shouldRestoreSource(Path sourceDb) throws IOException {
        return !Files.exists(sourceDb) || Files.size(sourceDb) == 0;
    }

    private void ensureParentFolders(Path sourceDb, Path backupDb) throws IOException {
        Files.createDirectories(sourceDb.getParent());
        Files.createDirectories(backupDb.getParent());
    }

    private Path sourceDbPath() {
        return AppDataPaths.dataFile().toPath();
    }

    private Path backupDbPath() throws IOException {
        return AppDataPaths.dataBackupDir().resolve("data." + migrationHash() + BACKUP_EXTENSION);
    }

    private synchronized String migrationHash() throws IOException {
        if (cachedMigrationHash == null) {
            cachedMigrationHash = calculateMigrationHash();
        }
        return cachedMigrationHash;
    }

    private String calculateMigrationHash() throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<MigrationResource> migrations = migrationResources();
            if (migrations.isEmpty()) {
                throw new IOException("No SQL migrations found in " + MIGRATION_RESOURCE_DIR);
            }

            for (MigrationResource migration : migrations) {
                digest.update(migration.name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(migration.content());
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, BACKUP_HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    private List<MigrationResource> migrationResources() throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(
                Thread.currentThread().getContextClassLoader());
        Resource[] resources = resolver.getResources(
                ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + MIGRATION_RESOURCE_DIR + "/*.sql");

        return Arrays.stream(resources)
                .map(this::migrationResource)
                .sorted(Comparator.comparing(MigrationResource::name))
                .toList();
    }

    private MigrationResource migrationResource(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            String filename = resource.getFilename();
            if (filename == null || filename.isBlank()) {
                throw new IOException("Migration resource filename is missing: " + resource.getDescription());
            }
            return new MigrationResource(filename, input.readAllBytes());
        } catch (IOException e) {
            throw new AppException("Failed to read migration resource: " + resource.getDescription(), e);
        }
    }

    private Path temporaryDbPath(Path db) {
        return db.resolveSibling(db.getFileName() + AppConstants.TMP_EXTENSION);
    }

    private record MigrationResource(String name, byte[] content) {

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof MigrationResource(String otherName, byte[] otherContent))) {
                return false;
            }
            return name.equals(otherName) && Arrays.equals(content, otherContent);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + Arrays.hashCode(content);
        }

        @Override
        public @NotNull String toString() {
            return "MigrationResource[name=" + name + ", contentLength=" + content.length + "]";
        }
    }
}
