package com.afterlands.afterlanguage.core.resolver;

import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.afterlanguage.core.cache.TranslationCache;
import com.afterlands.afterlanguage.core.template.CompiledMessage;
import com.afterlands.afterlanguage.core.template.TemplateEngine;
import com.afterlands.core.api.messages.Placeholder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core translation resolver with fallback chain.
 *
 * <h3>Fallback Chain:</h3>
 * <ol>
 *     <li>Player's configured language</li>
 *     <li>Default language (pt_br)</li>
 *     <li>Key literal or missing-format</li>
 * </ol>
 *
 * <h3>Caching Strategy:</h3>
 * <ul>
 *     <li>L1 (Hot): Check for cached resolved text</li>
 *     <li>L2 (Registry): Lookup translation from registry</li>
 *     <li>L3 (Template): Use compiled template if available</li>
 *     <li>Compile template and cache if needed</li>
 * </ul>
 *
 * <h3>Performance:</h3>
 * <ul>
 *     <li>L1 hit: &lt; 0.01ms</li>
 *     <li>L2 hit: &lt; 0.1ms</li>
 *     <li>Full resolution: &lt; 0.2ms</li>
 * </ul>
 */
public class MessageResolver {

    private final TranslationRegistry registry;
    private final TranslationCache cache;
    private final TemplateEngine templateEngine;

    private final String defaultLanguage;
    private final boolean showKeyOnMissing;
    private final String missingFormat;
    private final boolean logMissingKeys;
    private final boolean debug;

    // Track missing keys to avoid spam
    private final Set<String> loggedMissingKeys = ConcurrentHashMap.newKeySet();

    /**
     * Creates a message resolver.
     *
     * @param registry Translation registry (L2)
     * @param cache Translation cache (L1/L3)
     * @param templateEngine Template compilation engine
     * @param defaultLanguage Default language code
     * @param showKeyOnMissing Show key when translation missing
     * @param missingFormat Format for missing translations
     * @param logMissingKeys Log missing keys to console
     * @param debug Enable debug logging
     */
    public MessageResolver(
            @NotNull TranslationRegistry registry,
            @NotNull TranslationCache cache,
            @NotNull TemplateEngine templateEngine,
            @NotNull String defaultLanguage,
            boolean showKeyOnMissing,
            @NotNull String missingFormat,
            boolean logMissingKeys,
            boolean debug
    ) {
        this.registry = registry;
        this.cache = cache;
        this.templateEngine = templateEngine;
        this.defaultLanguage = defaultLanguage;
        this.showKeyOnMissing = showKeyOnMissing;
        this.missingFormat = missingFormat;
        this.logMissingKeys = logMissingKeys;
        this.debug = debug;
    }

    /**
     * Resolves a translation with placeholders.
     *
     * <p>Follows the fallback chain and caching strategy.</p>
     *
     * @param language Player's language
     * @param namespace Namespace
     * @param key Key
     * @param placeholders Placeholders to apply
     * @return Resolved translation
     */
    @NotNull
    public String resolve(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key,
            @NotNull Placeholder... placeholders
    ) {
        // Convert placeholders to map
        Map<String, Object> placeholderMap = Placeholder.toMap(placeholders);

        // Try L1 hot cache (only if no placeholders, otherwise always dynamic)
        if (placeholders.length == 0) {
            String cached = cache.getHot(language, namespace, key);
            if (cached != null) {
                return cached;
            }
        }

        // Try player's language
        Optional<Translation> translation = registry.get(language, namespace, key);

        // Fallback to default language if not found
        if (translation.isEmpty() && !language.equals(defaultLanguage)) {
            translation = registry.get(defaultLanguage, namespace, key);
        }

        // If found, resolve with placeholders
        if (translation.isPresent()) {
            String resolved = resolveWithPlaceholders(
                    language, namespace, key,
                    translation.get().text(),
                    placeholderMap
            );

            // Cache if no placeholders (static text)
            if (placeholders.length == 0) {
                cache.putHot(language, namespace, key, resolved);
            }

            return resolved;
        }

        // Ultimate fallback: missing translation
        return handleMissing(language, namespace, key);
    }

    /**
     * Resolves translation with pluralization support.
     *
     * @param language Player's language
     * @param namespace Namespace
     * @param key Key
     * @param count Count for pluralization
     * @param placeholders Placeholders to apply
     * @return Resolved translation
     */
    @NotNull
    public String resolve(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key,
            int count,
            @NotNull Placeholder... placeholders
    ) {
        // Add count as placeholder
        Placeholder[] withCount = new Placeholder[placeholders.length + 1];
        withCount[0] = Placeholder.of("count", count);
        System.arraycopy(placeholders, 0, withCount, 1, placeholders.length);

        // Try player's language
        Optional<Translation> translation = registry.get(language, namespace, key);

        // Fallback to default language if not found
        if (translation.isEmpty() && !language.equals(defaultLanguage)) {
            translation = registry.get(defaultLanguage, namespace, key);
        }

        if (translation.isPresent()) {
            Translation t = translation.get();
            String text = t.getText(count); // Gets plural form if available

            Map<String, Object> placeholderMap = Placeholder.toMap(withCount);
            return resolveWithPlaceholders(language, namespace, key, text, placeholderMap);
        }

        return handleMissing(language, namespace, key);
    }

    /**
     * Resolves text with placeholders using template engine.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     * @param text Translation text
     * @param placeholders Placeholder map
     * @return Resolved text
     */
    @NotNull
    private String resolveWithPlaceholders(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String text,
            @NotNull Map<String, Object> placeholders
    ) {
        // If no placeholders in text, return as-is
        if (!templateEngine.hasPlaceholders(text)) {
            return text;
        }

        // If no values provided, return text with placeholders intact
        if (placeholders.isEmpty()) {
            return text;
        }

        // Try L3 template cache
        CompiledMessage compiled = cache.getTemplate(language, namespace, key);

        if (compiled == null) {
            // Compile and cache
            compiled = templateEngine.compile(text);
            cache.putTemplate(language, namespace, key, compiled);
        }

        // Apply placeholders
        return compiled.apply(placeholders);
    }

    /**
     * Handles missing translation.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     * @return Fallback text
     */
    @NotNull
    private String handleMissing(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        String fullKey = namespace + ":" + key;

        // Log if enabled (avoid spam with deduplication)
        if (logMissingKeys && loggedMissingKeys.add(fullKey)) {
            System.out.println("[AfterLanguage] Missing translation: " + fullKey + " [" + language + "]");
        }

        // Return formatted missing message or key literal
        if (showKeyOnMissing) {
            return missingFormat
                    .replace("{namespace}", namespace)
                    .replace("{key}", key)
                    .replace("{fullkey}", fullKey);
        } else {
            return fullKey;
        }
    }

    /**
     * Checks if translation exists for a key.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     * @return true if translation exists
     */
    public boolean exists(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        Optional<Translation> translation = registry.get(language, namespace, key);

        if (translation.isPresent()) {
            return true;
        }

        // Check fallback
        if (!language.equals(defaultLanguage)) {
            return registry.get(defaultLanguage, namespace, key).isPresent();
        }

        return false;
    }

    /**
     * Resets missing key log tracking.
     *
     * <p>Useful after reload to re-log previously missing keys.</p>
     */
    public void resetMissingKeyTracking() {
        loggedMissingKeys.clear();
    }
}
