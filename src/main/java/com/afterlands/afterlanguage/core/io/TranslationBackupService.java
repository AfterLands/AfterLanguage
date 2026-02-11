package com.afterlands.afterlanguage.core.io;

import com.afterlands.afterlanguage.api.model.Translation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for automatic backup and restore of dynamic translations (v1.2.0).
 *
 * <p>Creates timestamped backups before destructive operations and provides
 * restore functionality. Implements automatic rotation to prevent disk bloat.</p>
 *
 * <h3>Backup Structure:</h3>
 * <pre>
 * plugins/AfterLanguage/backups/
 * ├── 2024-01-15_14-30-45_myplugin/
 * │   ├── pt_br/
 * │   │   └── myplugin/
 * │   │       └── backup.yml
 * │   └── en_us/
 * │       └── myplugin/
 * │           └── backup.yml
 * └── 2024-01-15_15-20-10_myplugin/
 *     └── ...
 * </pre>
 *
 * <h3>Features:</h3>
 * <ul>
 *     <li>Timestamped backup directories for easy identification</li>
 *     <li>Automatic backup before delete/overwrite operations</li>
 *     <li>Configurable backup rotation (max backups to keep)</li>
 *     <li>List and restore backups by ID or timestamp</li>
 *     <li>Async operations for performance</li>
 * </ul>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * TranslationBackupService backup = new TranslationBackupService(
 *     backupsDir, exporter, importer, logger, true, 10
 * );
 *
 * // Create backup before deletion
 * List<Translation> translations = repository.getNamespace("myplugin").join();
 * String backupId = backup.createBackup("myplugin", translations).join();
 * logger.info("Created backup: " + backupId);
 *
 * // Later: restore backup
 * backup.restoreBackup(backupId, "myplugin", repository).join();
 * }</pre>
 *
 * @since 1.2.0
 */
public class TranslationBackupService {

    private static final DateTimeFormatter BACKUP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                    .withZone(ZoneId.systemDefault());

    private final Path backupsDir;
    private final TranslationExporter exporter;
    private final TranslationImporter importer;
    private final Logger logger;
    private final boolean enabled;
    private final int maxBackups;

    /**
     * Creates a backup service.
     *
     * @param backupsDir Directory to store backups
     * @param exporter Translation exporter for creating backups
     * @param importer Translation importer for restoring backups
     * @param logger Logger instance
     * @param enabled Whether backups are enabled
     * @param maxBackups Maximum number of backups to keep (0 = unlimited)
     */
    public TranslationBackupService(
            @NotNull Path backupsDir,
            @NotNull TranslationExporter exporter,
            @NotNull TranslationImporter importer,
            @NotNull Logger logger,
            boolean enabled,
            int maxBackups
    ) {
        this.backupsDir = Objects.requireNonNull(backupsDir, "backupsDir cannot be null");
        this.exporter = Objects.requireNonNull(exporter, "exporter cannot be null");
        this.importer = Objects.requireNonNull(importer, "importer cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.enabled = enabled;
        this.maxBackups = Math.max(0, maxBackups);
    }

    /**
     * Creates a backup of translations for a namespace.
     *
     * @param namespace Namespace to backup
     * @param translations Translations to backup
     * @return CompletableFuture with backup ID (timestamp_namespace format)
     */
    @NotNull
    public CompletableFuture<String> createBackup(
            @NotNull String namespace,
            @NotNull List<Translation> translations
    ) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(translations, "translations cannot be null");

        if (!enabled) {
            logger.fine("[BackupService] Backups disabled, skipping backup for: " + namespace);
            return CompletableFuture.completedFuture("");
        }

        if (translations.isEmpty()) {
            logger.fine("[BackupService] No translations to backup for: " + namespace);
            return CompletableFuture.completedFuture("");
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Generate backup ID with timestamp
                String timestamp = BACKUP_FORMATTER.format(Instant.now());
                String backupId = timestamp + "_" + namespace;
                Path backupDir = backupsDir.resolve(backupId);

                // Create backup directory
                Files.createDirectories(backupDir);

                // Export translations to backup directory
                TranslationExporter.ExportResult result = exporter.exportNamespace(
                        namespace,
                        translations,
                        backupDir
                );

                logger.info("[BackupService] Created backup '" + backupId + "' with " +
                           result.exportedCount() + " translations");

                // Clean old backups
                cleanOldBackups(namespace);

                return backupId;

            } catch (IOException e) {
                logger.severe("[BackupService] Failed to create backup for " + namespace + ": " + e.getMessage());
                throw new RuntimeException("Backup creation failed", e);
            }
        });
    }

    /**
     * Lists all available backups.
     *
     * @return CompletableFuture with list of backup info
     */
    @NotNull
    public CompletableFuture<List<BackupInfo>> listBackups() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.exists(backupsDir)) {
                    return List.of();
                }

                try (Stream<Path> paths = Files.list(backupsDir)) {
                    return paths
                            .filter(Files::isDirectory)
                            .map(this::parseBackupInfo)
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparing(BackupInfo::timestamp).reversed())
                            .collect(Collectors.toList());
                }

            } catch (IOException e) {
                logger.warning("[BackupService] Failed to list backups: " + e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * Lists backups for a specific namespace.
     *
     * @param namespace Namespace to filter by
     * @return CompletableFuture with list of backup info
     */
    @NotNull
    public CompletableFuture<List<BackupInfo>> listBackups(@NotNull String namespace) {
        Objects.requireNonNull(namespace, "namespace cannot be null");

        return listBackups()
                .thenApply(backups -> backups.stream()
                        .filter(b -> b.namespace().equals(namespace))
                        .collect(Collectors.toList()));
    }

    /**
     * Restores translations from a backup.
     *
     * <p>This will import all translations from the backup, overwriting existing ones.</p>
     *
     * @param backupId Backup ID to restore
     * @param namespace Namespace to restore (must match backup namespace)
     * @param importer Importer to use for restoration
     * @return CompletableFuture with number of restored translations
     */
    @NotNull
    public CompletableFuture<Integer> restoreBackup(
            @NotNull String backupId,
            @NotNull String namespace,
            @NotNull TranslationImporter importer
    ) {
        Objects.requireNonNull(backupId, "backupId cannot be null");
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(importer, "importer cannot be null");

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path backupDir = backupsDir.resolve(backupId);

                if (!Files.exists(backupDir)) {
                    throw new IllegalArgumentException("Backup not found: " + backupId);
                }

                // Verify namespace matches
                BackupInfo info = parseBackupInfo(backupDir);
                if (info == null || !info.namespace().equals(namespace)) {
                    throw new IllegalArgumentException("Backup namespace mismatch: expected " +
                            namespace + ", found " + (info != null ? info.namespace() : "unknown"));
                }

                // Find all YAML files in backup
                List<Path> yamlFiles = new ArrayList<>();
                try (Stream<Path> paths = Files.walk(backupDir)) {
                    paths.filter(Files::isRegularFile)
                         .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                         .forEach(yamlFiles::add);
                }

                if (yamlFiles.isEmpty()) {
                    logger.warning("[BackupService] No YAML files found in backup: " + backupId);
                    return 0;
                }

                // Import all files
                int totalRestored = 0;
                for (Path yamlFile : yamlFiles) {
                    // Detect language from path structure: backupDir/<language>/<namespace>/file.yml
                    String language = detectLanguageFromPath(backupDir, yamlFile);

                    if (language != null) {
                        TranslationImporter.ImportResult result = importer.importFromFile(
                                yamlFile, namespace, language, true // overwrite = true
                        ).join();

                        totalRestored += result.importedCount();
                    }
                }

                logger.info("[BackupService] Restored " + totalRestored + " translations from backup: " + backupId);

                return totalRestored;

            } catch (Exception e) {
                logger.severe("[BackupService] Failed to restore backup " + backupId + ": " + e.getMessage());
                throw new RuntimeException("Backup restoration failed", e);
            }
        });
    }

    /**
     * Deletes a backup.
     *
     * @param backupId Backup ID to delete
     * @return CompletableFuture that completes when deleted
     */
    @NotNull
    public CompletableFuture<Void> deleteBackup(@NotNull String backupId) {
        Objects.requireNonNull(backupId, "backupId cannot be null");

        return CompletableFuture.runAsync(() -> {
            try {
                Path backupDir = backupsDir.resolve(backupId);

                if (!Files.exists(backupDir)) {
                    logger.warning("[BackupService] Backup not found: " + backupId);
                    return;
                }

                // Delete directory recursively
                deleteDirectoryRecursively(backupDir);

                logger.info("[BackupService] Deleted backup: " + backupId);

            } catch (IOException e) {
                logger.severe("[BackupService] Failed to delete backup " + backupId + ": " + e.getMessage());
                throw new RuntimeException("Backup deletion failed", e);
            }
        });
    }

    /**
     * Cleans old backups for a namespace, keeping only the most recent ones.
     *
     * @param namespace Namespace to clean backups for
     */
    private void cleanOldBackups(@NotNull String namespace) {
        if (maxBackups <= 0) {
            return; // Unlimited backups
        }

        try {
            List<BackupInfo> backups = listBackups(namespace).join();

            if (backups.size() <= maxBackups) {
                return; // Within limit
            }

            // Sort by timestamp (newest first) and delete oldest
            List<BackupInfo> toDelete = backups.stream()
                    .sorted(Comparator.comparing(BackupInfo::timestamp).reversed())
                    .skip(maxBackups)
                    .collect(Collectors.toList());

            for (BackupInfo backup : toDelete) {
                deleteBackup(backup.backupId()).join();
                logger.fine("[BackupService] Cleaned old backup: " + backup.backupId());
            }

            if (!toDelete.isEmpty()) {
                logger.info("[BackupService] Cleaned " + toDelete.size() + " old backups for: " + namespace);
            }

        } catch (Exception e) {
            logger.warning("[BackupService] Failed to clean old backups: " + e.getMessage());
        }
    }

    /**
     * Parses backup info from a backup directory.
     *
     * @param backupDir Backup directory
     * @return BackupInfo or null if invalid
     */
    @Nullable
    private BackupInfo parseBackupInfo(@NotNull Path backupDir) {
        String backupId = backupDir.getFileName().toString();

        // Format: yyyy-MM-dd_HH-mm-ss_namespace
        String[] parts = backupId.split("_");
        if (parts.length < 3) {
            return null; // Invalid format
        }

        // Reconstruct timestamp and namespace
        String timestamp = parts[0] + "_" + parts[1];  // "yyyy-MM-dd_HH-mm-ss"
        String namespace = String.join("_", Arrays.copyOfRange(parts, 2, parts.length));

        try {
            Instant instant = Instant.from(BACKUP_FORMATTER.parse(timestamp));

            // Count translations
            long translationCount = 0;
            try (Stream<Path> paths = Files.walk(backupDir)) {
                translationCount = paths
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                        .count();
            }

            return new BackupInfo(backupId, namespace, instant, (int) translationCount);

        } catch (Exception e) {
            logger.fine("[BackupService] Failed to parse backup info for: " + backupId);
            return null;
        }
    }

    /**
     * Detects language code from file path structure.
     *
     * @param backupDir Backup root directory
     * @param yamlFile YAML file path
     * @return Language code or null if not detected
     */
    @Nullable
    private String detectLanguageFromPath(@NotNull Path backupDir, @NotNull Path yamlFile) {
        Path relativePath = backupDir.relativize(yamlFile);

        if (relativePath.getNameCount() < 2) {
            return null;
        }

        // First directory is the language
        return relativePath.getName(0).toString();
    }

    /**
     * Deletes a directory recursively.
     *
     * @param directory Directory to delete
     * @throws IOException if deletion fails
     */
    private void deleteDirectoryRecursively(@NotNull Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                 .forEach(path -> {
                     try {
                         Files.delete(path);
                     } catch (IOException e) {
                         logger.warning("[BackupService] Failed to delete: " + path);
                     }
                 });
        }
    }

    /**
     * Checks if backups are enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Gets maximum number of backups to keep.
     *
     * @return Max backups (0 = unlimited)
     */
    public int getMaxBackups() {
        return maxBackups;
    }

    /**
     * Information about a backup.
     *
     * @param backupId Backup identifier (timestamp_namespace)
     * @param namespace Namespace that was backed up
     * @param timestamp When backup was created
     * @param translationCount Number of translation files in backup
     */
    public record BackupInfo(
            @NotNull String backupId,
            @NotNull String namespace,
            @NotNull Instant timestamp,
            int translationCount
    ) {
        public BackupInfo {
            Objects.requireNonNull(backupId, "backupId cannot be null");
            Objects.requireNonNull(namespace, "namespace cannot be null");
            Objects.requireNonNull(timestamp, "timestamp cannot be null");
        }

        /**
         * Formats timestamp as readable string.
         *
         * @return Formatted timestamp
         */
        @NotNull
        public String formattedTimestamp() {
            return BACKUP_FORMATTER.format(timestamp);
        }

        @Override
        public String toString() {
            return "BackupInfo{id=" + backupId + ", namespace=" + namespace +
                   ", timestamp=" + formattedTimestamp() + ", files=" + translationCount + "}";
        }
    }
}
