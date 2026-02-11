package com.afterlands.afterlanguage.core.plural;

import com.afterlands.afterlanguage.api.model.PluralCategory;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Plural rules for Spanish language.
 *
 * <h3>Rules:</h3>
 * <ul>
 *     <li><b>ONE:</b> count == 1 (singular)</li>
 *     <li><b>OTHER:</b> count != 1 (plural)</li>
 * </ul>
 *
 * <h3>Examples:</h3>
 * <pre>
 * 0 elementos → OTHER ("0 elementos")
 * 1 elemento  → ONE   ("1 elemento")
 * 2 elementos → OTHER ("2 elementos")
 * 5 elementos → OTHER ("5 elementos")
 * </pre>
 *
 * <p>Based on CLDR plural rules for Spanish (es).</p>
 *
 * @author AfterLands Team
 * @since 1.2.0
 */
public class SpanishPluralRules implements PluralRules {

    private static final SpanishPluralRules INSTANCE = new SpanishPluralRules();
    private static final Set<PluralCategory> SUPPORTED_CATEGORIES = Set.of(
            PluralCategory.ONE,
            PluralCategory.OTHER
    );

    private SpanishPluralRules() {
    }

    @NotNull
    public static SpanishPluralRules getInstance() {
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
