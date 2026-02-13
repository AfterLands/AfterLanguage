package com.afterlands.afterlanguage.core.extractor;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Generic YAML extractor with two modes:
 *
 * <ul>
 *     <li><b>extractAll:</b> Copies the entire YAML structure as translations</li>
 *     <li><b>extractFields:</b> Recursively extracts only specific field names</li>
 * </ul>
 *
 * <p>Source language is always overwritten. Other languages are created only if
 * they don't exist (preserves existing translations).</p>
 */
public class YamlExtractor {

    private final Path languagesDir;
    private final String sourceLanguage;
    private final List<String> enabledLanguages;
    private final Logger logger;
    private final boolean debug;

    public YamlExtractor(
            @NotNull Path languagesDir,
            @NotNull String sourceLanguage,
            @NotNull List<String> enabledLanguages,
            @NotNull Logger logger,
            boolean debug) {
        this.languagesDir = languagesDir;
        this.sourceLanguage = sourceLanguage;
        this.enabledLanguages = enabledLanguages;
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Extracts the entire YAML structure as translations.
     *
     * @param source     Source YAML file
     * @param namespace  Namespace identifier
     * @param outputName Output file name without extension
     */
    public void extractAll(@NotNull File source, @NotNull String namespace, @NotNull String outputName) {
        if (!source.exists()) {
            return;
        }

        YamlConfiguration sourceYaml = YamlConfiguration.loadConfiguration(source);
        writeToLanguages(sourceYaml, namespace, outputName);

        logger.info("[YamlExtractor] Extracted all from '" + source.getName()
                + "' for namespace '" + namespace + "'");
    }

    /**
     * Extracts only specific fields from the YAML structure.
     *
     * <p>Recursively walks the YAML tree and copies only values whose
     * key name matches one of the specified fields.</p>
     *
     * @param source     Source YAML file
     * @param namespace  Namespace identifier
     * @param outputName Output file name without extension
     * @param fields     Set of field names to extract (e.g., "name", "description", "lore")
     */
    public void extractFields(@NotNull File source, @NotNull String namespace,
                              @NotNull String outputName, @NotNull Set<String> fields) {
        if (!source.exists()) {
            return;
        }

        YamlConfiguration sourceYaml = YamlConfiguration.loadConfiguration(source);
        YamlConfiguration outputYaml = new YamlConfiguration();

        extractFieldsRecursive(sourceYaml, outputYaml, "", fields);

        writeToLanguages(outputYaml, namespace, outputName);

        logger.info("[YamlExtractor] Extracted fields " + fields + " from '" + source.getName()
                + "' for namespace '" + namespace + "'");
    }

    /**
     * Recursively walks the YAML tree and copies matching field values.
     */
    private void extractFieldsRecursive(@NotNull ConfigurationSection source,
                                        @NotNull YamlConfiguration output,
                                        @NotNull String basePath,
                                        @NotNull Set<String> fields) {
        for (String key : source.getKeys(false)) {
            String fullPath = basePath.isEmpty() ? key : basePath + "." + key;

            if (fields.contains(key)) {
                // This is a target field — copy its value
                Object value = source.get(key);
                if (value != null && !(value instanceof ConfigurationSection)) {
                    output.set(fullPath, value);
                }
            }

            // Recurse into sub-sections regardless
            ConfigurationSection sub = source.getConfigurationSection(key);
            if (sub != null) {
                extractFieldsRecursive(sub, output, fullPath, fields);
            }
        }
    }

    /**
     * Writes YAML to all language directories.
     */
    private void writeToLanguages(@NotNull YamlConfiguration yaml,
                                  @NotNull String namespace,
                                  @NotNull String outputName) {
        for (String lang : enabledLanguages) {
            File targetFile = languagesDir.resolve(lang).resolve(namespace)
                    .resolve(outputName + ".yml").toFile();

            if (lang.equals(sourceLanguage)) {
                writeYaml(yaml, targetFile);
            } else {
                if (!targetFile.exists()) {
                    writeYaml(yaml, targetFile);
                }
            }
        }
    }

    private void writeYaml(@NotNull YamlConfiguration yaml, @NotNull File targetFile) {
        try {
            targetFile.getParentFile().mkdirs();
            yaml.save(targetFile);
        } catch (IOException e) {
            logger.warning("[YamlExtractor] Failed to write " + targetFile + ": " + e.getMessage());
        }
    }
}
