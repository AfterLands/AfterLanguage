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

                // Load translations for ALL languages and reload atomically
                loadAllLanguagesForNamespace(namespace);

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

                // Load translations for ALL languages and reload atomically
                loadAllLanguagesForNamespace(namespace);

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
     * Discovers namespace directories on disk that are not yet registered
     * and registers them (without defaults).
     *
     * <p>Scans {@code languagesDir/<sourceLanguage>/} for directories
     * that are not in {@code registeredNamespaces}.</p>
     */
    public void discoverAndRegisterNewNamespaces() {
        Path sourceLangDir = languagesDir.resolve(sourceLanguage.code());
        if (!Files.exists(sourceLangDir)) {
            return;
        }

        try (Stream<Path> dirs = Files.list(sourceLangDir)) {
            dirs.filter(Files::isDirectory)
                .forEach(dir -> {
                    String name = dir.getFileName().toString();
                    if (!registeredNamespaces.containsKey(name)) {
                        // ConcurrentHashMap does not allow null values, use the dir itself as sentinel
                        registeredNamespaces.put(name, dir);
                        logger.info("[NamespaceManager] Discovered new namespace on disk: " + name);
                    }
                });
        } catch (IOException e) {
            logger.warning("[NamespaceManager] Failed to scan for new namespaces: " + e.getMessage());
        }
    }

    /**
     * Reloads all registered namespaces.
     *
     * <p>Before reloading, discovers any new namespace directories on disk
     * that were not previously registered.</p>
     *
     * @return Future that completes when all namespaces are reloaded
     */
    @NotNull
    public CompletableFuture<Void> reloadAll() {
        // Discover any new namespace directories added to disk since last load
        discoverAndRegisterNewNamespaces();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String namespace : registeredNamespaces.keySet()) {
            futures.add(reloadNamespace(namespace));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Loads translations for ALL languages for a namespace and reloads the registry atomically.
     *
     * <p>Collects translations from all language directories first, then performs
     * a single atomic swap in the registry. This prevents the race condition where
     * loading languages sequentially would cause each reload to wipe previous languages.</p>
     *
     * @param namespace Namespace to load
     */
    private void loadAllLanguagesForNamespace(@NotNull String namespace) {
        List<Translation> allTranslations = new ArrayList<>();

        try (Stream<Path> langDirs = Files.list(languagesDir)) {
            langDirs.filter(Files::isDirectory)
                    .forEach(langDir -> {
                        String langCode = langDir.getFileName().toString();
                        Language lang = new Language(langCode, langCode, langCode.equals(sourceLanguage.code()));

                        Map<String, Translation> translations = yamlLoader.loadNamespace(lang, namespace);

                        if (translations.isEmpty()) {
                            if (debug) {
                                logger.fine("[NamespaceManager] No translations loaded for " + namespace + " [" + langCode + "]");
                            }
                            return;
                        }

                        allTranslations.addAll(translations.values());

                        if (debug) {
                            logger.info("[NamespaceManager] Collected " + translations.size() +
                                       " translations for " + namespace + " [" + langCode + "]");
                        }
                    });
        } catch (IOException e) {
            logger.severe("[NamespaceManager] Failed to list language directories: " + e.getMessage());
            return;
        }

        if (!allTranslations.isEmpty()) {
            // Single atomic swap with ALL languages combined
            registry.reloadNamespace(namespace, allTranslations);

            if (debug) {
                logger.info("[NamespaceManager] Atomically loaded " + allTranslations.size() +
                           " total translations for namespace: " + namespace);
            }
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
