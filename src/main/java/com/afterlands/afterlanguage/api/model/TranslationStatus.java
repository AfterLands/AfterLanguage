package com.afterlands.afterlanguage.api.model;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Status of a translation across all languages.
 *
 * @param namespace Namespace
 * @param key Key
 * @param states Map of language code to translation state
 */
public record TranslationStatus(
        @NotNull String namespace,
        @NotNull String key,
        @NotNull Map<String, TranslationState> states
) {

    /**
     * Compact constructor with validation.
     */
    public TranslationStatus {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(states, "states cannot be null");

        // Make immutable copy
        states = Map.copyOf(states);
    }

    /**
     * Creates a status with a single language state.
     *
     * @param namespace Namespace
     * @param key Key
     * @param language Language code
     * @param state State
     * @return TranslationStatus instance
     */
    @NotNull
    public static TranslationStatus of(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull TranslationState state
    ) {
        return new TranslationStatus(namespace, key, Map.of(language, state));
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
     * Gets state for a specific language.
     *
     * @param language Language code
     * @return State or PENDING if not found
     */
    @NotNull
    public TranslationState getState(@NotNull String language) {
        return states.getOrDefault(language, TranslationState.PENDING);
    }

    /**
     * Checks if all enabled languages have translations.
     *
     * @return true if no PENDING states
     */
    public boolean isFullyTranslated() {
        return states.values().stream()
                .noneMatch(state -> state == TranslationState.PENDING);
    }

    /**
     * Checks if any translation is outdated.
     *
     * @return true if any OUTDATED state exists
     */
    public boolean hasOutdated() {
        return states.values().stream()
                .anyMatch(state -> state == TranslationState.OUTDATED);
    }

    /**
     * Returns a copy with updated state for a language.
     *
     * @param language Language code
     * @param state New state
     * @return New TranslationStatus instance
     */
    @NotNull
    public TranslationStatus withState(@NotNull String language, @NotNull TranslationState state) {
        Map<String, TranslationState> newStates = new java.util.HashMap<>(states);
        newStates.put(language, state);
        return new TranslationStatus(namespace, key, newStates);
    }

    @Override
    public String toString() {
        return fullKey() + " - " + states;
    }
}
