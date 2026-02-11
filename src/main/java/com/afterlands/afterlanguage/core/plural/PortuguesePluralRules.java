package com.afterlands.afterlanguage.core.plural;

import com.afterlands.afterlanguage.api.model.PluralCategory;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Plural rules for Portuguese (Brazilian) language.
 *
 * <h3>Rules:</h3>
 * <ul>
 *     <li><b>ONE:</b> count == 1 (singular)</li>
 *     <li><b>OTHER:</b> count == 0 or count >= 2 (plural)</li>
 * </ul>
 *
 * <h3>Examples:</h3>
 * <pre>
 * 0 items → OTHER ("0 itens")
 * 1 item  → ONE   ("1 item")
 * 2 items → OTHER ("2 itens")
 * 5 items → OTHER ("5 itens")
 * </pre>
 *
 * <p>Based on CLDR plural rules for Portuguese (pt).</p>
 *
 * @author AfterLands Team
 * @since 1.2.0
 */
public class PortuguesePluralRules implements PluralRules {

    private static final PortuguesePluralRules INSTANCE = new PortuguesePluralRules();
    private static final Set<PluralCategory> SUPPORTED_CATEGORIES = Set.of(
            PluralCategory.ONE,
            PluralCategory.OTHER
    );

    private PortuguesePluralRules() {
    }

    @NotNull
    public static PortuguesePluralRules getInstance() {
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
