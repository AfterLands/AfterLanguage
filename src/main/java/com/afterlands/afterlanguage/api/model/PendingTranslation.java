package com.afterlands.afterlanguage.api.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a translation that needs attention (pending or outdated).
 *
 * @param namespace Namespace
 * @param key Key
 * @param sourceText Original source text (from default language)
 * @param language Target language that needs translation
 * @param state Current state (PENDING or OUTDATED)
 * @param createdAt When this pending translation was created
 */
public record PendingTranslation(
        @NotNull String namespace,
        @NotNull String key,
        @NotNull String sourceText,
        @NotNull String language,
        @NotNull TranslationState state,
        @NotNull Instant createdAt
) {

    /**
     * Compact constructor with validation.
     */
    public PendingTranslation {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(sourceText, "sourceText cannot be null");
        Objects.requireNonNull(language, "language cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");

        if (state == TranslationState.TRANSLATED) {
            throw new IllegalArgumentException("PendingTranslation cannot have TRANSLATED state");
        }
    }

    /**
     * Creates a pending translation.
     *
     * @param namespace Namespace
     * @param key Key
     * @param sourceText Source text
     * @param language Target language
     * @return PendingTranslation instance
     */
    @NotNull
    public static Translation of(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String sourceText,
            @NotNull String language
    ) {
        return Translation.of(namespace, key, language, sourceText);
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
     * Checks if this is a pending translation (no translation exists).
     *
     * @return true if state is PENDING
     */
    public boolean isPending() {
        return state == TranslationState.PENDING;
    }

    /**
     * Checks if this is an outdated translation (source changed).
     *
     * @return true if state is OUTDATED
     */
    public boolean isOutdated() {
        return state == TranslationState.OUTDATED;
    }

    @Override
    public String toString() {
        return fullKey() + " [" + language + "] - " + state + " (source: \"" + sourceText + "\")";
    }
}
