package com.afterlands.afterlanguage.api.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Represents a supported language.
 *
 * <p>Immutable value object.</p>
 *
 * @param code Language code (e.g., "pt_br", "en_us")
 * @param name Display name (e.g., "Português (BR)", "English (US)")
 * @param enabled Whether language is enabled
 */
public record Language(
        @NotNull String code,
        @NotNull String name,
        boolean enabled
) {

    /**
     * Compact constructor with validation.
     */
    public Language {
        Objects.requireNonNull(code, "code cannot be null");
        Objects.requireNonNull(name, "name cannot be null");

        if (code.isEmpty()) {
            throw new IllegalArgumentException("code cannot be empty");
        }

        // Validate code format (lowercase, alphanumeric + underscore)
        if (!code.matches("^[a-z]{2}_[a-z]{2}$")) {
            throw new IllegalArgumentException(
                "Invalid language code format: '" + code + "' (expected format: xx_xx, e.g., pt_br)"
            );
        }
    }

    /**
     * Creates a language.
     *
     * @param code Language code
     * @param name Display name
     * @return Language instance
     */
    @NotNull
    public static Language of(@NotNull String code, @NotNull String name) {
        return new Language(code, name, true);
    }

    /**
     * Creates a disabled language.
     *
     * @param code Language code
     * @param name Display name
     * @return Disabled language instance
     */
    @NotNull
    public static Language disabled(@NotNull String code, @NotNull String name) {
        return new Language(code, name, false);
    }

    /**
     * Returns a copy with enabled status changed.
     *
     * @param enabled New enabled status
     * @return New Language instance
     */
    @NotNull
    public Language withEnabled(boolean enabled) {
        return new Language(code, name, enabled);
    }

    /**
     * Checks if this is the default language (pt_br).
     *
     * @return true if code is "pt_br"
     */
    public boolean isDefault() {
        return "pt_br".equals(code);
    }

    @Override
    public String toString() {
        return name + " (" + code + ")" + (enabled ? "" : " [disabled]");
    }
}
