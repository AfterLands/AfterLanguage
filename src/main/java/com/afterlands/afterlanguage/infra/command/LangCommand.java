package com.afterlands.afterlanguage.infra.command;

import com.afterlands.afterlanguage.bootstrap.PluginRegistry;
import com.afterlands.core.api.messages.MessageKey;
import com.afterlands.core.api.messages.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Command: /lang
 *
 * <h3>Subcommands:</h3>
 * <ul>
 *     <li>/lang set {@literal <}language{@literal >} - Change your language</li>
 *     <li>/lang list - List available languages</li>
 *     <li>/lang info - Show your current language</li>
 * </ul>
 */
public class LangCommand implements CommandExecutor, TabCompleter {

    private final PluginRegistry registry;

    public LangCommand(@NotNull PluginRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            // Default: show info
            return handleInfo(player);
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            case "set" -> handleSet(player, args);
            case "list" -> handleList(player);
            case "info" -> handleInfo(player);
            default -> {
                sendMessage(player, "§cUnknown subcommand. Usage: /lang <set|list|info>");
                yield true;
            }
        };
    }

    /**
     * Handles /lang set {@literal <}language{@literal >}
     */
    private boolean handleSet(@NotNull Player player, @NotNull String[] args) {
        if (args.length < 2) {
            sendMessage(player, "§cUsage: /lang set <language>");
            return true;
        }

        String language = args[1].toLowerCase();
        UUID playerId = player.getUniqueId();

        // Validate language
        List<String> availableLanguages = getAvailableLanguages();
        if (!availableLanguages.contains(language)) {
            sendMessage(player, "§cInvalid language: " + language);
            sendMessage(player, "§7Use /lang list to see available languages.");
            return true;
        }

        // Save async
        registry.getPlayerLanguageRepo().setLanguage(playerId, language, false)
                .thenAccept(v -> {
                    // Send confirmation message in NEW language
                    sendTranslatedMessage(player, language, "general.language_changed",
                            Placeholder.of("language", language));
                })
                .exceptionally(ex -> {
                    sendMessage(player, "§cFailed to save language preference.");
                    registry.getLogger().warning("[LangCommand] Failed to save language for " + player.getName() + ": " + ex.getMessage());
                    return null;
                });

        return true;
    }

    /**
     * Handles /lang list
     */
    private boolean handleList(@NotNull Player player) {
        String currentLang = registry.getPlayerLanguageRepo()
                .getCachedLanguage(player.getUniqueId())
                .orElse(registry.getDefaultLanguage().code());

        // Header
        sendTranslatedMessage(player, currentLang, "general.language_list_header");

        // List each language
        List<String> availableLanguages = getAvailableLanguages();
        for (String langCode : availableLanguages) {
            String langName = getLanguageName(langCode);
            String current = langCode.equals(currentLang) ? " §a(current)" : "";

            sendMessage(player, "  §7- §e" + langCode + " §7(" + langName + ")" + current);
        }

        return true;
    }

    /**
     * Handles /lang info
     */
    private boolean handleInfo(@NotNull Player player) {
        String currentLang = registry.getPlayerLanguageRepo()
                .getCachedLanguage(player.getUniqueId())
                .orElse(registry.getDefaultLanguage().code());

        String langName = getLanguageName(currentLang);

        sendMessage(player, "§7Your current language: §e" + currentLang + " §7(" + langName + ")");
        sendMessage(player, "§7Use §e/lang set <language> §7to change.");

        return true;
    }

    /**
     * Gets available language codes from config.
     */
    @NotNull
    private List<String> getAvailableLanguages() {
        var languagesSection = registry.getPlugin().getConfig()
                .getConfigurationSection("language.languages");

        if (languagesSection != null) {
            return new ArrayList<>(languagesSection.getKeys(false));
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
     * Sends a message to player.
     */
    private void sendMessage(@NotNull Player player, @NotNull String message) {
        player.sendMessage(message.replace("&", "§"));
    }

    /**
     * Sends a translated message to player.
     */
    private void sendTranslatedMessage(@NotNull Player player, @NotNull String language, @NotNull String key, @NotNull Placeholder... placeholders) {
        String message = registry.getMessageResolver()
                .resolve(language, "afterlanguage", key, placeholders);

        if (!message.isEmpty()) {
            sendMessage(player, message);
        }
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Subcommands
            completions.add("set");
            completions.add("list");
            completions.add("info");

            String input = args[0].toLowerCase();
            completions.removeIf(s -> !s.startsWith(input));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            // Language codes
            String input = args[1].toLowerCase();
            for (String lang : getAvailableLanguages()) {
                if (lang.startsWith(input)) {
                    completions.add(lang);
                }
            }
        }

        return completions;
    }
}
