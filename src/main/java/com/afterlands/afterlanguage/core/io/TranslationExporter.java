package com.afterlands.afterlanguage.core.io;

import com.afterlands.afterlanguage.api.model.PluralCategory;
import com.afterlands.afterlanguage.api.model.Translation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Service for exporting translations to YAML files (v1.2.0).
 *
 * <p>Exports translations from the registry or database to YAML files
 * in the standard AfterLanguage format.</p>
 *
 * <h3>Output Structure:</h3>
 * <pre>
 * outputDir/
 * ├── pt_br/
 * │   └── namespace/
 * │       └── exported.yml
 * └── en_us/
 *     └── namespace/
 *         └── exported.yml
 * </pre>
 *
 * <h3>Features:</h3>
 * <ul>
 *     <li>Export entire namespace or specific language</li>
 *     <li>Preserve plural forms structure</li>
 *     <li>Organize by language and namespace</li>
 *     <li>Generate clean, readable YAML</li>
 * </ul>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * TranslationExporter exporter = new TranslationExporter(logger, debug);
 *
 * List<Translation> translations = registry.getNamespace("myplugin");
 * Path outputDir = Paths.get("exports");
 *
 * ExportResult result = exporter.exportNamespace("myplugin", translations, outputDir);
 * logger.info("Exported " + result.exportedCount() + " translations to " + result.files().size() + " files");
 * }</pre>
 *
 * @since 1.2.0
 */
public class TranslationExporter {

    private final Logger logger;
    private final boolean debug;

    public TranslationExporter(@NotNull Logger logger, boolean debug) {
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.debug = debug;
    }

    /**
     * Exports all translations for a namespace to YAML files.
     *
     * <p>Creates one YAML file per language in the structure:
     * {@code outputDir/<language>/<namespace>/translations.yml}</p>
     *
     * @param namespace Namespace to export
     * @param translations List of translations to export
     * @param outputDir Output directory
     * @return Export result with statistics
     * @throws IOException if file operations fail
     */
    @NotNull
    public ExportResult exportNamespace(
            @NotNull String namespace,
            @NotNull List<Translation> translations,
            @NotNull Path outputDir
    ) throws IOException {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(translations, "translations cannot be null");
        Objects.requireNonNull(outputDir, "outputDir cannot be null");

        // Create output directory if needed
        Files.createDirectories(outputDir);

        // Group translations by language
        Map<String, List<Translation>> byLanguage = translations.stream()
                .collect(Collectors.groupingBy(Translation::language));

        List<Path> exportedFiles = new ArrayList<>();
        int totalExported = 0;

        // Export each language
        for (Map.Entry<String, List<Translation>> entry : byLanguage.entrySet()) {
            String language = entry.getKey();
            List<Translation> langTranslations = entry.getValue();

            // Create language/namespace directory
            Path langDir = outputDir.resolve(language).resolve(namespace);
            Files.createDirectories(langDir);

            // Export to YAML file
            Path yamlFile = langDir.resolve("translations.yml");
            int exported = exportToYaml(langTranslations, yamlFile);

            exportedFiles.add(yamlFile);
            totalExported += exported;

            if (debug) {
                logger.fine("[TranslationExporter] Exported " + exported + " translations to " + yamlFile);
            }
        }

        logger.info("[TranslationExporter] Exported " + totalExported + " translations for namespace '" +
                   namespace + "' to " + exportedFiles.size() + " files");

        return new ExportResult(totalExported, exportedFiles);
    }

    /**
     * Exports translations for a specific language only.
     *
     * @param namespace Namespace to export
     * @param language Language code
     * @param translations List of translations to export
     * @param outputDir Output directory
     * @return Export result with statistics
     * @throws IOException if file operations fail
     */
    @NotNull
    public ExportResult exportNamespaceByLanguage(
            @NotNull String namespace,
            @NotNull String language,
            @NotNull List<Translation> translations,
            @NotNull Path outputDir
    ) throws IOException {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(language, "language cannot be null");
        Objects.requireNonNull(translations, "translations cannot be null");
        Objects.requireNonNull(outputDir, "outputDir cannot be null");

        // Filter by language
        List<Translation> filtered = translations.stream()
                .filter(t -> t.language().equals(language))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            logger.warning("[TranslationExporter] No translations found for language: " + language);
            return new ExportResult(0, List.of());
        }

        // Create language/namespace directory
        Path langDir = outputDir.resolve(language).resolve(namespace);
        Files.createDirectories(langDir);

        // Export to YAML file
        Path yamlFile = langDir.resolve("translations.yml");
        int exported = exportToYaml(filtered, yamlFile);

        logger.info("[TranslationExporter] Exported " + exported + " translations for namespace '" +
                   namespace + "' [" + language + "] to " + yamlFile);

        return new ExportResult(exported, List.of(yamlFile));
    }

    /**
     * Exports translations to a single flat YAML file.
     *
     * <p>Creates a single timestamped file like {@code namespace_language_2026-02-10_14-30-00.yml}
     * directly in the output directory.</p>
     *
     * @param namespace Namespace being exported
     * @param language Language code
     * @param translations Translations to export (already filtered by language)
     * @param outputFile Target output file path
     * @return Export result with statistics
     * @throws IOException if file operations fail
     */
    @NotNull
    public ExportResult exportToSingleFile(
            @NotNull String namespace,
            @NotNull String language,
            @NotNull List<Translation> translations,
            @NotNull Path outputFile
    ) throws IOException {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(language, "language cannot be null");
        Objects.requireNonNull(translations, "translations cannot be null");
        Objects.requireNonNull(outputFile, "outputFile cannot be null");

        // Filter by language
        List<Translation> filtered = translations.stream()
                .filter(t -> t.language().equals(language))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            logger.warning("[TranslationExporter] No translations found for language: " + language);
            return new ExportResult(0, List.of());
        }

        // Ensure parent directory exists
        Files.createDirectories(outputFile.getParent());

        // Export to single YAML file
        int exported = exportToYaml(filtered, outputFile);

        logger.info("[TranslationExporter] Exported " + exported + " translations for namespace '" +
                   namespace + "' [" + language + "] to " + outputFile);

        return new ExportResult(exported, List.of(outputFile));
    }

    /**
     * Exports translations to a single YAML file.
     *
     * @param translations Translations to export
     * @param outputFile Output YAML file
     * @return Number of translations exported
     * @throws IOException if file operations fail
     */
    private int exportToYaml(
            @NotNull List<Translation> translations,
            @NotNull Path outputFile
    ) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();

        // Add header comment
        yaml.options().header(
                "AfterLanguage - Exported Translations\n" +
                "Generated: " + new Date() + "\n" +
                "Translations: " + translations.size()
        );

        // Add each translation
        for (Translation translation : translations) {
            String key = translation.key();

            if (translation.hasPlural()) {
                // Export with plural forms
                Map<PluralCategory, String> pluralForms = translation.pluralForms();

                if (pluralForms != null) {
                    for (Map.Entry<PluralCategory, String> entry : pluralForms.entrySet()) {
                        String pluralKey = key + "." + entry.getKey().getKey();
                        yaml.set(pluralKey, entry.getValue());
                    }
                }
            } else {
                // Simple translation
                yaml.set(key, translation.text());
            }
        }

        // Save to file
        yaml.save(outputFile.toFile());

        return translations.size();
    }

    /**
     * Result of an export operation.
     *
     * @param exportedCount Number of translations exported
     * @param files List of files created
     */
    public record ExportResult(
            int exportedCount,
            @NotNull List<Path> files
    ) {
        public ExportResult {
            Objects.requireNonNull(files, "files cannot be null");
        }

        @Override
        public String toString() {
            return "ExportResult{exported=" + exportedCount + ", files=" + files.size() + "}";
        }
    }
}
