package com.afterlands.afterlanguage.api.service;

import com.afterlands.afterlanguage.api.model.PluralCategory;
import com.afterlands.afterlanguage.api.model.Translation;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for managing dynamic runtime translations (v1.2.0).
 *
 * <p>This API allows plugins to create, update, query, and delete translations
 * at runtime without requiring file edits or server restarts.</p>
 *
 * <h3>Key Features:</h3>
 * <ul>
 *     <li>CRUD operations for translations</li>
 *     <li>Support for ICU-like plural forms (ZERO, ONE, TWO, FEW, MANY, OTHER)</li>
 *     <li>Namespace-based organization</li>
 *     <li>Async operations via CompletableFuture</li>
 *     <li>Automatic cache invalidation</li>
 *     <li>Event firing (TranslationCreated/Updated/DeletedEvent)</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Get API instance
 * DynamicContentAPI api = AfterLanguage.getDynamicContentAPI();
 *
 * // Create a simple translation
 * api.createTranslation("myplugin", "welcome", "en_us", "Welcome to the server!")
 *    .thenRun(() -> logger.info("Translation created!"));
 *
 * // Create translation with plural forms
 * Map<PluralCategory, String> plurals = Map.of(
 *     PluralCategory.ONE, "1 item",
 *     PluralCategory.OTHER, "{count} items"
 * );
 * api.createTranslationWithPlurals("myplugin", "items", "en_us", plurals)
 *    .thenRun(() -> logger.info("Plural translation created!"));
 *
 * // Query translations
 * api.getTranslation("myplugin", "welcome", "en_us")
 *    .thenAccept(opt -> opt.ifPresent(t -> logger.info("Found: " + t.text())));
 *
 * // List all translations for a namespace
 * api.getAllTranslations("myplugin")
 *    .thenAccept(list -> logger.info("Total: " + list.size()));
 * }</pre>
 *
 * <h3>Thread Safety:</h3>
 * <p>All methods are thread-safe and return CompletableFuture for async execution.
 * Database operations are executed on AfterCore's database thread pool.</p>
 *
 * <h3>Cache Invalidation:</h3>
 * <p>Creating, updating, or deleting translations automatically invalidates
 * the affected cache entries in the three-tier caching system.</p>
 *
 * @since 1.2.0
 * @author AfterLands Team
 */
public interface DynamicContentAPI {

    // ══════════════════════════════════════════════
    // CREATE OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Creates a new simple translation.
     *
     * <p>If a translation with the same namespace, key, and language already exists,
     * it will be updated instead (UPSERT behavior).</p>
     *
     * @param namespace Plugin namespace (e.g., "myplugin")
     * @param key Translation key (e.g., "welcome")
     * @param language Language code (e.g., "en_us", "pt_br")
     * @param text Translation text
     * @return CompletableFuture that completes when saved
     * @throws IllegalArgumentException if any parameter is null or empty
     */
    @NotNull
    CompletableFuture<Void> createTranslation(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String text
    );

    /**
     * Creates a translation with ICU-like plural forms.
     *
     * <p>The pluralForms map must contain at least the {@link PluralCategory#OTHER} key.</p>
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * Map<PluralCategory, String> plurals = Map.of(
     *     PluralCategory.ONE, "1 item",
     *     PluralCategory.OTHER, "{count} items"
     * );
     * api.createTranslationWithPlurals("shop", "items", "en_us", plurals);
     * }</pre>
     *
     * @param namespace Plugin namespace
     * @param key Translation key
     * @param language Language code
     * @param pluralForms Map of plural categories to text (must include OTHER)
     * @return CompletableFuture that completes when saved
     * @throws IllegalArgumentException if pluralForms doesn't contain OTHER
     */
    @NotNull
    CompletableFuture<Void> createTranslationWithPlurals(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull Map<PluralCategory, String> pluralForms
    );

    // ══════════════════════════════════════════════
    // READ OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Gets a single translation.
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @return CompletableFuture with Optional of Translation (empty if not found)
     */
    @NotNull
    CompletableFuture<Optional<Translation>> getTranslation(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    );

    /**
     * Gets all translations for a namespace (all languages).
     *
     * @param namespace Namespace
     * @return CompletableFuture with list of translations
     */
    @NotNull
    CompletableFuture<List<Translation>> getAllTranslations(@NotNull String namespace);

    /**
     * Gets all translations for a namespace in a specific language.
     *
     * @param namespace Namespace
     * @param language Language code
     * @return CompletableFuture with list of translations
     */
    @NotNull
    CompletableFuture<List<Translation>> getTranslationsByLanguage(
            @NotNull String namespace,
            @NotNull String language
    );

    /**
     * Counts translations in a namespace.
     *
     * @param namespace Namespace
     * @return CompletableFuture with count
     */
    @NotNull
    CompletableFuture<Integer> countTranslations(@NotNull String namespace);

    /**
     * Checks if a translation exists.
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @return CompletableFuture with true if exists
     */
    @NotNull
    CompletableFuture<Boolean> translationExists(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    );

    // ══════════════════════════════════════════════
    // UPDATE OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Updates the text of an existing translation.
     *
     * <p>If the translation doesn't exist, it will be created (UPSERT).</p>
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @param newText New text
     * @return CompletableFuture that completes when updated
     */
    @NotNull
    CompletableFuture<Void> updateTranslation(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String newText
    );

    /**
     * Updates the plural forms of an existing translation.
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @param pluralForms New plural forms (must include OTHER)
     * @return CompletableFuture that completes when updated
     */
    @NotNull
    CompletableFuture<Void> updateTranslationPlurals(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull Map<PluralCategory, String> pluralForms
    );

    // ══════════════════════════════════════════════
    // DELETE OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Deletes a single translation.
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @return CompletableFuture with true if deleted, false if not found
     */
    @NotNull
    CompletableFuture<Boolean> deleteTranslation(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    );

    /**
     * Deletes all translations for a namespace.
     *
     * <p>Use with caution - this deletes all dynamic translations for the namespace.</p>
     *
     * @param namespace Namespace
     * @return CompletableFuture with number of deleted translations
     */
    @NotNull
    CompletableFuture<Integer> deleteAllTranslations(@NotNull String namespace);

    // ══════════════════════════════════════════════
    // CACHE MANAGEMENT
    // ══════════════════════════════════════════════

    /**
     * Manually invalidates cache for a namespace.
     *
     * <p>This is automatically called after create/update/delete operations,
     * but can be called manually if needed.</p>
     *
     * @param namespace Namespace to invalidate
     */
    void invalidateCache(@NotNull String namespace);

    /**
     * Reloads all dynamic translations from database into registry.
     *
     * <p>This also invalidates all caches for the namespace.</p>
     *
     * @param namespace Namespace to reload
     * @return CompletableFuture that completes when reloaded
     */
    @NotNull
    CompletableFuture<Void> reloadNamespace(@NotNull String namespace);

    // ══════════════════════════════════════════════
    // EXPORT/IMPORT OPERATIONS (v1.2.0)
    // ══════════════════════════════════════════════

    /**
     * Exports all translations for a namespace to YAML files.
     *
     * <p>Creates YAML files organized by language in the output directory.</p>
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * Path exportDir = Paths.get("exports");
     * api.exportNamespace("myplugin", exportDir)
     *    .thenAccept(count -> logger.info("Exported " + count + " translations"));
     * }</pre>
     *
     * @param namespace Namespace to export
     * @param outputDir Output directory path
     * @return CompletableFuture with number of exported translations
     */
    @NotNull
    CompletableFuture<Integer> exportNamespace(
            @NotNull String namespace,
            @NotNull java.nio.file.Path outputDir
    );

    /**
     * Imports translations from a YAML file into the database.
     *
     * <p>Supports both simple translations and plural forms.</p>
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * Path yamlFile = Paths.get("translations.yml");
     * api.importTranslations(yamlFile, "myplugin", "pt_br", true)
     *    .thenAccept(result -> logger.info("Imported: " + result.imported() + ", Skipped: " + result.skipped()));
     * }</pre>
     *
     * @param file YAML file to import from
     * @param namespace Target namespace
     * @param language Target language code
     * @param overwrite If true, overwrites existing translations
     * @return CompletableFuture with import result (imported count, skipped count)
     */
    @NotNull
    CompletableFuture<ImportResult> importTranslations(
            @NotNull java.nio.file.Path file,
            @NotNull String namespace,
            @NotNull String language,
            boolean overwrite
    );

    /**
     * Result of an import operation.
     *
     * @param imported Number of translations imported
     * @param skipped Number of translations skipped (already exist)
     */
    record ImportResult(int imported, int skipped) {
        public int total() {
            return imported + skipped;
        }
    }
}
