package com.afterlands.afterlanguage.core.extractor;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Extracts messages.yml from a plugin's data folder and generates
 * translation files in AfterLanguage's languages directory.
 *
 * <p>Source language is always overwritten (sync with plugin).
 * Other languages are created as copies only if they don't exist
 * (preserves existing translations).</p>
 */
public class MessageExtractor {

    private final Path languagesDir;
    private final String sourceLanguage;
    private final List<String> enabledLanguages;
    private final Logger logger;
    private final boolean debug;

    public MessageExtractor(
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
     * Extracts a messages file from a plugin and generates translation files.
     *
     * @param sourceFile The plugin's messages.yml file
     * @param namespace  Namespace identifier (e.g., "aftercore")
     * @param outputName Output file name without extension (e.g., "messages")
     */
    public void extract(@NotNull File sourceFile, @NotNull String namespace, @NotNull String outputName) {
        if (!sourceFile.exists()) {
            if (debug) {
                logger.fine("[MessageExtractor] Source file not found: " + sourceFile);
            }
            return;
        }

        YamlConfiguration sourceYaml = YamlConfiguration.loadConfiguration(sourceFile);

        for (String lang : enabledLanguages) {
            File targetFile = languagesDir.resolve(lang).resolve(namespace)
                    .resolve(outputName + ".yml").toFile();

            if (lang.equals(sourceLanguage)) {
                // Source language: always overwrite (sync with plugin)
                writeYaml(sourceYaml, targetFile);
                if (debug) {
                    logger.fine("[MessageExtractor] Synced source language: " + targetFile);
                }
            } else {
                // Other languages: create only if doesn't exist
                if (!targetFile.exists()) {
                    writeYaml(sourceYaml, targetFile);
                    if (debug) {
                        logger.fine("[MessageExtractor] Created translation template: " + targetFile);
                    }
                }
            }
        }

        logger.info("[MessageExtractor] Extracted '" + outputName + "' for namespace '" + namespace
                + "' from " + sourceFile.getName());
    }

    /**
     * Writes a YamlConfiguration to a file, creating parent directories as needed.
     */
    private void writeYaml(@NotNull YamlConfiguration yaml, @NotNull File targetFile) {
        try {
            targetFile.getParentFile().mkdirs();
            yaml.save(targetFile);
        } catch (IOException e) {
            logger.warning("[MessageExtractor] Failed to write " + targetFile + ": " + e.getMessage());
        }
    }
}
