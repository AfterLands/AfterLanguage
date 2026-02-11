package com.afterlands.afterlanguage.api.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Represents a placeholder for template substitution.
 *
 * <p>Simple key-value pair used for replacing {key} in message templates.</p>
 *
 * @param key The placeholder key (without braces)
 * @param value The replacement value
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public record Placeholder(@NotNull String key, @NotNull String value) {

    public Placeholder {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
    }

    /**
     * Creates a new placeholder.
     *
     * @param key Placeholder key
     * @param value Replacement value
     * @return New placeholder
     */
    @NotNull
    public static Placeholder of(@NotNull String key, @NotNull String value) {
        return new Placeholder(key, value);
    }

    /**
     * Creates a placeholder with Object value (toString() called).
     *
     * @param key Placeholder key
     * @param value Replacement value
     * @return New placeholder
     */
    @NotNull
    public static Placeholder of(@NotNull String key, @NotNull Object value) {
        return new Placeholder(key, String.valueOf(value));
    }

    /**
     * Converts an array of placeholders to a Map.
     *
     * @param placeholders Placeholder array
     * @return Map of key-value pairs
     */
    @NotNull
    public static java.util.Map<String, Object> toMap(@NotNull Placeholder... placeholders) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        for (Placeholder p : placeholders) {
            map.put(p.key(), p.value());
        }
        return map;
    }
}
