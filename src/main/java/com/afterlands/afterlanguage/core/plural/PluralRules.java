package com.afterlands.afterlanguage.core.plural;

import com.afterlands.afterlanguage.api.model.PluralCategory;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Defines language-specific plural rules for selecting the appropriate plural category.
 *
 * <p>Each language has unique pluralization rules that determine which {@link PluralCategory}
 * to use for a given count. This interface allows implementing CLDR-compliant plural rules
 * for different languages.</p>
 *
 * <h3>Implementation Examples:</h3>
 * <pre>{@code
 * // English: ONE for 1, OTHER for everything else
 * public class EnglishPluralRules implements PluralRules {
 *     public PluralCategory select(int count) {
 *         return (count == 1) ? PluralCategory.ONE : PluralCategory.OTHER;
 *     }
 * }
 *
 * // Polish: ONE for 1, FEW for 2-4, MANY for 5+, OTHER for fractions
 * public class PolishPluralRules implements PluralRules {
 *     public PluralCategory select(int count) {
 *         if (count == 1) return PluralCategory.ONE;
 *         if (count >= 2 && count <= 4) return PluralCategory.FEW;
 *         return PluralCategory.MANY;
 *     }
 * }
 * }</pre>
 *
 * <h3>Usage in Translation Resolution:</h3>
 * <pre>{@code
 * PluralRules rules = languagePluralRulesRegistry.get("pt_br");
 * PluralCategory category = rules.select(5);  // Returns OTHER for Portuguese
 *
 * String key = "items.found." + category.getKey();  // "items.found.other"
 * String template = registry.get(language, namespace, key);
 * }</pre>
 *
 * @see PluralCategory
 * @see <a href="https://cldr.unicode.org/index/cldr-spec/plural-rules">CLDR Plural Rules</a>
 */
@FunctionalInterface
public interface PluralRules {

    /**
     * Selects the appropriate plural category for the given count.
     *
     * <p>This method implements the language-specific logic for determining
     * which plural form to use. The returned category is used to look up
     * the correct translation variant.</p>
     *
     * <h3>Examples:</h3>
     * <pre>
     * English (count=1):  ONE
     * English (count=5):  OTHER
     * Polish (count=2):   FEW
     * Arabic (count=0):   ZERO
     * Arabic (count=2):   TWO
     * </pre>
     *
     * @param count The numeric count to evaluate (must be non-negative)
     * @return The appropriate plural category (never null)
     * @throws IllegalArgumentException if count is negative
     */
    @NotNull
    PluralCategory select(int count);

    /**
     * Returns all plural categories supported by this language's rules.
     *
     * <p>This set represents all categories that could be returned by {@link #select(int)}.
     * It is used for:</p>
     * <ul>
     *   <li>Validating translation files have all required plural forms</li>
     *   <li>Generating template translation files for translators</li>
     *   <li>Detecting missing plural variants in translations</li>
     * </ul>
     *
     * <p>Note: {@link PluralCategory#OTHER} should always be included as it serves
     * as the fallback category.</p>
     *
     * <h3>Examples:</h3>
     * <pre>
     * English:    [ONE, OTHER]
     * Portuguese: [ONE, OTHER]
     * Polish:     [ONE, FEW, MANY, OTHER]
     * Arabic:     [ZERO, ONE, TWO, FEW, MANY, OTHER]
     * </pre>
     *
     * @return Immutable set of supported plural categories (never null or empty)
     */
    @NotNull
    default Set<PluralCategory> getSupportedCategories() {
        // Default implementation for simple languages (English-style)
        return Set.of(PluralCategory.ONE, PluralCategory.OTHER);
    }

    /**
     * Checks if this rule set supports a specific plural category.
     *
     * <p>Convenience method equivalent to {@code getSupportedCategories().contains(category)}.</p>
     *
     * @param category The category to check
     * @return true if this category is supported by the language
     */
    default boolean supports(@NotNull PluralCategory category) {
        return getSupportedCategories().contains(category);
    }

    /**
     * Validates that the given count is acceptable for plural rule evaluation.
     *
     * <p>Default implementation rejects negative counts. Override if your language
     * has additional constraints.</p>
     *
     * @param count The count to validate
     * @throws IllegalArgumentException if count is invalid
     */
    default void validateCount(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative: " + count);
        }
    }
}
