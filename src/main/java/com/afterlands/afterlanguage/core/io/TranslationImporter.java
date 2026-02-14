package com.afterlands.afterlanguage.core.io;

import com.afterlands.afterlanguage.api.model.PluralCategory;
import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.afterlanguage.core.service.DynamicTranslationStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Service for importing translations from YAML files (v1.2.0).
 *
 * <p>Imports translations from YAML files into the dynamic translations database.
 * Supports both simple translations and plural forms.</p>
 *
 * <h3>Input Format:</h3>
 * <pre>
 * # Simple translation
 * welcome: "Welcome to the server!"
 *
 * # Plural forms
 * items:
 *   one: "1 item"
 *   other: "{count} items"
 * </pre>
 *
 * <h3>Features:</h3>
 * <ul>
 *     <li>Import from single file or directory</li>
 *     <li>Automatic plural form detection</li>
 *     <li>Overwrite or skip existing translations</li>
 *     <li>Validation before import</li>
 *     <li>Async database operations</li>
 * </ul>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * TranslationImporter importer = new TranslationImporter(repository, logger, debug);
 *
 * Path yamlFile = Paths.get("translations.yml");
 * ImportResult result = importer.importFromFile(yamlFile, "myplugin", "pt_br", true).join();
 *
 * logger.info("Imported " + result.importedCount() + " translations");
 * }</pre>
 *
 * @since 1.2.0
 */
public class TranslationImporter {

    private final DynamicTranslationStore repository;
    private final Logger logger;
    private final boolean debug;

    public TranslationImporter(
            @NotNull DynamicTranslationStore repository,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.debug = debug;
    }

    /**
     * Imports translations from a YAML file.
     *
     * @param file YAML file to import from
     * @param namespace Target namespace
     * @param language Target language code
     * @param overwrite If true, overwrites existing translations; if false, skips them
     * @return CompletableFuture with import result
     */
    @NotNull
    public CompletableFuture<ImportResult> importFromFile(
            @NotNull Path file,
            @NotNull String namespace,
            @NotNull String language,
            boolean overwrite
    ) {
        Objects.requireNonNull(file, "file cannot be null");
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(language, "language cannot be null");

        if (!Files.exists(file)) {
            return CompletableFuture.failedFuture(
                    new IOException("File not found: " + file)
            );
        }

        if (!file.toString().endsWith(".yml") && !file.toString().endsWith(".yaml")) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("File must be YAML (.yml or .yaml): " + file)
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Load YAML file
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());

                // Parse translations
                List<Translation> translations = parseYaml(yaml, namespace, language);

                if (debug) {
                    logger.fine("[TranslationImporter] Parsed " + translations.size() +
                               " translations from " + file);
                }

                // Import to database
                return importTranslations(translations, overwrite).join();

            } catch (Exception e) {
                logger.severe("[TranslationImporter] Failed to import from " + file + ": " + e.getMessage());
                throw new RuntimeException("Import failed", e);
            }
        });
    }

    /**
     * Imports translations from all YAML files in a directory.
     *
     * <p>Recursively scans directory for .yml/.yaml files and imports them all.</p>
     *
     * @param directory Directory to scan
     * @param namespace Target namespace
     * @param language Target language code
     * @param overwrite If true, overwrites existing translations
     * @return CompletableFuture with combined import result
     */
    @NotNull
    public CompletableFuture<ImportResult> importFromDirectory(
            @NotNull Path directory,
            @NotNull String namespace,
            @NotNull String language,
            boolean overwrite
    ) {
        Objects.requireNonNull(directory, "directory cannot be null");
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(language, "language cannot be null");

        if (!Files.isDirectory(directory)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Not a directory: " + directory)
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Find all YAML files
                List<Path> yamlFiles = new ArrayList<>();
                try (Stream<Path> paths = Files.walk(directory)) {
                    paths.filter(Files::isRegularFile)
                         .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                         .forEach(yamlFiles::add);
                }

                if (yamlFiles.isEmpty()) {
                    logger.warning("[TranslationImporter] No YAML files found in " + directory);
                    return new ImportResult(0, 0, List.of());
                }

                // Import all files
                int totalImported = 0;
                int totalSkipped = 0;
                List<String> importedKeys = new ArrayList<>();

                for (Path file : yamlFiles) {
                    ImportResult result = importFromFile(file, namespace, language, overwrite).join();
                    totalImported += result.importedCount();
                    totalSkipped += result.skippedCount();
                    importedKeys.addAll(result.importedKeys());
                }

                logger.info("[TranslationImporter] Imported " + totalImported + " translations from " +
                           yamlFiles.size() + " files in " + directory);

                return new ImportResult(totalImported, totalSkipped, importedKeys);

            } catch (Exception e) {
                logger.severe("[TranslationImporter] Failed to import from directory " +
                             directory + ": " + e.getMessage());
                throw new RuntimeException("Directory import failed", e);
            }
        });
    }

    /**
     * Imports a list of translations to the database.
     *
     * @param translations Translations to import
     * @param overwrite If true, overwrites existing; if false, checks existence first
     * @return CompletableFuture with import result
     */
    @NotNull
    private CompletableFuture<ImportResult> importTranslations(
            @NotNull List<Translation> translations,
            boolean overwrite
    ) {
        if (translations.isEmpty()) {
            return CompletableFuture.completedFuture(new ImportResult(0, 0, List.of()));
        }

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        List<String> importedKeys = new ArrayList<>();
        int[] skippedCount = {0};

        for (Translation translation : translations) {
            CompletableFuture<Boolean> future;

            if (overwrite) {
                // Always save
                future = repository.save(translation)
                        .thenApply(v -> {
                            importedKeys.add(translation.fullKey());
                            return true;
                        });
            } else {
                // Check if exists first
                future = repository.exists(translation.namespace(), translation.key(), translation.language())
                        .thenCompose(exists -> {
                            if (exists) {
                                skippedCount[0]++;
                                return CompletableFuture.completedFuture(false);
                            } else {
                                return repository.save(translation)
                                        .thenApply(v -> {
                                            importedKeys.add(translation.fullKey());
                                            return true;
                                        });
                            }
                        });
            }

            futures.add(future);
        }

        // Wait for all imports
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    long imported = futures.stream()
                            .map(CompletableFuture::join)
                            .filter(Boolean::booleanValue)
                            .count();

                    return new ImportResult((int) imported, skippedCount[0], importedKeys);
                });
    }

    /**
     * Parses YAML configuration into Translation objects.
     *
     * @param yaml YAML configuration
     * @param namespace Target namespace
     * @param language Target language
     * @return List of parsed translations
     */
    @NotNull
    private List<Translation> parseYaml(
            @NotNull YamlConfiguration yaml,
            @NotNull String namespace,
            @NotNull String language
    ) {
        List<Translation> translations = new ArrayList<>();

        for (String key : yaml.getKeys(false)) {
            Object value = yaml.get(key);

            if (value == null) {
                continue;
            }

            if (value instanceof String) {
                // Simple translation
                Translation translation = Translation.of(namespace, key, language, (String) value);
                translations.add(translation);

            } else if (value instanceof ConfigurationSection) {
                // Possible plural forms
                ConfigurationSection section = (ConfigurationSection) value;
                Map<PluralCategory, String> pluralForms = parsePluralForms(section);

                if (pluralForms != null && !pluralForms.isEmpty()) {
                    // Valid plural forms
                    Translation translation = Translation.withPluralForms(namespace, key, language, pluralForms);
                    translations.add(translation);
                } else {
                    // Not plural forms, might be nested structure (skip)
                    if (debug) {
                        logger.fine("[TranslationImporter] Skipping non-translation section: " + key);
                    }
                }
            }
        }

        return translations;
    }

    /**
     * Parses plural forms from a configuration section.
     *
     * @param section Configuration section
     * @return Map of plural forms, or null if invalid
     */
    private Map<PluralCategory, String> parsePluralForms(@NotNull ConfigurationSection section) {
        Map<PluralCategory, String> pluralForms = new HashMap<>();

        for (String key : section.getKeys(false)) {
            Object value = section.get(key);

            if (!(value instanceof String)) {
                continue;
            }

            try {
                PluralCategory category = PluralCategory.fromKey(key);
                pluralForms.put(category, (String) value);
            } catch (IllegalArgumentException e) {
                // Not a valid plural category key
                return null;
            }
        }

        // Must have at least OTHER
        if (!pluralForms.containsKey(PluralCategory.OTHER)) {
            return null;
        }

        return pluralForms;
    }

    /**
     * Result of an import operation.
     *
     * @param importedCount Number of translations imported
     * @param skippedCount Number of translations skipped (already exist)
     * @param importedKeys List of imported translation keys
     */
    public record ImportResult(
            int importedCount,
            int skippedCount,
            @NotNull List<String> importedKeys
    ) {
        public ImportResult {
            Objects.requireNonNull(importedKeys, "importedKeys cannot be null");
        }

        public int totalProcessed() {
            return importedCount + skippedCount;
        }

        @Override
        public String toString() {
            return "ImportResult{imported=" + importedCount + ", skipped=" + skippedCount + "}";
        }
    }
}
