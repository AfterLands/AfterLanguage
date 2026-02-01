package com.afterlands.afterlanguage.infra.papi;

import com.afterlands.afterlanguage.AfterLanguagePlugin;
import com.afterlands.afterlanguage.api.model.Language;
import com.afterlands.afterlanguage.core.resolver.MessageResolver;
import com.afterlands.afterlanguage.infra.persistence.PlayerLanguageRepository;
import com.afterlands.core.api.messages.MessageKey;
import com.afterlands.core.api.messages.Placeholder;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Logger;

/**
 * PlaceholderAPI expansion for AfterLanguage.
 *
 * <h3>Available Placeholders:</h3>
 * <ul>
 *     <li>%afterlang_player_language% - Player's current language code (e.g., "pt_br")</li>
 *     <li>%afterlang_player_language_name% - Player's current language name (e.g., "Português (BR)")</li>
 *     <li>%afterlang_namespace:key% - Translated message (e.g., %afterlang_afterlanguage:welcome%)</li>
 *     <li>%afterlang_key% - Translated message from "afterlanguage" namespace (e.g., %afterlang_welcome%)</li>
 * </ul>
 *
 * <h3>Usage Examples:</h3>
 * <pre>
 * # In deluxemenus or other plugins:
 * display_name: "&a{player} &7[%afterlang_player_language%]"
 * lore:
 *   - "%afterlang_afterlanguage:gui.welcome%"
 *   - "%afterlang_common:stats.level%"
 * </pre>
 */
public class AfterLanguageExpansion extends PlaceholderExpansion {

    private final AfterLanguagePlugin plugin;
    private final PlayerLanguageRepository languageRepository;
    private final MessageResolver messageResolver;
    private final Language defaultLanguage;
    private final Logger logger;
    private final boolean debug;

    /**
     * Creates PlaceholderAPI expansion.
     *
     * @param plugin Plugin instance
     * @param languageRepository Player language repository
     * @param messageResolver Message resolver
     * @param defaultLanguage Default language
     * @param logger Logger
     * @param debug Enable debug logging
     */
    public AfterLanguageExpansion(
            @NotNull AfterLanguagePlugin plugin,
            @NotNull PlayerLanguageRepository languageRepository,
            @NotNull MessageResolver messageResolver,
            @NotNull Language defaultLanguage,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.plugin = plugin;
        this.languageRepository = languageRepository;
        this.messageResolver = messageResolver;
        this.defaultLanguage = defaultLanguage;
        this.logger = logger;
        this.debug = debug;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "afterlang";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return "AfterLands";
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Never unregister
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    @Nullable
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            if (debug) {
                logger.warning("[PAPI] Placeholder request without player: " + params);
            }
            return "";
        }

        try {
            return switch (params.toLowerCase()) {
                case "player_language" -> getPlayerLanguageCode(player);
                case "player_language_name" -> getPlayerLanguageName(player);
                default -> resolveTranslationPlaceholder(player, params);
            };
        } catch (Exception e) {
            logger.warning("[PAPI] Failed to resolve placeholder '" + params + "' for " +
                    player.getName() + ": " + e.getMessage());
            if (debug) {
                e.printStackTrace();
            }
            return "";
        }
    }

    /**
     * Gets player's current language code.
     *
     * @param player Player
     * @return Language code (e.g., "pt_br")
     */
    @NotNull
    private String getPlayerLanguageCode(@NotNull Player player) {
        try {
            return languageRepository.getCachedLanguage(player.getUniqueId())
                    .orElse(defaultLanguage.code());
        } catch (Exception e) {
            logger.warning("[PAPI] Failed to get language code for " + player.getName());
            return defaultLanguage.code();
        }
    }

    /**
     * Gets player's current language name.
     *
     * @param player Player
     * @return Language name (e.g., "Português (BR)")
     */
    @NotNull
    private String getPlayerLanguageName(@NotNull Player player) {
        String code = getPlayerLanguageCode(player);
        return getLanguageDisplayName(code);
    }

    /**
     * Resolves translation placeholder.
     *
     * <p>Supports two formats:</p>
     * <ul>
     *     <li>namespace:key - Full qualified key (e.g., "afterlanguage:welcome")</li>
     *     <li>key - Assumes "afterlanguage" namespace (e.g., "welcome")</li>
     * </ul>
     *
     * @param player Player
     * @param params Placeholder params
     * @return Translated message
     */
    @Nullable
    private String resolveTranslationPlaceholder(@NotNull Player player, @NotNull String params) {
        String namespace;
        String key;

        // Parse namespace:key or just key
        if (params.contains(":")) {
            String[] parts = params.split(":", 2);
            if (parts.length != 2) {
                logger.warning("[PAPI] Invalid placeholder format: " + params);
                return null;
            }
            namespace = parts[0];
            key = parts[1];
        } else {
            // Default to "afterlanguage" namespace
            namespace = "afterlanguage";
            key = params;
        }

        // Get player's language
        String language = getPlayerLanguageCode(player);

        // Resolve translation
        String resolved = messageResolver.resolve(language, namespace, key);

        if (debug) {
            logger.info(String.format(
                    "[PAPI] Resolved %s:%s for %s (%s) -> %s",
                    namespace, key, player.getName(), language, resolved
            ));
        }

        return resolved;
    }

    /**
     * Gets language display name from config.
     *
     * @param code Language code
     * @return Display name
     */
    @NotNull
    private String getLanguageDisplayName(@NotNull String code) {
        String configName = plugin.getConfig()
                .getString("language-names." + code);

        if (configName != null) {
            return configName;
        }

        // Fallback to common names
        return switch (code) {
            case "pt_br" -> "Português (BR)";
            case "en_us" -> "English (US)";
            case "es_es" -> "Español (ES)";
            default -> code;
        };
    }
}
