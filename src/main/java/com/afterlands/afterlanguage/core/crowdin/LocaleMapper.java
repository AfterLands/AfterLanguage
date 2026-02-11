package com.afterlands.afterlanguage.core.crowdin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Maps between AfterLanguage locale codes and Crowdin locale codes.
 *
 * <p>AfterLanguage uses underscores (e.g., "pt_br", "en_us") while Crowdin
 * uses hyphens (e.g., "pt-BR", "en").</p>
 *
 * <h3>Default Mappings:</h3>
 * <pre>
 * Crowdin     -> AfterLanguage
 * pt-BR       -> pt_br
 * en          -> en_us
 * es-ES       -> es_es
 * fr          -> fr_fr
 * de          -> de_de
 * it          -> it_it
 * ja          -> ja_jp
 * ko          -> ko_kr
 * zh-CN       -> zh_cn
 * zh-TW       -> zh_tw
 * ru          -> ru_ru
 * </pre>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class LocaleMapper {

    /**
     * Crowdin locale -> AfterLanguage locale code.
     */
    private final Map<String, String> crowdinToAfter;

    /**
     * AfterLanguage locale code -> Crowdin locale.
     */
    private final Map<String, String> afterToCrowdin;

    /**
     * Creates a LocaleMapper with custom mappings.
     *
     * @param mappings Map of Crowdin locale to AfterLanguage locale
     */
    public LocaleMapper(@NotNull Map<String, String> mappings) {
        Objects.requireNonNull(mappings, "mappings cannot be null");

        this.crowdinToAfter = new LinkedHashMap<>(mappings);
        this.afterToCrowdin = new LinkedHashMap<>();

        // Build reverse mapping
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            afterToCrowdin.put(entry.getValue(), entry.getKey());
        }
    }

    /**
     * Creates a LocaleMapper with default mappings.
     */
    public LocaleMapper() {
        this(getDefaultMappings());
    }

    /**
     * Gets the default locale mappings.
     */
    @NotNull
    public static Map<String, String> getDefaultMappings() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("pt-BR", "pt_br");
        defaults.put("en", "en_us");
        defaults.put("es-ES", "es_es");
        defaults.put("fr", "fr_fr");
        defaults.put("de", "de_de");
        defaults.put("it", "it_it");
        defaults.put("ja", "ja_jp");
        defaults.put("ko", "ko_kr");
        defaults.put("zh-CN", "zh_cn");
        defaults.put("zh-TW", "zh_tw");
        defaults.put("ru", "ru_ru");
        return defaults;
    }

    /**
     * Converts a Crowdin locale to AfterLanguage locale code.
     *
     * <p>If no mapping exists, attempts automatic conversion:</p>
     * <ul>
     *     <li>"en" -> "en_us"</li>
     *     <li>"pt-BR" -> "pt_br"</li>
     * </ul>
     *
     * @param crowdinLocale Crowdin locale code (e.g., "pt-BR", "en")
     * @return AfterLanguage locale code (e.g., "pt_br", "en_us")
     */
    @NotNull
    public String toAfterLanguage(@NotNull String crowdinLocale) {
        Objects.requireNonNull(crowdinLocale, "crowdinLocale cannot be null");

        // Check explicit mapping first
        String mapped = crowdinToAfter.get(crowdinLocale);
        if (mapped != null) {
            return mapped;
        }

        // Try case-insensitive lookup
        for (Map.Entry<String, String> entry : crowdinToAfter.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(crowdinLocale)) {
                return entry.getValue();
            }
        }

        // Automatic conversion: convert to lowercase and replace hyphens
        return crowdinLocale.toLowerCase().replace("-", "_");
    }

    /**
     * Converts an AfterLanguage locale code to Crowdin locale.
     *
     * <p>If no mapping exists, attempts automatic conversion:</p>
     * <ul>
     *     <li>"pt_br" -> "pt-BR"</li>
     *     <li>"en_us" -> "en"</li>
     * </ul>
     *
     * @param afterLocale AfterLanguage locale code (e.g., "pt_br", "en_us")
     * @return Crowdin locale code (e.g., "pt-BR", "en")
     */
    @NotNull
    public String toCrowdin(@NotNull String afterLocale) {
        Objects.requireNonNull(afterLocale, "afterLocale cannot be null");

        // Check explicit mapping first
        String mapped = afterToCrowdin.get(afterLocale);
        if (mapped != null) {
            return mapped;
        }

        // Try case-insensitive lookup
        for (Map.Entry<String, String> entry : afterToCrowdin.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(afterLocale)) {
                return entry.getValue();
            }
        }

        // Automatic conversion
        // Split by underscore: "pt_br" -> ["pt", "br"]
        String[] parts = afterLocale.split("_");
        if (parts.length == 2) {
            // Convert to Crowdin format: "pt-BR"
            return parts[0].toLowerCase() + "-" + parts[1].toUpperCase();
        }

        // Single part: just lowercase
        return afterLocale.toLowerCase();
    }

    /**
     * Checks if a Crowdin locale has a mapping.
     *
     * @param crowdinLocale Crowdin locale code
     * @return true if explicitly mapped
     */
    public boolean hasCrowdinMapping(@NotNull String crowdinLocale) {
        return crowdinToAfter.containsKey(crowdinLocale);
    }

    /**
     * Checks if an AfterLanguage locale has a mapping.
     *
     * @param afterLocale AfterLanguage locale code
     * @return true if explicitly mapped
     */
    public boolean hasAfterMapping(@NotNull String afterLocale) {
        return afterToCrowdin.containsKey(afterLocale);
    }

    /**
     * Gets all Crowdin locales in the mapping.
     *
     * @return Set of Crowdin locale codes
     */
    @NotNull
    public Set<String> getCrowdinLocales() {
        return Collections.unmodifiableSet(crowdinToAfter.keySet());
    }

    /**
     * Gets all AfterLanguage locales in the mapping.
     *
     * @return Set of AfterLanguage locale codes
     */
    @NotNull
    public Set<String> getAfterLocales() {
        return Collections.unmodifiableSet(afterToCrowdin.keySet());
    }

    /**
     * Gets the number of locale mappings.
     *
     * @return Mapping count
     */
    public int size() {
        return crowdinToAfter.size();
    }

    /**
     * Normalizes a locale code to AfterLanguage format.
     *
     * <p>Handles various input formats:</p>
     * <ul>
     *     <li>"pt-BR" -> "pt_br"</li>
     *     <li>"PT_BR" -> "pt_br"</li>
     *     <li>"pt_br" -> "pt_br"</li>
     *     <li>"en" -> "en_us" (if mapped)</li>
     * </ul>
     *
     * @param locale Locale in any format
     * @return Normalized AfterLanguage locale code
     */
    @NotNull
    public String normalize(@Nullable String locale) {
        if (locale == null || locale.isEmpty()) {
            return "en_us"; // Default fallback
        }

        // Check if it's a Crowdin locale
        if (locale.contains("-")) {
            return toAfterLanguage(locale);
        }

        // Check if it's already an AfterLanguage locale (case-insensitive)
        String lower = locale.toLowerCase();
        if (afterToCrowdin.containsKey(lower)) {
            return lower;
        }

        // Check Crowdin mappings for short codes like "en"
        if (crowdinToAfter.containsKey(locale)) {
            return crowdinToAfter.get(locale);
        }

        // Try lowercase with underscore
        return lower.replace("-", "_");
    }

    /**
     * Extracts the locale from a Crowdin file path.
     *
     * <p>Example: "languages/pt-BR/myplugin/messages.yml" -> "pt_br"</p>
     *
     * @param filePath File path from Crowdin export
     * @return Extracted and mapped locale, or null if not found
     */
    @Nullable
    public String extractLocaleFromPath(@NotNull String filePath) {
        Objects.requireNonNull(filePath, "filePath cannot be null");

        // Try to find a Crowdin locale in the path
        for (String crowdinLocale : crowdinToAfter.keySet()) {
            if (filePath.contains("/" + crowdinLocale + "/") ||
                filePath.contains("\\" + crowdinLocale + "\\") ||
                filePath.startsWith(crowdinLocale + "/") ||
                filePath.startsWith(crowdinLocale + "\\")) {
                return toAfterLanguage(crowdinLocale);
            }
        }

        // Try to extract from path pattern: languages/<locale>/...
        String[] parts = filePath.replace("\\", "/").split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if ("languages".equalsIgnoreCase(part) && i + 1 < parts.length) {
                return normalize(parts[i + 1]);
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return "LocaleMapper[" + crowdinToAfter.size() + " mappings: " +
               String.join(", ", crowdinToAfter.keySet()) + "]";
    }
}
