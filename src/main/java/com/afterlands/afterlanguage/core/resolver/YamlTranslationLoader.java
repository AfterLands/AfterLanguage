package com.afterlands.afterlanguage.core.resolver;

import com.afterlands.afterlanguage.api.model.Language;
import com.afterlands.afterlanguage.api.model.PluralCategory;
import com.afterlands.afterlanguage.api.model.Translation;
import org.bukkit.configuration.ConfigurationSection;
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
 *
 * <h3>Plural Forms (v1.2.0):</h3>
 * <pre>
 * # Simple translation
 * welcome: "Welcome!"
 *
 * # With plural forms
 * items:
 *   one: "1 item"
 *   other: "{count} items"
 *
 * # Full ICU plural forms
 * items:
 *   zero: "No items"
 *   one: "1 item"
 *   two: "2 items"
 *   few: "{count} items (few)"
 *   many: "{count} items (many)"
 *   other: "{count} items"
 * </pre>
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
            loadSection(yaml, "", namespace, language, prefix, timestamp, translations);
        } catch (Exception e) {
            logger.severe("[YamlLoader] Failed to load file " + file + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Recursively loads a configuration section.
     */
    private void loadSection(
            @NotNull ConfigurationSection section,
            @NotNull String currentPath,
            @NotNull String namespace,
            @NotNull Language language,
            @NotNull String filePrefix,
            @NotNull Instant timestamp,
            @NotNull Map<String, Translation> translations
    ) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            String relativeKey = currentPath.isEmpty() ? key : currentPath + "." + key;
            String fullKey = filePrefix + relativeKey;

            if (value instanceof ConfigurationSection) {
                ConfigurationSection subsection = (ConfigurationSection) value;
                
                // Check if this section is a plural definition
                Map<PluralCategory, String> pluralForms = parsePluralForms(subsection, fullKey);
                
                if (pluralForms != null) {
                    // It IS a plural definition
                    String defaultText = pluralForms.getOrDefault(PluralCategory.OTHER, "");
                    translations.put(fullKey, new Translation(
                            namespace,
                            fullKey,
                            language.code(),
                            defaultText,
                            null,
                            pluralForms,
                            timestamp,
                            null
                    ));
                } else {
                    // It is NOT a plural definition (or invalid one), treat as nested structure
                    // Recursively load subsection
                    loadSection(subsection, relativeKey, namespace, language, filePrefix, timestamp, translations);
                }
                
            } else if (value instanceof String) {
                translations.put(fullKey, new Translation(
                        namespace,
                        fullKey,
                        language.code(),
                        (String) value,
                        null,
                        null,
                        timestamp,
                        null
                ));
                
            } else if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> lines = (List<String>) value;
                String text = String.join("\n", lines);
                translations.put(fullKey, new Translation(
                        namespace,
                        fullKey,
                        language.code(),
                        text,
                        null,
                        null,
                        timestamp,
                        null
                ));
            }
        }
    }

    /**
     * Parses plural forms from a YAML configuration section.
     *
     * <p>Expects keys like: zero, one, two, few, many, other</p>
     *
     * @return Map of PluralCategory to text, or null if not a valid plural definition
     */
    private Map<PluralCategory, String> parsePluralForms(
            @NotNull ConfigurationSection section,
            @NotNull String fullKey
    ) {
        // Optimization: Quick check if it looks like a plural section
        // A plural section MUST NOT have arbitrary keys that are not plural categories
        // But checking strictness might break mixed content.
        // Better rule: It IS a plural section if it contains 'other' AND all other keys are valid categories.
        
        if (!section.contains(PluralCategory.OTHER.getKey())) {
            return null; // Not a plural section if 'other' is missing
        }

        Map<PluralCategory, String> pluralForms = new HashMap<>();
        boolean hasInvalidKeys = false;

        for (String key : section.getKeys(false)) {
            try {
                PluralCategory category = PluralCategory.fromKey(key);
                Object val = section.get(key);
                if (val instanceof String) {
                    pluralForms.put(category, (String) val);
                } else {
                    hasInvalidKeys = true; // Plural values must be strings
                }
            } catch (IllegalArgumentException e) {
                hasInvalidKeys = true; // Found a key that isn't a plural category
            }
        }

        // If we found 'other' but also found invalid keys (like 'enabled', 'title'),
        // then this is likely a nested structure that just happens to have an 'other' key.
        // However, standard plural format shouldn't be mixed with other keys.
        // For safety, we'll assume:
        // - If it has 'other' AND only valid plural keys -> It's a plural
        // - If it has 'other' AND other keys -> Treat as nested structure (and 'other' is just a key named other)
        
        if (hasInvalidKeys) {
            return null;
        }

        return pluralForms;
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
