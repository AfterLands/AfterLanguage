package com.afterlands.afterlanguage.infra.command;

import com.afterlands.afterlanguage.bootstrap.PluginRegistry;
import com.afterlands.core.api.messages.MessageKey;
import com.afterlands.core.api.messages.Placeholder;
import com.afterlands.core.commands.annotations.Command;
import com.afterlands.core.commands.annotations.SubCommand;
import com.afterlands.core.commands.annotations.Arg;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Player language management command.
 *
 * <h3>Usage:</h3>
 * <ul>
 *     <li>/lang set {@literal <}language{@literal >} - Change your language</li>
 *     <li>/lang list - List available languages</li>
 *     <li>/lang info - Show your current language</li>
 * </ul>
 */
@Command(name = "lang", permission = "afterlanguage.use")
public class LangCommand {

    private final PluginRegistry registry;

    public LangCommand(@NotNull PluginRegistry registry) {
        this.registry = registry;
    }

    /**
     * Shows player's current language (default subcommand).
     */
    @SubCommand(name = "")
    public void defaultCommand(@NotNull Player player) {
        showInfo(player);
    }

    /**
     * Changes player's language.
     *
     * @param player Player executing command
     * @param langCode Language code (e.g., "pt_br", "en_us")
     */
    @SubCommand(name = "set")
    public void setLanguage(
            @NotNull Player player,
            @Arg(name = "language") @NotNull String langCode
    ) {
        UUID playerId = player.getUniqueId();
        String normalizedCode = langCode.toLowerCase();

        // Validate language exists
        List<String> availableLanguages = getAvailableLanguages();
        if (!availableLanguages.contains(normalizedCode)) {
            sendMessage(player, MessageKey.of("afterlanguage", "error.invalid_language"),
                    Placeholder.of("language", normalizedCode));
            return;
        }

        // Save async
        registry.getPlayerLanguageRepo().setLanguage(playerId, normalizedCode, false)
                .thenAccept(v -> {
                    // Send confirmation in NEW language
                    sendMessage(player, MessageKey.of("afterlanguage", "general.language_changed"),
                            Placeholder.of("language", normalizedCode));
                })
                .exceptionally(ex -> {
                    registry.getLogger().warning("[LangCommand] Failed to save language for " +
                            player.getName() + ": " + ex.getMessage());
                    sendMessage(player, MessageKey.of("afterlanguage", "error.save_failed"));
                    return null;
                });
    }

    /**
     * Lists all available languages.
     */
    @SubCommand(name = "list")
    public void listLanguages(@NotNull Player player) {
        String currentLang = registry.getPlayerLanguageRepo()
                .getCachedLanguage(player.getUniqueId())
                .orElse(registry.getDefaultLanguage().code());

        // Header
        sendMessage(player, MessageKey.of("afterlanguage", "general.language_list_header"));

        // List each language
        List<String> availableLanguages = getAvailableLanguages();
        for (String langCode : availableLanguages) {
            String langName = getLanguageName(langCode);
            String current = langCode.equals(currentLang) ? " &a(current)" : "";

            sendMessage(player, MessageKey.of("afterlanguage", "general.language_list_entry"),
                    Placeholder.of("code", langCode),
                    Placeholder.of("name", langName),
                    Placeholder.of("current", current));
        }
    }

    /**
     * Shows player's current language info.
     */
    @SubCommand(name = "info")
    public void showInfo(@NotNull Player player) {
        String currentLang = registry.getPlayerLanguageRepo()
                .getCachedLanguage(player.getUniqueId())
                .orElse(registry.getDefaultLanguage().code());

        String langName = getLanguageName(currentLang);

        sendMessage(player, MessageKey.of("afterlanguage", "general.language_info"),
                Placeholder.of("code", currentLang),
                Placeholder.of("name", langName));
    }

    /**
     * Gets available language codes from config.
     */
    @NotNull
    private List<String> getAvailableLanguages() {
        var languagesSection = registry.getPlugin().getConfig()
                .getConfigurationSection("language.languages");

        if (languagesSection != null) {
            return List.copyOf(languagesSection.getKeys(false));
        }

        return List.of(registry.getDefaultLanguage().code());
    }

    /**
     * Gets language display name.
     */
    @NotNull
    private String getLanguageName(@NotNull String code) {
        String configName = registry.getPlugin().getConfig()
                .getString("language.languages." + code + ".name");

        if (configName != null) {
            return configName;
        }

        // Fallback to common names
        return switch (code) {
            case "pt_br" -> "Português (Brasil)";
            case "en_us" -> "English (US)";
            case "es_es" -> "Español (España)";
            default -> code;
        };
    }

    /**
     * Sends a translated message to player.
     */
    private void sendMessage(@NotNull Player player, @NotNull MessageKey key, @NotNull Placeholder... placeholders) {
        String language = registry.getPlayerLanguageRepo()
                .getCachedLanguage(player.getUniqueId())
                .orElse(registry.getDefaultLanguage().code());

        String message = registry.getMessageResolver()
                .resolve(language, key.namespace(), key.path(), placeholders);

        if (!message.isEmpty()) {
            player.sendMessage(message.replace("&", "§"));
        }
    }
}
