package com.afterlands.afterlanguage.infra.command;

import com.afterlands.afterlanguage.bootstrap.PluginRegistry;
import com.afterlands.core.actions.ActionService;
import com.afterlands.core.actions.ActionSpec;
import com.afterlands.core.api.AfterCoreAPI;
import com.afterlands.core.api.messages.MessageKey;
import com.afterlands.core.api.messages.Placeholder;
import com.afterlands.core.inventory.InventoryContext;
import com.afterlands.core.inventory.InventoryService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command: /lang
 *
 * <h3>Subcommands:</h3>
 * <ul>
 *     <li>/lang - Opens GUI language selector (default)</li>
 *     <li>/lang gui - Opens GUI language selector</li>
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
            // Default: open GUI
            return handleGui(player);
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            case "gui" -> handleGui(player);
            case "set" -> handleSet(player, args);
            case "list" -> handleList(player);
            case "info" -> handleInfo(player);
            default -> {
                sendMessage(player, "§cUnknown subcommand. Usage: /lang <gui|set|list|info>");
                yield true;
            }
        };
    }

    /**
     * Handles /lang gui
     */
    private boolean handleGui(@NotNull Player player) {
        try {
            // Get AfterCore InventoryService
            AfterCoreAPI afterCore = com.afterlands.core.api.AfterCore.get();
            if (afterCore == null) {
                sendMessage(player, "§cAfterCore not available.");
                return true;
            }

            InventoryService inventoryService = afterCore.inventory();

            // Get current language
            String currentLang = registry.getPlayerLanguageRepo()
                    .getCachedLanguage(player.getUniqueId())
                    .orElse(registry.getDefaultLanguage().code());

            String currentLangName = getLanguageName(currentLang);

            // Build context with dynamic placeholders
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("current_language_name", currentLangName);
            placeholders.put("translation_percent", "100"); // TODO: Calculate actual percentage

            // Language-specific glow and indicators
            placeholders.put("is_current_pt_br", currentLang.equals("pt_br") ? "true" : "false");
            placeholders.put("is_current_en_us", currentLang.equals("en_us") ? "true" : "false");
            placeholders.put("is_current_es_es", currentLang.equals("es_es") ? "true" : "false");

            // Current language indicator
            String indicator = registry.getMessageResolver()
                    .resolve(currentLang, "afterlanguage", "gui.selector.current_lang");
            placeholders.put("current_lang_indicator", indicator);

            // Create context with player ID and inventory ID
            InventoryContext context = new InventoryContext(player.getUniqueId(), "language_selector")
                    .withPlaceholders(placeholders);

            // Open inventory using inventory ID directly
            inventoryService.openInventory(player, "language_selector", context);

        } catch (Exception e) {
            sendMessage(player, "§cFailed to open language selector.");
            registry.getLogger().warning("[LangCommand] Failed to open GUI for " +
                    player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        return true;
    }

    /**
     * Handles /lang set {@literal <}language{@literal >}
     */
    private boolean handleSet(@NotNull Player player, @NotNull String[] args) {
        if (args.length < 2) {
            sendMessage(player, "§cUsage: /lang set <language>");
            return true;
        }

        String newLanguage = args[1].toLowerCase();
        UUID playerId = player.getUniqueId();

        // Validate language
        List<String> availableLanguages = getAvailableLanguages();
        if (!availableLanguages.contains(newLanguage)) {
            sendMessage(player, "§cInvalid language: " + newLanguage);
            sendMessage(player, "§7Use /lang list to see available languages.");
            return true;
        }

        // Get old language
        String oldLanguage = registry.getPlayerLanguageRepo()
                .getCachedLanguage(playerId)
                .orElse(registry.getDefaultLanguage().code());

        // Save async
        registry.getPlayerLanguageRepo().setLanguage(playerId, newLanguage, false)
                .thenAccept(v -> {
                    // Send confirmation message in NEW language
                    sendTranslatedMessage(player, newLanguage, "general.language_changed",
                            Placeholder.of("language", newLanguage));

                    // Execute language-change actions if configured
                    executeLanguageChangeActions(player, oldLanguage, newLanguage);
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

    /**
     * Executes language-change actions from config.
     *
     * @param player Player
     * @param oldLang Old language
     * @param newLang New language
     */
    private void executeLanguageChangeActions(@NotNull Player player, @NotNull String oldLang, @NotNull String newLang) {
        try {
            // Get ActionService from AfterCore
            AfterCoreAPI afterCore = com.afterlands.core.api.AfterCore.get();
            if (afterCore == null) {
                return;
            }

            ActionService actionService = afterCore.actions();
            org.bukkit.configuration.file.FileConfiguration config = registry.getPlugin().getConfig();

            // Execute "any" actions
            List<String> anyActions = config.getStringList("actions.language-change.any");
            executeActions(actionService, player, anyActions, oldLang, newLang);

            // Execute language-specific actions
            List<String> langActions = config.getStringList("actions.language-change." + newLang);
            executeActions(actionService, player, langActions, oldLang, newLang);

        } catch (Exception e) {
            registry.getLogger().warning("[LangCommand] Failed to execute language-change actions: " + e.getMessage());
        }
    }

    /**
     * Executes a list of actions.
     */
    private void executeActions(
            @NotNull ActionService actionService,
            @NotNull Player player,
            @NotNull List<String> actions,
            @NotNull String oldLang,
            @NotNull String newLang
    ) {
        if (actions.isEmpty()) {
            return;
        }

        AfterCoreAPI afterCore = com.afterlands.core.api.AfterCore.get();
        if (afterCore == null) {
            return;
        }

        String languageName = getLanguageName(newLang);

        for (String action : actions) {
            String processed = action
                    .replace("{old_language}", oldLang)
                    .replace("{new_language}", newLang)
                    .replace("{language_name}", languageName);

            try {
                // Parse and execute action
                ActionSpec spec = actionService.parse(processed);
                afterCore.executeAction(spec, player);
            } catch (Exception e) {
                registry.getLogger().warning("[LangCommand] Failed to execute action: " + e.getMessage());
            }
        }
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Subcommands
            completions.add("gui");
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
