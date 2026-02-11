package com.afterlands.afterlanguage.core.plural;

import com.afterlands.afterlanguage.api.model.PluralCategory;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Plural rules for English language.
 *
 * <h3>Rules:</h3>
 * <ul>
 *     <li><b>ONE:</b> count == 1 (singular)</li>
 *     <li><b>OTHER:</b> count != 1 (plural)</li>
 * </ul>
 *
 * <h3>Examples:</h3>
 * <pre>
 * 0 items → OTHER ("0 items")
 * 1 item  → ONE   ("1 item")
 * 2 items → OTHER ("2 items")
 * 5 items → OTHER ("5 items")
 * </pre>
 *
 * <p>Based on CLDR plural rules for English (en).</p>
 *
 * @author AfterLands Team
 * @since 1.2.0
 */
public class EnglishPluralRules implements PluralRules {

    private static final EnglishPluralRules INSTANCE = new EnglishPluralRules();
    private static final Set<PluralCategory> SUPPORTED_CATEGORIES = Set.of(
            PluralCategory.ONE,
            PluralCategory.OTHER
    );

    private EnglishPluralRules() {
    }

    @NotNull
    public static EnglishPluralRules getInstance() {
        return INSTANCE;
    }

    @Override
    @NotNull
    public PluralCategory select(int count) {
        validateCount(count);
        return count == 1 ? PluralCategory.ONE : PluralCategory.OTHER;
    }

    @Override
    @NotNull
    public Set<PluralCategory> getSupportedCategories() {
        return SUPPORTED_CATEGORIES;
    }
}
