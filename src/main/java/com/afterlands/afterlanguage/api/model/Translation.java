package com.afterlands.afterlanguage.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * A single translation entry.
 *
 * <p>Immutable value object representing a translation for a specific key
 * in a specific language and namespace.</p>
 *
 * @param namespace Plugin namespace (e.g., "afterjournal")
 * @param key Translation key (e.g., "quest.started")
 * @param language Language code (e.g., "pt_br")
 * @param text Translated text
 * @param pluralText Plural form text (optional, for .one/.other variants)
 * @param updatedAt When translation was last updated
 * @param sourceHash MD5 hash of source text (for outdated detection)
 */
public record Translation(
        @NotNull String namespace,
        @NotNull String key,
        @NotNull String language,
        @NotNull String text,
        @Nullable String pluralText,
        @NotNull Instant updatedAt,
        @Nullable String sourceHash
) {

    /**
     * Compact constructor with validation.
     */
    public Translation {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(language, "language cannot be null");
        Objects.requireNonNull(text, "text cannot be null");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null");

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
     * Creates a simple translation without pluralization.
     *
     * @param namespace Namespace
     * @param key Key
     * @param language Language code
     * @param text Translation text
     * @return Translation instance
     */
    @NotNull
    public static Translation of(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String text
    ) {
        return new Translation(namespace, key, language, text, null, Instant.now(), null);
    }

    /**
     * Creates a translation with pluralization support.
     *
     * @param namespace Namespace
     * @param key Key
     * @param language Language code
     * @param text Singular/default text
     * @param pluralText Plural text
     * @return Translation instance
     */
    @NotNull
    public static Translation withPlural(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String text,
            @NotNull String pluralText
    ) {
        return new Translation(namespace, key, language, text, pluralText, Instant.now(), null);
    }

    /**
     * Returns full key in format "namespace:key".
     *
     * @return Full key
     */
    @NotNull
    public String fullKey() {
        return namespace + ":" + key;
    }

    /**
     * Checks if translation has plural form.
     *
     * @return true if pluralText is not null
     */
    public boolean hasPlural() {
        return pluralText != null;
    }

    /**
     * Gets text for specific count (pluralization).
     *
     * @param count Count for pluralization
     * @return Singular text if count == 1, plural text otherwise (or default text if no plural)
     */
    @NotNull
    public String getText(int count) {
        if (count == 1) {
            return text;
        }
        return pluralText != null ? pluralText : text;
    }

    /**
     * Returns a copy with updated text.
     *
     * @param newText New text
     * @return New Translation instance
     */
    @NotNull
    public Translation withText(@NotNull String newText) {
        return new Translation(namespace, key, language, newText, pluralText, Instant.now(), sourceHash);
    }

    /**
     * Returns a copy with updated source hash.
     *
     * @param hash New source hash
     * @return New Translation instance
     */
    @NotNull
    public Translation withSourceHash(@Nullable String hash) {
        return new Translation(namespace, key, language, text, pluralText, updatedAt, hash);
    }

    /**
     * Checks if translation is outdated based on source hash.
     *
     * @param currentSourceHash Current source hash
     * @return true if hashes don't match
     */
    public boolean isOutdated(@Nullable String currentSourceHash) {
        if (sourceHash == null || currentSourceHash == null) {
            return false;
        }
        return !sourceHash.equals(currentSourceHash);
    }

    @Override
    public String toString() {
        return fullKey() + " [" + language + "] = \"" + text + "\"" +
               (hasPlural() ? " (plural: \"" + pluralText + "\")" : "");
    }
}
