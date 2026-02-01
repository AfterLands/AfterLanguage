package com.afterlands.afterlanguage.core.resolver;

import com.afterlands.afterlanguage.api.model.Language;
import com.afterlands.afterlanguage.api.model.Translation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Loads translations from YAML files in the filesystem.
 *
 * <h3>File Structure:</h3>
 * <pre>
 * plugins/AfterLanguage/languages/
 * ├── pt_br/              # Source language
 * │   ├── afterlanguage.yml
 * │   ├── aftercore.yml
 * │   └── afterjournal/   # Namespace with subfolders
 * │       ├── gui.yml
 * │       └── quests/
 * │           └── tutorial.yml
 * └── en_us/              # Mirror structure
 * </pre>
 *
 * <h3>Key Format:</h3>
 * <ul>
 *     <li>Flat keys in YAML (no nesting)</li>
 *     <li>Subfolders generate prefix: {@code quests/tutorial.yml} → prefix {@code quests.tutorial.}</li>
 * </ul>
 */
public class YamlTranslationLoader {

    private final Path languagesDir;
    private final Logger logger;
    private final boolean debug;

    /**
     * Creates a YAML translation loader.
     *
     * @param languagesDir Path to languages directory
     * @param logger Logger
     * @param debug Enable debug logging
     */
    public YamlTranslationLoader(
            @NotNull Path languagesDir,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.languagesDir = languagesDir;
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Loads all translations for a namespace in a specific language.
     *
     * @param language Language to load
     * @param namespace Namespace to load
     * @return Map of key -> Translation
     */
    @NotNull
    public Map<String, Translation> loadNamespace(@NotNull Language language, @NotNull String namespace) {
        Path nsDir = languagesDir.resolve(language.code()).resolve(namespace);

        if (!Files.exists(nsDir)) {
            if (debug) {
                logger.fine("[YamlLoader] Namespace directory not found: " + nsDir);
            }
            return Collections.emptyMap();
        }

        Map<String, Translation> translations = new HashMap<>();
        Instant now = Instant.now();

        try {
            // Walk directory tree and find all .yml files
            try (Stream<Path> paths = Files.walk(nsDir)) {
                paths.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                     .forEach(file -> {
                         String prefix = calculatePrefix(nsDir, file);
                         loadFile(file, namespace, language, prefix, now, translations);
                     });
            }

            if (debug) {
                logger.info("[YamlLoader] Loaded " + translations.size() +
                           " translations for " + namespace + " [" + language.code() + "]");
            }

        } catch (Exception e) {
            logger.severe("[YamlLoader] Failed to load namespace " + namespace + ": " + e.getMessage());
            e.printStackTrace();
        }

        return translations;
    }

    /**
     * Loads a single YAML file.
     */
    private void loadFile(
            @NotNull Path file,
            @NotNull String namespace,
            @NotNull Language language,
            @NotNull String prefix,
            @NotNull Instant timestamp,
            @NotNull Map<String, Translation> translations
    ) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());

            // Iterate over all keys in the YAML (flat structure expected)
            for (String key : yaml.getKeys(false)) {
                Object value = yaml.get(key);

                if (value == null) {
                    continue;
                }

                // Full key with prefix
                String fullKey = prefix.isEmpty() ? key : prefix + key;

                // Handle different value types
                if (value instanceof String) {
                    // Simple string translation
                    String text = (String) value;
                    translations.put(fullKey, new Translation(
                            namespace,
                            fullKey,
                            language.code(),
                            text,
                            null, // No plural
                            timestamp,
                            null  // No source hash for file-based
                    ));

                } else if (value instanceof List) {
                    // List = multiline lore
                    @SuppressWarnings("unchecked")
                    List<String> lines = (List<String>) value;
                    String text = String.join("\\n", lines);
                    translations.put(fullKey, new Translation(
                            namespace,
                            fullKey,
                            language.code(),
                            text,
                            null,
                            timestamp,
                            null
                    ));

                } else {
                    if (debug) {
                        logger.warning("[YamlLoader] Unsupported value type for key " + fullKey +
                                      ": " + value.getClass().getSimpleName());
                    }
                }
            }

        } catch (Exception e) {
            logger.severe("[YamlLoader] Failed to load file " + file + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Calculates prefix from subfolder structure.
     *
     * <p>Example: {@code languages/pt_br/afterjournal/quests/tutorial.yml}
     * → prefix = {@code quests.tutorial.}</p>
     *
     * @param nsDir Namespace directory
     * @param file YAML file
     * @return Prefix string (ends with dot if non-empty)
     */
    @NotNull
    private String calculatePrefix(@NotNull Path nsDir, @NotNull Path file) {
        Path relativePath = nsDir.relativize(file);
        String pathStr = relativePath.toString();

        // Remove file extension
        pathStr = pathStr.replaceFirst("\\.ya?ml$", "");

        // Convert path separators to dots
        pathStr = pathStr.replace(File.separator, ".");
        pathStr = pathStr.replace("/", "."); // Unix-style as well

        // Add trailing dot if not empty
        return pathStr.isEmpty() ? "" : pathStr + ".";
    }

    /**
     * Checks if namespace directory exists for a language.
     *
     * @param language Language code
     * @param namespace Namespace
     * @return true if directory exists
     */
    public boolean namespaceExists(@NotNull String language, @NotNull String namespace) {
        return Files.exists(languagesDir.resolve(language).resolve(namespace));
    }

    /**
     * Gets languages directory path.
     *
     * @return Languages directory
     */
    @NotNull
    public Path getLanguagesDir() {
        return languagesDir;
    }
}
