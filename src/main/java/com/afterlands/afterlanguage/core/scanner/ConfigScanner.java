package com.afterlands.afterlanguage.core.scanner;

import com.afterlands.afterlanguage.api.service.ScanResult;
import com.afterlands.afterlanguage.api.service.TranslationSchema;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Scans config files and extracts translatable keys.
 *
 * <h3>Features:</h3>
 * <ul>
 *     <li>Glob pattern matching for file discovery</li>
 *     <li>Wildcard YAML path navigation (e.g., items.*.name)</li>
 *     <li>Action filtering (extract only translatable actions)</li>
 *     <li>Diff detection (new/changed/removed keys)</li>
 * </ul>
 */
public class ConfigScanner {

    private final Path configRoot;
    private final Logger logger;
    private final boolean debug;

    /**
     * Creates a config scanner.
     *
     * @param configRoot Root directory for config scanning (e.g., plugin data folder)
     * @param logger Logger
     * @param debug Enable debug logging
     */
    public ConfigScanner(
            @NotNull Path configRoot,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.configRoot = Objects.requireNonNull(configRoot, "configRoot");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.debug = debug;
    }

    /**
     * Scans configs according to schema and compares with previous scan.
     *
     * @param schema Translation schema
     * @param previousKeys Keys from previous scan (for diff detection)
     * @return Scan result with new/changed/removed keys
     */
    @NotNull
    public ScanResult scan(
            @NotNull TranslationSchema schema,
            @NotNull Map<String, String> previousKeys
    ) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(previousKeys, "previousKeys");

        Map<String, String> extractedValues = new HashMap<>();

        // Process each scan rule
        for (TranslationSchema.ScanRule rule : schema.getRules()) {
            try {
                List<Path> files = findMatchingFiles(rule.getFileGlobPattern());

                for (Path file : files) {
                    scanFile(file, rule, extractedValues);
                }
            } catch (Exception e) {
                logger.warning("[ConfigScanner] Error scanning pattern " +
                        rule.getFileGlobPattern() + ": " + e.getMessage());
            }
        }

        // Detect changes
        List<String> newKeys = new ArrayList<>();
        List<String> changedKeys = new ArrayList<>();

        for (Map.Entry<String, String> entry : extractedValues.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (!previousKeys.containsKey(key)) {
                newKeys.add(key);
            } else if (!previousKeys.get(key).equals(value)) {
                changedKeys.add(key);
            }
        }

        // Detect removed keys
        List<String> removedKeys = new ArrayList<>();
        for (String key : previousKeys.keySet()) {
            if (!extractedValues.containsKey(key)) {
                removedKeys.add(key);
            }
        }

        if (debug && (newKeys.size() > 0 || changedKeys.size() > 0 || removedKeys.size() > 0)) {
            logger.info(String.format("[ConfigScanner] %s: %d new, %d changed, %d removed",
                    schema.getNamespace(), newKeys.size(), changedKeys.size(), removedKeys.size()));
        }

        return new ScanResult(
                schema.getNamespace(),
                newKeys,
                changedKeys,
                removedKeys,
                extractedValues
        );
    }

    /**
     * Finds files matching glob pattern.
     */
    @NotNull
    private List<Path> findMatchingFiles(@NotNull String globPattern) throws Exception {
        List<Path> matches = new ArrayList<>();

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

        try (Stream<Path> paths = Files.walk(configRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(configRoot.relativize(p)))
                    .forEach(matches::add);
        }

        return matches;
    }

    /**
     * Scans a single file according to rule.
     */
    private void scanFile(
            @NotNull Path file,
            @NotNull TranslationSchema.ScanRule rule,
            @NotNull Map<String, String> extractedValues
    ) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());

            for (String yamlPath : rule.getYamlPaths()) {
                extractFromPath(yaml, yamlPath, yamlPath, rule, extractedValues);
            }

        } catch (Exception e) {
            logger.warning("[ConfigScanner] Error parsing file " + file + ": " + e.getMessage());
        }
    }

    /**
     * Extracts values from YAML path (supports wildcards).
     */
    private void extractFromPath(
            @NotNull ConfigurationSection section,
            @NotNull String pathPattern,
            @NotNull String currentPath,
            @NotNull TranslationSchema.ScanRule rule,
            @NotNull Map<String, String> extractedValues
    ) {
        String[] parts = pathPattern.split("\\.", 2);
        String current = parts[0];
        String remaining = parts.length > 1 ? parts[1] : null;

        if (current.equals("*")) {
            // Wildcard - iterate all keys
            Set<String> keys = section.getKeys(false);
            for (String key : keys) {
                String newPath = currentPath.replaceFirst("\\*", key);

                if (remaining != null) {
                    ConfigurationSection child = section.getConfigurationSection(key);
                    if (child != null) {
                        extractFromPath(child, remaining, newPath, rule, extractedValues);
                    }
                } else {
                    // Leaf node
                    Object value = section.get(key);
                    processValue(newPath, value, rule, extractedValues);
                }
            }
        } else {
            // Specific key
            if (remaining != null) {
                ConfigurationSection child = section.getConfigurationSection(current);
                if (child != null) {
                    extractFromPath(child, remaining, currentPath, rule, extractedValues);
                }
            } else {
                // Leaf node
                Object value = section.get(current);
                processValue(currentPath, value, rule, extractedValues);
            }
        }
    }

    /**
     * Processes a value and extracts translatable content.
     */
    private void processValue(
            @NotNull String key,
            @NotNull Object value,
            @NotNull TranslationSchema.ScanRule rule,
            @NotNull Map<String, String> extractedValues
    ) {
        if (value instanceof String str) {
            // Simple string value
            extractedValues.put(key, str);

        } else if (value instanceof List<?> list) {
            // Check if it's a list of actions or a multiline lore
            if (!rule.getActionFilter().isEmpty()) {
                // Extract translatable actions
                for (Object item : list) {
                    if (item instanceof String action) {
                        extractTranslatableAction(key, action, rule, extractedValues);
                    }
                }
            } else {
                // Treat as multiline lore
                StringBuilder combined = new StringBuilder();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) combined.append("\\n");
                    combined.append(list.get(i).toString());
                }
                extractedValues.put(key, combined.toString());
            }
        }
    }

    /**
     * Extracts translatable content from action string.
     */
    private void extractTranslatableAction(
            @NotNull String key,
            @NotNull String action,
            @NotNull TranslationSchema.ScanRule rule,
            @NotNull Map<String, String> extractedValues
    ) {
        // Parse action format: [type] content
        if (!action.startsWith("[")) {
            return;
        }

        int closeBracket = action.indexOf(']');
        if (closeBracket == -1) {
            return;
        }

        String actionType = action.substring(1, closeBracket).trim();
        String content = action.substring(closeBracket + 1).trim();

        // Check if action type is in filter
        if (rule.getActionFilter().contains(actionType)) {
            String actionKey = key + ".action." + actionType;
            extractedValues.put(actionKey, content);
        }
    }
}
