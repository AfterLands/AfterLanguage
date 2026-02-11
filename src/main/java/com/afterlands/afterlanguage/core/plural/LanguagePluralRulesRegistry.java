package com.afterlands.afterlanguage.core.plural;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

/**
 * Registry for language-specific plural rules.
 *
 * <p>Maps language codes (e.g., "pt_br", "en_us") to their corresponding
 * {@link PluralRules} implementations.</p>
 *
 * <h3>Supported Languages:</h3>
 * <ul>
 *     <li><b>pt_br</b> - Portuguese (Brazilian): {@link PortuguesePluralRules}</li>
 *     <li><b>en_us</b> - English (US): {@link EnglishPluralRules}</li>
 *     <li><b>es_es</b> - Spanish (Spain): {@link SpanishPluralRules}</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * PluralRules rules = LanguagePluralRulesRegistry.getRules("pt_br");
 * PluralCategory category = rules.select(5); // Returns OTHER
 * }</pre>
 *
 * <p>This class is thread-safe and uses immutable data structures.</p>
 *
 * @author AfterLands Team
 * @since 1.2.0
 */
public final class LanguagePluralRulesRegistry {

    private static final Map<String, PluralRules> RULES = Map.of(
            "pt_br", PortuguesePluralRules.getInstance(),
            "en_us", EnglishPluralRules.getInstance(),
            "es_es", SpanishPluralRules.getInstance()
    );

    private static final PluralRules DEFAULT_RULES = EnglishPluralRules.getInstance();

    private LanguagePluralRulesRegistry() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    @NotNull
    public static PluralRules getRules(@NotNull String languageCode) {
        if (languageCode == null) {
            throw new NullPointerException("languageCode cannot be null");
        }
        return RULES.getOrDefault(languageCode.toLowerCase(), DEFAULT_RULES);
    }

    @NotNull
    public static Set<String> getSupportedLanguages() {
        return RULES.keySet();
    }

    public static boolean isSupported(@NotNull String languageCode) {
        if (languageCode == null) {
            return false;
        }
        return RULES.containsKey(languageCode.toLowerCase());
    }

    @NotNull
    public static PluralRules getDefaultRules() {
        return DEFAULT_RULES;
    }
}
