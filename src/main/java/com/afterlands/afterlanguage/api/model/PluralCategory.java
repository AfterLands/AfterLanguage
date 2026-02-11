package com.afterlands.afterlanguage.api.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * ICU-style plural categories for language-specific pluralization rules.
 *
 * <p>These categories follow the CLDR (Common Locale Data Repository) specification
 * and are used to support complex pluralization rules across different languages.</p>
 *
 * <h3>Category Usage by Language:</h3>
 * <ul>
 *   <li><b>ZERO:</b> Used in Arabic for count = 0 (اصفار)</li>
 *   <li><b>ONE:</b> Singular form in most languages (1 item)</li>
 *   <li><b>TWO:</b> Dual form in Arabic, Welsh, Slovenian (2 items)</li>
 *   <li><b>FEW:</b> Small count (3-10) in Polish, Russian, Arabic, Croatian</li>
 *   <li><b>MANY:</b> Large count (11+) in Polish, Russian, Arabic</li>
 *   <li><b>OTHER:</b> Default/fallback for all other cases (always required)</li>
 * </ul>
 *
 * <h3>Language Examples:</h3>
 * <pre>
 * English:    ONE (1), OTHER (0, 2+)
 * Portuguese: ONE (1), OTHER (0, 2+)
 * Polish:     ONE (1), FEW (2-4), MANY (5+), OTHER (fractions)
 * Arabic:     ZERO (0), ONE (1), TWO (2), FEW (3-10), MANY (11-99), OTHER (100+)
 * </pre>
 *
 * @see <a href="https://cldr.unicode.org/index/cldr-spec/plural-rules">CLDR Plural Rules</a>
 */
public enum PluralCategory {

    /**
     * Used for count = 0 in languages like Arabic.
     *
     * <p>Example: "لا توجد عناصر" (no items)</p>
     */
    ZERO,

    /**
     * Singular form - used for count = 1 in most languages.
     *
     * <p>Example: "1 item" / "1 item" / "1 предмет"</p>
     */
    ONE,

    /**
     * Dual form - used for count = 2 in languages with dual grammatical number.
     *
     * <p>Languages: Arabic, Welsh, Slovenian, Sorbian</p>
     * <p>Example: "عنصران" (2 items in Arabic)</p>
     */
    TWO,

    /**
     * Small count form - used for small numbers (typically 3-10) in Slavic languages.
     *
     * <p>Languages: Polish, Russian, Ukrainian, Czech, Slovak, Croatian</p>
     * <p>Example: "2-4 предмета" in Russian (2-4 items)</p>
     */
    FEW,

    /**
     * Large count form - used for larger numbers (typically 11+) in Slavic languages.
     *
     * <p>Languages: Polish, Russian, Ukrainian, Czech, Slovak, Croatian</p>
     * <p>Example: "5+ предметов" in Russian (5+ items)</p>
     */
    MANY,

    /**
     * Default/fallback category - must always be defined.
     *
     * <p>Used for all counts not covered by other categories in the language's plural rules.
     * In languages like English, this covers all non-singular forms (0, 2, 3, ...).</p>
     */
    OTHER;

    /**
     * Returns the lowercase key representation for this category.
     *
     * <p>Used in YAML translation files and template syntax.</p>
     *
     * @return Lowercase category name (e.g., "zero", "one", "other")
     */
    @NotNull
    public String getKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a plural category from its string key.
     *
     * <p>Case-insensitive parsing. Returns null if key is invalid.</p>
     *
     * @param key Category key (e.g., "one", "FEW", "Other")
     * @return Corresponding PluralCategory, or null if not found
     */
    @Nullable
    public static PluralCategory fromKey(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }

        try {
            return valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Checks if this category is the mandatory OTHER category.
     *
     * @return true if this is OTHER
     */
    public boolean isOther() {
        return this == OTHER;
    }

    /**
     * Checks if this category represents a singular form (ONE).
     *
     * @return true if this is ONE
     */
    public boolean isSingular() {
        return this == ONE;
    }
}
