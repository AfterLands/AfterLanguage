package com.afterlands.afterlanguage.core.service;

import com.afterlands.afterlanguage.api.model.PluralCategory;
import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.afterlanguage.api.service.DynamicContentAPI;
import com.afterlands.afterlanguage.core.cache.TranslationCache;
import com.afterlands.afterlanguage.core.io.TranslationExporter;
import com.afterlands.afterlanguage.core.io.TranslationImporter;
import com.afterlands.afterlanguage.core.resolver.TranslationRegistry;
import com.afterlands.afterlanguage.infra.event.TranslationCreatedEvent;
import com.afterlands.afterlanguage.infra.event.TranslationDeletedEvent;
import com.afterlands.afterlanguage.infra.event.TranslationUpdatedEvent;
import com.afterlands.afterlanguage.infra.persistence.DynamicTranslationRepository;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Implementation of {@link DynamicContentAPI} (v1.2.0).
 *
 * <p>Coordinates database operations, cache invalidation, registry updates,
 * and event firing for dynamic translation management.</p>
 *
 * <h3>Operation Flow:</h3>
 * <ol>
 *     <li>Validate input parameters</li>
 *     <li>Execute database operation via {@link DynamicTranslationRepository}</li>
 *     <li>Update {@link TranslationRegistry} if needed</li>
 *     <li>Invalidate caches via {@link TranslationCache}</li>
 *     <li>Fire Bukkit events (Created/Updated/Deleted)</li>
 * </ol>
 *
 * <h3>Thread Safety:</h3>
 * <p>All database operations are async. Events are fired synchronously
 * on the main thread via Bukkit.getScheduler().runTask().</p>
 *
 * @since 1.2.0
 */
public class DynamicContentAPIImpl implements DynamicContentAPI {

    private final DynamicTranslationRepository repository;
    private final TranslationRegistry registry;
    private final TranslationCache cache;
    private final Logger logger;
    private final boolean debug;

    public DynamicContentAPIImpl(
            @NotNull DynamicTranslationRepository repository,
            @NotNull TranslationRegistry registry,
            @NotNull TranslationCache cache,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.debug = debug;
    }

    // ══════════════════════════════════════════════
    // CREATE OPERATIONS
    // ══════════════════════════════════════════════

    @Override
    @NotNull
    public CompletableFuture<Void> createTranslation(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String text
    ) {
        validateParams(namespace, key, language);
        Objects.requireNonNull(text, "text cannot be null");

        Translation translation = Translation.of(namespace, key, language, text);

        return repository.save(translation)
                .thenRun(() -> {
                    // Update registry
                    registry.register(translation);

                    // Invalidate cache
                    cache.invalidateNamespace(namespace);

                    // Fire event
                    fireEventAsync(new TranslationCreatedEvent(translation, "api"));

                    if (debug) {
                        logger.info("[DynamicContentAPI] Created translation: " + translation.fullKey() +
                                   " [" + language + "]");
                    }
                });
    }

    @Override
    @NotNull
    public CompletableFuture<Void> createTranslationWithPlurals(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull Map<PluralCategory, String> pluralForms
    ) {
        validateParams(namespace, key, language);
        Objects.requireNonNull(pluralForms, "pluralForms cannot be null");

        if (!pluralForms.containsKey(PluralCategory.OTHER)) {
            throw new IllegalArgumentException("pluralForms must contain at least OTHER category");
        }

        Translation translation = Translation.withPluralForms(namespace, key, language, pluralForms);

        return repository.save(translation)
                .thenRun(() -> {
                    // Update registry
                    registry.register(translation);

                    // Invalidate cache
                    cache.invalidateNamespace(namespace);

                    // Fire event
                    fireEventAsync(new TranslationCreatedEvent(translation, "api"));

                    if (debug) {
                        logger.info("[DynamicContentAPI] Created plural translation: " + translation.fullKey() +
                                   " [" + language + "] with " + pluralForms.size() + " forms");
                    }
                });
    }

    // ══════════════════════════════════════════════
    // READ OPERATIONS
    // ══════════════════════════════════════════════

    @Override
    @NotNull
    public CompletableFuture<Optional<Translation>> getTranslation(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    ) {
        validateParams(namespace, key, language);
        return repository.get(namespace, key, language);
    }

    @Override
    @NotNull
    public CompletableFuture<List<Translation>> getAllTranslations(@NotNull String namespace) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        return repository.getNamespace(namespace);
    }

    @Override
    @NotNull
    public CompletableFuture<List<Translation>> getTranslationsByLanguage(
            @NotNull String namespace,
            @NotNull String language
    ) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(language, "language cannot be null");
        return repository.getAllByLanguage(namespace, language);
    }

    @Override
    @NotNull
    public CompletableFuture<Integer> countTranslations(@NotNull String namespace) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        return repository.count(namespace);
    }

    @Override
    @NotNull
    public CompletableFuture<Boolean> translationExists(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    ) {
        validateParams(namespace, key, language);
        return repository.exists(namespace, key, language);
    }

    // ══════════════════════════════════════════════
    // UPDATE OPERATIONS
    // ══════════════════════════════════════════════

    @Override
    @NotNull
    public CompletableFuture<Void> updateTranslation(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String newText
    ) {
        validateParams(namespace, key, language);
        Objects.requireNonNull(newText, "newText cannot be null");

        // Get old translation (optional)
        return repository.get(namespace, key, language)
                .thenCompose(oldOpt -> {
                    Translation newTranslation = Translation.of(namespace, key, language, newText);

                    return repository.save(newTranslation)
                            .thenRun(() -> {
                                // Update registry
                                registry.register(newTranslation);

                                // Invalidate cache
                                cache.invalidateNamespace(namespace);

                                // Fire event
                                fireEventAsync(new TranslationUpdatedEvent(
                                        oldOpt.orElse(null),
                                        newTranslation,
                                        "api"
                                ));

                                if (debug) {
                                    logger.info("[DynamicContentAPI] Updated translation: " +
                                               newTranslation.fullKey() + " [" + language + "]");
                                }
                            });
                });
    }

    @Override
    @NotNull
    public CompletableFuture<Void> updateTranslationPlurals(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull Map<PluralCategory, String> pluralForms
    ) {
        validateParams(namespace, key, language);
        Objects.requireNonNull(pluralForms, "pluralForms cannot be null");

        if (!pluralForms.containsKey(PluralCategory.OTHER)) {
            throw new IllegalArgumentException("pluralForms must contain at least OTHER category");
        }

        // Get old translation (optional)
        return repository.get(namespace, key, language)
                .thenCompose(oldOpt -> {
                    Translation newTranslation = Translation.withPluralForms(namespace, key, language, pluralForms);

                    return repository.save(newTranslation)
                            .thenRun(() -> {
                                // Update registry
                                registry.register(newTranslation);

                                // Invalidate cache
                                cache.invalidateNamespace(namespace);

                                // Fire event
                                fireEventAsync(new TranslationUpdatedEvent(
                                        oldOpt.orElse(null),
                                        newTranslation,
                                        "api"
                                ));

                                if (debug) {
                                    logger.info("[DynamicContentAPI] Updated plural translation: " +
                                               newTranslation.fullKey() + " [" + language + "]");
                                }
                            });
                });
    }

    // ══════════════════════════════════════════════
    // DELETE OPERATIONS
    // ══════════════════════════════════════════════

    @Override
    @NotNull
    public CompletableFuture<Boolean> deleteTranslation(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    ) {
        validateParams(namespace, key, language);

        // Get translation before deleting (for event)
        return repository.get(namespace, key, language)
                .thenCompose(opt -> {
                    return repository.delete(namespace, key, language)
                            .thenApply(deleted -> {
                                if (deleted) {
                                    // Remove from registry
                                    registry.unregister(namespace, key, language);

                                    // Invalidate cache
                                    cache.invalidateNamespace(namespace);

                                    // Fire event
                                    if (opt.isPresent()) {
                                        fireEventAsync(new TranslationDeletedEvent(opt.get(), "api"));
                                    } else {
                                        fireEventAsync(new TranslationDeletedEvent(namespace, key, language, "api"));
                                    }

                                    if (debug) {
                                        logger.info("[DynamicContentAPI] Deleted translation: " +
                                                   namespace + ":" + key + " [" + language + "]");
                                    }
                                }
                                return deleted;
                            });
                });
    }

    @Override
    @NotNull
    public CompletableFuture<Integer> deleteAllTranslations(@NotNull String namespace) {
        Objects.requireNonNull(namespace, "namespace cannot be null");

        return repository.deleteNamespace(namespace)
                .thenApply(count -> {
                    if (count > 0) {
                        // Clear registry for namespace
                        registry.clearNamespace(namespace);

                        // Invalidate cache
                        cache.invalidateNamespace(namespace);

                        if (debug) {
                            logger.info("[DynamicContentAPI] Deleted " + count +
                                       " translations for namespace: " + namespace);
                        }
                    }
                    return count;
                });
    }

    // ══════════════════════════════════════════════
    // CACHE MANAGEMENT
    // ══════════════════════════════════════════════

    @Override
    public void invalidateCache(@NotNull String namespace) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        cache.invalidateNamespace(namespace);

        if (debug) {
            logger.fine("[DynamicContentAPI] Invalidated cache for namespace: " + namespace);
        }
    }

    @Override
    @NotNull
    public CompletableFuture<Void> reloadNamespace(@NotNull String namespace) {
        Objects.requireNonNull(namespace, "namespace cannot be null");

        return repository.getNamespace(namespace)
                .thenAccept(translations -> {
                    // Clear existing data
                    registry.clearNamespace(namespace);
                    cache.invalidateNamespace(namespace);

                    // Re-register all translations
                    for (Translation translation : translations) {
                        registry.register(translation);
                    }

                    if (debug) {
                        logger.info("[DynamicContentAPI] Reloaded namespace " + namespace +
                                   " with " + translations.size() + " translations");
                    }
                });
    }

    // ══════════════════════════════════════════════
    // EXPORT/IMPORT OPERATIONS
    // ══════════════════════════════════════════════

    @Override
    @NotNull
    public CompletableFuture<Integer> exportNamespace(
            @NotNull String namespace,
            @NotNull Path outputDir
    ) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(outputDir, "outputDir cannot be null");

        return repository.getNamespace(namespace)
                .thenApply(translations -> {
                    try {
                        TranslationExporter exporter = new TranslationExporter(logger, debug);
                        TranslationExporter.ExportResult result = exporter.exportNamespace(
                                namespace,
                                translations,
                                outputDir
                        );

                        if (debug) {
                            logger.info("[DynamicContentAPI] Exported " + result.exportedCount() +
                                       " translations for namespace " + namespace);
                        }

                        return result.exportedCount();

                    } catch (Exception e) {
                        logger.severe("[DynamicContentAPI] Failed to export namespace " +
                                     namespace + ": " + e.getMessage());
                        throw new RuntimeException("Export failed", e);
                    }
                });
    }

    @Override
    @NotNull
    public CompletableFuture<ImportResult> importTranslations(
            @NotNull Path file,
            @NotNull String namespace,
            @NotNull String language,
            boolean overwrite
    ) {
        Objects.requireNonNull(file, "file cannot be null");
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(language, "language cannot be null");

        TranslationImporter importer = new TranslationImporter(repository, logger, debug);

        return importer.importFromFile(file, namespace, language, overwrite)
                .thenApply(result -> {
                    // Reload namespace to update registry and cache
                    reloadNamespace(namespace).join();

                    if (debug) {
                        logger.info("[DynamicContentAPI] Imported " + result.importedCount() +
                                   " translations (skipped " + result.skippedCount() + ") for namespace " +
                                   namespace + " [" + language + "]");
                    }

                    return new ImportResult(result.importedCount(), result.skippedCount());
                });
    }

    // ══════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════

    /**
     * Validates common parameters.
     */
    private void validateParams(@NotNull String namespace, @NotNull String key, @NotNull String language) {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(language, "language cannot be null");

        if (namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace cannot be empty");
        }
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key cannot be empty");
        }
        if (language.isEmpty()) {
            throw new IllegalArgumentException("language cannot be empty");
        }
    }

    /**
     * Fires a Bukkit event asynchronously (on main thread).
     *
     * <p>Uses Bukkit scheduler to run on main thread if needed.</p>
     */
    private void fireEventAsync(@NotNull org.bukkit.event.Event event) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            Bukkit.getScheduler().runTask(
                    Bukkit.getPluginManager().getPlugin("AfterLanguage"),
                    () -> Bukkit.getPluginManager().callEvent(event)
            );
        }
    }
}
