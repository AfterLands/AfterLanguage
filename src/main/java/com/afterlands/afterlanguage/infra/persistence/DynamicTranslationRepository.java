package com.afterlands.afterlanguage.infra.persistence;

import com.afterlands.afterlanguage.api.model.PluralCategory;
import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.core.database.SqlDataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Repository for dynamically created translations (v1.2.0 - Full CRUD).
 *
 * <p>Manages runtime translations with support for ICU-like plural forms.
 * All operations are async via CompletableFuture.</p>
 *
 * <h3>Database Schema:</h3>
 * <pre>
 * CREATE TABLE afterlanguage_dynamic_translations (
 *     id INT AUTO_INCREMENT PRIMARY KEY,
 *     namespace VARCHAR(64) NOT NULL,
 *     translation_key VARCHAR(128) NOT NULL,
 *     language VARCHAR(10) NOT NULL,
 *     text TEXT NOT NULL,
 *     plural_text TEXT,           -- Legacy (deprecated)
 *     plural_zero TEXT,            -- v1.2.0
 *     plural_one TEXT,             -- v1.2.0
 *     plural_two TEXT,             -- v1.2.0
 *     plural_few TEXT,             -- v1.2.0
 *     plural_many TEXT,            -- v1.2.0
 *     plural_other TEXT,           -- v1.2.0
 *     source VARCHAR(16) DEFAULT 'manual',
 *     status VARCHAR(16) DEFAULT 'pending',
 *     created_at TIMESTAMP NOT NULL,
 *     updated_at TIMESTAMP NOT NULL,
 *     UNIQUE KEY uk_translation (namespace, translation_key, language)
 * );
 * </pre>
 *
 * <h3>Operations:</h3>
 * <ul>
 *     <li>save() - Insert or update translation</li>
 *     <li>get() - Get single translation</li>
 *     <li>getNamespace() - Get all translations for a namespace</li>
 *     <li>getAllByLanguage() - Get all translations for a namespace and language</li>
 *     <li>delete() - Delete single translation</li>
 *     <li>deleteNamespace() - Delete all translations for a namespace</li>
 *     <li>count() - Count translations in namespace</li>
 *     <li>exists() - Check if translation exists</li>
 * </ul>
 */
public class DynamicTranslationRepository {

    private final SqlDataSource dataSource;
    private final Logger logger;
    private final String tableName;
    private final boolean debug;

    public DynamicTranslationRepository(
            @NotNull SqlDataSource dataSource,
            @NotNull String tableName,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Gets all dynamic translations for a namespace.
     *
     * @param namespace Namespace
     * @return List of translations
     */
    @NotNull
    public CompletableFuture<List<Translation>> getNamespace(@NotNull String namespace) {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT namespace, translation_key, language, text, plural_text, " +
                        "plural_zero, plural_one, plural_two, plural_few, plural_many, plural_other, " +
                        "updated_at FROM " + tableName + " WHERE namespace = ?";
            List<Translation> translations = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        translations.add(mapRowToTranslation(rs));
                    }
                }
            }

            if (debug) {
                logger.fine("[DynamicTranslationRepo] Loaded " + translations.size() +
                           " translations for namespace: " + namespace);
            }

            return translations;
        });
    }

    /**
     * Gets a single translation by key and language.
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @return Translation or empty if not found
     */
    @NotNull
    public CompletableFuture<Optional<Translation>> get(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    ) {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT namespace, translation_key, language, text, plural_text, " +
                        "plural_zero, plural_one, plural_two, plural_few, plural_many, plural_other, " +
                        "updated_at FROM " + tableName +
                        " WHERE namespace = ? AND translation_key = ? AND language = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);
                ps.setString(2, key);
                ps.setString(3, language);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRowToTranslation(rs));
                    }
                }
            }

            return Optional.empty();
        });
    }

    /**
     * Gets all translations for a namespace and language.
     *
     * @param namespace Namespace
     * @param language Language code
     * @return List of translations
     */
    @NotNull
    public CompletableFuture<List<Translation>> getAllByLanguage(
            @NotNull String namespace,
            @NotNull String language
    ) {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT namespace, translation_key, language, text, plural_text, " +
                        "plural_zero, plural_one, plural_two, plural_few, plural_many, plural_other, " +
                        "updated_at FROM " + tableName +
                        " WHERE namespace = ? AND language = ?";
            List<Translation> translations = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);
                ps.setString(2, language);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        translations.add(mapRowToTranslation(rs));
                    }
                }
            }

            return translations;
        });
    }

    /**
     * Saves (inserts or updates) a dynamic translation.
     *
     * <p>Uses UPSERT (INSERT ... ON DUPLICATE KEY UPDATE) for atomicity.</p>
     *
     * @param translation Translation to save
     * @return CompletableFuture that completes when saved
     */
    @NotNull
    public CompletableFuture<Void> save(@NotNull Translation translation) {
        return dataSource.runAsync(conn -> {
            String sql = """
                INSERT INTO %s (namespace, translation_key, language, text, plural_text,
                               plural_zero, plural_one, plural_two, plural_few, plural_many, plural_other,
                               source, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'dynamic', 'approved', ?, ?)
                ON DUPLICATE KEY UPDATE
                    text = VALUES(text),
                    plural_text = VALUES(plural_text),
                    plural_zero = VALUES(plural_zero),
                    plural_one = VALUES(plural_one),
                    plural_two = VALUES(plural_two),
                    plural_few = VALUES(plural_few),
                    plural_many = VALUES(plural_many),
                    plural_other = VALUES(plural_other),
                    updated_at = VALUES(updated_at)
                """.formatted(tableName);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, translation.namespace());
                ps.setString(2, translation.key());
                ps.setString(3, translation.language());
                ps.setString(4, translation.text());
                ps.setString(5, translation.pluralText()); // Legacy

                // Plural forms (v1.2.0)
                Map<PluralCategory, String> pluralForms = translation.pluralForms();
                ps.setString(6, pluralForms != null ? pluralForms.get(PluralCategory.ZERO) : null);
                ps.setString(7, pluralForms != null ? pluralForms.get(PluralCategory.ONE) : null);
                ps.setString(8, pluralForms != null ? pluralForms.get(PluralCategory.TWO) : null);
                ps.setString(9, pluralForms != null ? pluralForms.get(PluralCategory.FEW) : null);
                ps.setString(10, pluralForms != null ? pluralForms.get(PluralCategory.MANY) : null);
                ps.setString(11, pluralForms != null ? pluralForms.get(PluralCategory.OTHER) : null);

                Timestamp now = Timestamp.from(Instant.now());
                ps.setTimestamp(12, now);
                ps.setTimestamp(13, now);

                int affected = ps.executeUpdate();

                if (debug) {
                    logger.info("[DynamicTranslationRepo] Saved translation: " +
                               translation.namespace() + ":" + translation.key() +
                               " [" + translation.language() + "] (affected: " + affected + ")");
                }
            }
        });
    }

    /**
     * Deletes a single translation.
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @return CompletableFuture that completes when deleted
     */
    @NotNull
    public CompletableFuture<Boolean> delete(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    ) {
        return dataSource.supplyAsync(conn -> {
            String sql = "DELETE FROM " + tableName +
                        " WHERE namespace = ? AND translation_key = ? AND language = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);
                ps.setString(2, key);
                ps.setString(3, language);

                int affected = ps.executeUpdate();

                if (debug && affected > 0) {
                    logger.info("[DynamicTranslationRepo] Deleted translation: " +
                               namespace + ":" + key + " [" + language + "]");
                }

                return affected > 0;
            }
        });
    }

    /**
     * Deletes all translations for a namespace.
     *
     * @param namespace Namespace
     * @return CompletableFuture with number of deleted rows
     */
    @NotNull
    public CompletableFuture<Integer> deleteNamespace(@NotNull String namespace) {
        return dataSource.supplyAsync(conn -> {
            String sql = "DELETE FROM " + tableName + " WHERE namespace = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);

                int affected = ps.executeUpdate();

                if (debug) {
                    logger.info("[DynamicTranslationRepo] Deleted " + affected +
                               " translations for namespace: " + namespace);
                }

                return affected;
            }
        });
    }

    /**
     * Counts translations in a namespace.
     *
     * @param namespace Namespace
     * @return CompletableFuture with count
     */
    @NotNull
    public CompletableFuture<Integer> count(@NotNull String namespace) {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE namespace = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            }

            return 0;
        });
    }

    /**
     * Checks if a translation exists.
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @return CompletableFuture with true if exists
     */
    @NotNull
    public CompletableFuture<Boolean> exists(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    ) {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT 1 FROM " + tableName +
                        " WHERE namespace = ? AND translation_key = ? AND language = ? LIMIT 1";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);
                ps.setString(2, key);
                ps.setString(3, language);

                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * Maps a ResultSet row to a Translation object.
     *
     * <p>Handles both legacy plural_text and new plural forms columns.</p>
     *
     * @param rs ResultSet positioned at a row
     * @return Translation object
     * @throws Exception if mapping fails
     */
    @NotNull
    private Translation mapRowToTranslation(@NotNull ResultSet rs) throws Exception {
        String namespace = rs.getString("namespace");
        String key = rs.getString("translation_key");
        String language = rs.getString("language");
        String text = rs.getString("text");
        String pluralText = rs.getString("plural_text");
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

        // Check if plural forms are present (v1.2.0)
        Map<PluralCategory, String> pluralForms = null;

        String pluralZero = rs.getString("plural_zero");
        String pluralOne = rs.getString("plural_one");
        String pluralTwo = rs.getString("plural_two");
        String pluralFew = rs.getString("plural_few");
        String pluralMany = rs.getString("plural_many");
        String pluralOther = rs.getString("plural_other");

        // If any plural form column is non-null, build the map
        if (pluralZero != null || pluralOne != null || pluralTwo != null ||
            pluralFew != null || pluralMany != null || pluralOther != null) {

            pluralForms = new HashMap<>();
            if (pluralZero != null) pluralForms.put(PluralCategory.ZERO, pluralZero);
            if (pluralOne != null) pluralForms.put(PluralCategory.ONE, pluralOne);
            if (pluralTwo != null) pluralForms.put(PluralCategory.TWO, pluralTwo);
            if (pluralFew != null) pluralForms.put(PluralCategory.FEW, pluralFew);
            if (pluralMany != null) pluralForms.put(PluralCategory.MANY, pluralMany);
            if (pluralOther != null) pluralForms.put(PluralCategory.OTHER, pluralOther);

            // Ensure OTHER is always present
            if (!pluralForms.containsKey(PluralCategory.OTHER)) {
                pluralForms.put(PluralCategory.OTHER, text);
            }
        }

        return new Translation(
                namespace,
                key,
                language,
                text,
                pluralText, // Legacy
                pluralForms,
                updatedAt,
                null // No source hash for dynamic translations
        );
    }

    // ══════════════════════════════════════════════
    // CROWDIN SYNC METHODS (v1.3.0)
    // ══════════════════════════════════════════════

    /**
     * Updates the sync status for a translation.
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @param syncStatus New sync status (pending, synced, conflict, error)
     * @return CompletableFuture that completes when updated
     */
    @NotNull
    public CompletableFuture<Void> updateSyncStatus(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String syncStatus
    ) {
        return dataSource.runAsync(conn -> {
            String sql = "UPDATE " + tableName +
                        " SET sync_status = ?, updated_at = ? " +
                        "WHERE namespace = ? AND translation_key = ? AND language = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, syncStatus);
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.setString(3, namespace);
                ps.setString(4, key);
                ps.setString(5, language);

                int affected = ps.executeUpdate();

                if (debug && affected > 0) {
                    logger.fine("[DynamicTranslationRepo] Updated sync_status: " +
                               namespace + ":" + key + " [" + language + "] -> " + syncStatus);
                }
            }
        });
    }

    /**
     * Updates the Crowdin hash for a translation.
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @param crowdinHash MD5 hash of the text
     * @return CompletableFuture that completes when updated
     */
    @NotNull
    public CompletableFuture<Void> updateCrowdinHash(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String crowdinHash
    ) {
        return dataSource.runAsync(conn -> {
            String sql = "UPDATE " + tableName +
                        " SET crowdin_hash = ?, sync_status = 'synced', last_synced_at = ? " +
                        "WHERE namespace = ? AND translation_key = ? AND language = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, crowdinHash);
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.setString(3, namespace);
                ps.setString(4, key);
                ps.setString(5, language);

                ps.executeUpdate();
            }
        });
    }

    /**
     * Finds translations pending sync for a namespace.
     *
     * @param namespace Namespace
     * @return CompletableFuture with list of pending translations
     */
    @NotNull
    public CompletableFuture<List<Translation>> findPendingSync(@NotNull String namespace) {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT namespace, translation_key, language, text, plural_text, " +
                        "plural_zero, plural_one, plural_two, plural_few, plural_many, plural_other, " +
                        "updated_at FROM " + tableName +
                        " WHERE namespace = ? AND sync_status = 'pending'";
            List<Translation> translations = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        translations.add(mapRowToTranslation(rs));
                    }
                }
            }

            return translations;
        });
    }

    /**
     * Finds translations by sync status for a namespace.
     *
     * @param namespace Namespace
     * @param syncStatus Sync status filter
     * @return CompletableFuture with list of translations
     */
    @NotNull
    public CompletableFuture<List<Translation>> findByNamespaceAndSyncStatus(
            @NotNull String namespace,
            @NotNull String syncStatus
    ) {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT namespace, translation_key, language, text, plural_text, " +
                        "plural_zero, plural_one, plural_two, plural_few, plural_many, plural_other, " +
                        "updated_at FROM " + tableName +
                        " WHERE namespace = ? AND sync_status = ?";
            List<Translation> translations = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);
                ps.setString(2, syncStatus);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        translations.add(mapRowToTranslation(rs));
                    }
                }
            }

            return translations;
        });
    }

    /**
     * Gets stored Crowdin hashes for a namespace.
     *
     * @param namespace Namespace
     * @return CompletableFuture with map of fullKey -> crowdin_hash
     */
    @NotNull
    public CompletableFuture<Map<String, String>> getCrowdinHashes(@NotNull String namespace) {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT namespace, translation_key, crowdin_hash FROM " + tableName +
                        " WHERE namespace = ? AND crowdin_hash IS NOT NULL";
            Map<String, String> hashes = new HashMap<>();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String ns = rs.getString("namespace");
                        String key = rs.getString("translation_key");
                        String hash = rs.getString("crowdin_hash");
                        hashes.put(ns + ":" + key, hash);
                    }
                }
            }

            return hashes;
        });
    }

    /**
     * Batch updates sync status after successful sync.
     *
     * @param namespace Namespace
     * @param keys List of translation keys
     * @param syncStatus New sync status
     * @return CompletableFuture that completes when updated
     */
    @NotNull
    public CompletableFuture<Integer> batchUpdateSyncStatus(
            @NotNull String namespace,
            @NotNull List<String> keys,
            @NotNull String syncStatus
    ) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        return dataSource.supplyAsync(conn -> {
            String placeholders = String.join(",", keys.stream().map(k -> "?").toList());
            String sql = "UPDATE " + tableName +
                        " SET sync_status = ?, last_synced_at = ? " +
                        "WHERE namespace = ? AND translation_key IN (" + placeholders + ")";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                ps.setString(idx++, syncStatus);
                ps.setTimestamp(idx++, Timestamp.from(Instant.now()));
                ps.setString(idx++, namespace);
                for (String key : keys) {
                    ps.setString(idx++, key);
                }

                return ps.executeUpdate();
            }
        });
    }
}
