package com.afterlands.afterlanguage.core.resolver;

import com.afterlands.afterlanguage.api.model.Language;
import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.afterlanguage.core.cache.TranslationCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Manages namespace registration, default file provisioning, and hot-reload.
 *
 * <h3>Responsibilities:</h3>
 * <ul>
 *     <li>Register namespaces and copy default files if missing</li>
 *     <li>Load translations via YamlTranslationLoader</li>
 *     <li>Populate TranslationRegistry with atomic snapshot swap</li>
 *     <li>Invalidate L1/L3 caches on reload</li>
 * </ul>
 *
 * <h3>Thread Safety:</h3>
 * <p>All operations are thread-safe. File I/O should be executed async.</p>
 */
public class NamespaceManager {

    private final Path languagesDir;
    private final Language sourceLanguage;
    private final YamlTranslationLoader yamlLoader;
    private final TranslationRegistry registry;
    private final TranslationCache cache;
    private final Logger logger;
    private final boolean debug;

    /**
     * Tracks registered namespaces and their default folders.
     * Map: namespace -> default folder path (for copying on first run)
     */
    private final Map<String, Path> registeredNamespaces = new ConcurrentHashMap<>();

    /**
     * Creates a namespace manager.
     *
     * @param languagesDir Languages directory (plugins/AfterLanguage/languages/)
     * @param sourceLanguage Source language (e.g., pt_br)
     * @param yamlLoader YAML loader
     * @param registry Translation registry (L2)
     * @param cache Translation cache (L1/L3)
     * @param logger Logger
     * @param debug Enable debug logging
     */
    public NamespaceManager(
            @NotNull Path languagesDir,
            @NotNull Language sourceLanguage,
            @NotNull YamlTranslationLoader yamlLoader,
            @NotNull TranslationRegistry registry,
            @NotNull TranslationCache cache,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.languagesDir = Objects.requireNonNull(languagesDir, "languagesDir");
        this.sourceLanguage = Objects.requireNonNull(sourceLanguage, "sourceLanguage");
        this.yamlLoader = Objects.requireNonNull(yamlLoader, "yamlLoader");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.debug = debug;
    }

    /**
     * Registers a namespace with default file provisioning.
     *
     * <p>If {@code languages/<sourceLanguage>/<namespace>/} does not exist,
     * recursively copies from {@code defaultFolder}.</p>
     *
     * <p>Then loads all translations for the source language and populates the registry.</p>
     *
     * @param namespace Namespace identifier (e.g., "afterlanguage", "afterjournal")
     * @param defaultFolder Default folder to copy from (can be in JAR resources or filesystem)
     * @return Future that completes when namespace is loaded
     */
    @NotNull
    public CompletableFuture<Void> registerNamespace(
            @NotNull String namespace,
            @Nullable Path defaultFolder
    ) {
        Objects.requireNonNull(namespace, "namespace");

        if (registeredNamespaces.containsKey(namespace)) {
            if (debug) {
                logger.fine("[NamespaceManager] Namespace already registered: " + namespace);
            }
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                // Track registration
                if (defaultFolder != null) {
                    registeredNamespaces.put(namespace, defaultFolder);
                }

                // Check if namespace directory exists for source language
                Path nsDir = languagesDir.resolve(sourceLanguage.code()).resolve(namespace);

                if (!Files.exists(nsDir)) {
                    if (defaultFolder != null && Files.exists(defaultFolder)) {
                        logger.info("[NamespaceManager] Namespace '" + namespace + "' not found. Copying defaults from: " + defaultFolder);
                        copyDefaults(defaultFolder, nsDir);
                    } else {
                        logger.warning("[NamespaceManager] Namespace '" + namespace + "' not found and no defaults provided. Creating empty directory.");
                        Files.createDirectories(nsDir);
                    }
                }

                // Load translations for source language
                loadNamespaceForLanguage(sourceLanguage, namespace);

                logger.info("[NamespaceManager] Registered namespace: " + namespace);

            } catch (Exception e) {
                logger.severe("[NamespaceManager] Failed to register namespace '" + namespace + "': " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Namespace registration failed", e);
            }
        });
    }

    /**
     * Reloads a namespace from disk.
     *
     * <p>Performs atomic snapshot swap in registry and invalidates L1/L3 caches.</p>
     *
     * @param namespace Namespace to reload
     * @return Future that completes when reload is done
     */
    @NotNull
    public CompletableFuture<Void> reloadNamespace(@NotNull String namespace) {
        Objects.requireNonNull(namespace, "namespace");

        return CompletableFuture.runAsync(() -> {
            try {
                if (debug) {
                    logger.fine("[NamespaceManager] Reloading namespace: " + namespace);
                }

                // Load for source language
                loadNamespaceForLanguage(sourceLanguage, namespace);

                // Invalidate caches (atomic operation)
                cache.invalidateNamespace(namespace);

                logger.info("[NamespaceManager] Reloaded namespace: " + namespace);

            } catch (Exception e) {
                logger.severe("[NamespaceManager] Failed to reload namespace '" + namespace + "': " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Namespace reload failed", e);
            }
        });
    }

    /**
     * Reloads all registered namespaces.
     *
     * @return Future that completes when all namespaces are reloaded
     */
    @NotNull
    public CompletableFuture<Void> reloadAll() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String namespace : registeredNamespaces.keySet()) {
            futures.add(reloadNamespace(namespace));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Loads translations for a specific language and namespace.
     *
     * <p>Uses YamlTranslationLoader and populates TranslationRegistry with atomic swap.</p>
     */
    private void loadNamespaceForLanguage(@NotNull Language language, @NotNull String namespace) {
        Map<String, Translation> translations = yamlLoader.loadNamespace(language, namespace);

        if (translations.isEmpty()) {
            if (debug) {
                logger.fine("[NamespaceManager] No translations loaded for " + namespace + " [" + language.code() + "]");
            }
            return;
        }

        // Convert map keys to list of translations
        List<Translation> translationList = new ArrayList<>(translations.values());

        // Atomic swap in registry
        registry.reloadNamespace(namespace, translationList);

        if (debug) {
            logger.info("[NamespaceManager] Loaded " + translationList.size() +
                       " translations for " + namespace + " [" + language.code() + "]");
        }
    }

    /**
     * Recursively copies default files to target directory.
     *
     * @param source Source directory
     * @param target Target directory
     * @throws IOException If copy fails
     */
    private void copyDefaults(@NotNull Path source, @NotNull Path target) throws IOException {
        if (!Files.exists(source)) {
            logger.warning("[NamespaceManager] Source directory does not exist: " + source);
            return;
        }

        Files.createDirectories(target);

        try (Stream<Path> paths = Files.walk(source)) {
            paths.forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));

                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        if (debug) {
                            logger.fine("[NamespaceManager] Copied: " + sourcePath.getFileName() + " -> " + targetPath);
                        }
                    }
                } catch (IOException e) {
                    logger.warning("[NamespaceManager] Failed to copy " + sourcePath + ": " + e.getMessage());
                }
            });
        }

        logger.info("[NamespaceManager] Copied default files from " + source + " to " + target);
    }

    /**
     * Checks if a namespace is registered.
     *
     * @param namespace Namespace identifier
     * @return true if registered
     */
    public boolean isRegistered(@NotNull String namespace) {
        return registeredNamespaces.containsKey(namespace);
    }

    /**
     * Gets all registered namespace identifiers.
     *
     * @return Set of namespace names
     */
    @NotNull
    public Set<String> getRegisteredNamespaces() {
        return Collections.unmodifiableSet(registeredNamespaces.keySet());
    }

    /**
     * Gets the languages directory path.
     *
     * @return Languages directory
     */
    @NotNull
    public Path getLanguagesDir() {
        return languagesDir;
    }

    /**
     * Gets statistics for a namespace.
     *
     * @param namespace Namespace identifier
     * @return Map with stats (translation_count, etc.)
     */
    @NotNull
    public Map<String, Object> getNamespaceStats(@NotNull String namespace) {
        Map<String, Object> stats = new HashMap<>();

        // Count translations in registry
        int count = registry.getAllForNamespace(namespace, sourceLanguage.code()).size();
        stats.put("translation_count", count);
        stats.put("source_language", sourceLanguage.code());
        stats.put("registered", isRegistered(namespace));

        // Check if directory exists
        Path nsDir = languagesDir.resolve(sourceLanguage.code()).resolve(namespace);
        stats.put("directory_exists", Files.exists(nsDir));

        return stats;
    }
}
