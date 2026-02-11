package com.afterlands.afterlanguage.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single translation entry.
 *
 * <p>Immutable value object representing a translation for a specific key
 * in a specific language and namespace.</p>
 *
 * <h3>Pluralization (v1.2.0):</h3>
 * <p>Supports ICU-like plural forms via {@link PluralCategory}:</p>
 * <ul>
 *     <li>ZERO - For count = 0 (Arabic, etc.)</li>
 *     <li>ONE - For count = 1 (singular)</li>
 *     <li>TWO - For count = 2 (Arabic, Welsh)</li>
 *     <li>FEW - For small quantities (Slavic languages)</li>
 *     <li>MANY - For large quantities (Slavic languages)</li>
 *     <li>OTHER - Default/fallback (always required)</li>
 * </ul>
 *
 * @param namespace Plugin namespace (e.g., "afterjournal")
 * @param key Translation key (e.g., "quest.started")
 * @param language Language code (e.g., "pt_br")
 * @param text Default translated text (corresponds to OTHER plural form)
 * @param pluralText Deprecated: Legacy plural form (migrated to pluralForms.OTHER)
 * @param pluralForms Map of plural categories to their translated text (v1.2.0)
 * @param updatedAt When translation was last updated
 * @param sourceHash MD5 hash of source text (for outdated detection)
 */
public record Translation(
        @NotNull String namespace,
        @NotNull String key,
        @NotNull String language,
        @NotNull String text,
        @Deprecated @Nullable String pluralText,
        @Nullable Map<PluralCategory, String> pluralForms,
        @NotNull Instant updatedAt,
        @Nullable String sourceHash
) {

    /**
     * Compact constructor with validation and migration.
     *
     * <p>Automatically migrates legacy pluralText to pluralForms for backwards compatibility.</p>
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

        // Migration: Convert legacy pluralText to pluralForms
        if (pluralForms == null && pluralText != null) {
            Map<PluralCategory, String> migrated = new HashMap<>();
            migrated.put(PluralCategory.ONE, text);
            migrated.put(PluralCategory.OTHER, pluralText);
            pluralForms = Collections.unmodifiableMap(migrated);
        }

        // Ensure pluralForms is immutable if present
        if (pluralForms != null) {
            pluralForms = Collections.unmodifiableMap(new HashMap<>(pluralForms));

            // Validate: must have at least OTHER
            if (!pluralForms.containsKey(PluralCategory.OTHER)) {
                throw new IllegalArgumentException("pluralForms must contain at least OTHER category");
            }
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
        return new Translation(namespace, key, language, text, null, null, Instant.now(), null);
    }

    /**
     * Creates a translation with pluralization support (legacy).
     *
     * @param namespace Namespace
     * @param key Key
     * @param language Language code
     * @param text Singular/default text
     * @param pluralText Plural text
     * @return Translation instance
     * @deprecated Use {@link #withPluralForms(String, String, String, Map)} instead
     */
    @NotNull
    @Deprecated
    public static Translation withPlural(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String text,
            @NotNull String pluralText
    ) {
        return new Translation(namespace, key, language, text, pluralText, null, Instant.now(), null);
    }

    /**
     * Creates a translation with ICU-like plural forms (v1.2.0).
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * Map<PluralCategory, String> forms = Map.of(
     *     PluralCategory.ONE, "1 item",
     *     PluralCategory.OTHER, "{count} items"
     * );
     * Translation t = Translation.withPluralForms("myplugin", "items", "en_us", forms);
     * }</pre>
     *
     * @param namespace Namespace
     * @param key Key
     * @param language Language code
     * @param pluralForms Map of plural categories to text (must include OTHER)
     * @return Translation instance
     * @throws IllegalArgumentException if pluralForms doesn't contain OTHER
     */
    @NotNull
    public static Translation withPluralForms(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull Map<PluralCategory, String> pluralForms
    ) {
        if (pluralForms == null || !pluralForms.containsKey(PluralCategory.OTHER)) {
            throw new IllegalArgumentException("pluralForms must contain at least OTHER category");
        }

        String defaultText = pluralForms.get(PluralCategory.OTHER);
        return new Translation(namespace, key, language, defaultText, null, pluralForms, Instant.now(), null);
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
     * Checks if translation has plural forms.
     *
     * @return true if pluralForms is not null and not empty
     */
    public boolean hasPlural() {
        return pluralForms != null && !pluralForms.isEmpty();
    }

    /**
     * Gets the text for a specific plural category.
     *
     * <p>Falls back to OTHER if the specific category is not defined.</p>
     *
     * @param category The plural category
     * @return The text for the category, or OTHER if not found
     */
    @NotNull
    public String getTextForCategory(@NotNull PluralCategory category) {
        if (pluralForms == null || pluralForms.isEmpty()) {
            return text;
        }

        String categoryText = pluralForms.get(category);
        if (categoryText != null) {
            return categoryText;
        }

        // Fallback to OTHER
        return pluralForms.getOrDefault(PluralCategory.OTHER, text);
    }

    /**
     * Gets text for specific count (pluralization) - legacy simple version.
     *
     * <p>This is a simplified version that only handles ONE/OTHER.
     * For full ICU plural support, use {@link #getTextForCategory(PluralCategory)}
     * with PluralRules to select the correct category.</p>
     *
     * @param count Count for pluralization
     * @return Singular text if count == 1, plural text otherwise
     * @deprecated Use PluralRules.select() + getTextForCategory() instead
     */
    @NotNull
    @Deprecated
    public String getText(int count) {
        if (pluralForms != null && !pluralForms.isEmpty()) {
            PluralCategory category = count == 1 ? PluralCategory.ONE : PluralCategory.OTHER;
            return getTextForCategory(category);
        }

        // Legacy behavior
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
        return new Translation(namespace, key, language, newText, pluralText, pluralForms, Instant.now(), sourceHash);
    }

    /**
     * Returns a copy with updated plural forms.
     *
     * @param newPluralForms New plural forms
     * @return New Translation instance
     */
    @NotNull
    public Translation withPluralForms(@NotNull Map<PluralCategory, String> newPluralForms) {
        String newText = newPluralForms.getOrDefault(PluralCategory.OTHER, text);
        return new Translation(namespace, key, language, newText, null, newPluralForms, Instant.now(), sourceHash);
    }

    /**
     * Returns a copy with updated source hash.
     *
     * @param hash New source hash
     * @return New Translation instance
     */
    @NotNull
    public Translation withSourceHash(@Nullable String hash) {
        return new Translation(namespace, key, language, text, pluralText, pluralForms, updatedAt, hash);
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
        StringBuilder sb = new StringBuilder();
        sb.append(fullKey()).append(" [").append(language).append("] = \"").append(text).append("\"");

        if (hasPlural()) {
            sb.append(" (plural forms: ");
            if (pluralForms != null) {
                sb.append(pluralForms.size()).append(" categories)");
            } else if (pluralText != null) {
                sb.append("legacy)");
            }
        }

        return sb.toString();
    }
}
