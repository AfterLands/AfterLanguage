package com.afterlands.afterlanguage.infra.command;

import com.afterlands.afterlanguage.bootstrap.PluginRegistry;
import com.afterlands.core.actions.ActionService;
import com.afterlands.core.actions.ActionSpec;
import com.afterlands.core.api.AfterCoreAPI;
import com.afterlands.core.api.messages.MessageKey;
import com.afterlands.core.api.messages.Placeholder;
import com.afterlands.core.commands.annotations.Arg;
import com.afterlands.core.commands.annotations.Command;
import com.afterlands.core.commands.annotations.Permission;
import com.afterlands.core.commands.annotations.Sender;
import com.afterlands.core.commands.annotations.Subcommand;
import com.afterlands.core.commands.execution.CommandContext;
import com.afterlands.core.config.MessageService;
import com.afterlands.core.inventory.InventoryContext;
import com.afterlands.core.inventory.InventoryService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Player language management command.
 *
 * <h3>Subcommands:</h3>
 * <ul>
 *     <li>/lang - Opens GUI language selector (default)</li>
 *     <li>/lang gui - Opens GUI language selector</li>
 *     <li>/lang set {@literal <}language{@literal >} - Change your language</li>
 *     <li>/lang list - List available languages</li>
 *     <li>/lang info - Show your current language</li>
 * </ul>
 *
 * @author AfterLands Team
 * @since 1.1.0
 */
@Command(name = "lang", description = "Manage your language preferences")
@Permission("afterlanguage.use")
public class LangCommand {

    private final PluginRegistry registry;
    private final MessageService msgService;

    public LangCommand(@NotNull PluginRegistry registry) {
        this.registry = registry;
        this.msgService = registry.getMessageService();
    }

    /**
     * Default command - opens language selector GUI.
     * Executes when player runs /lang without arguments.
     */
    @Subcommand("")
    public void langDefault(CommandContext ctx, @Sender @NotNull Player player) {
        openLanguageSelector(player);
    }

    /**
     * Opens language selector GUI.
     * Command: /lang gui
     */
    @Subcommand("gui")
    public void gui(CommandContext ctx, @Sender @NotNull Player player) {
        openLanguageSelector(player);
    }

    /**
     * Sets player language.
     * Command: /lang set {@literal <}language{@literal >}
     *
     * @param player Player executing command
     * @param language Language code (pt_br, en_us, etc.)
     */
    @Subcommand("set")
    public void setLanguage(CommandContext ctx,
                           @Sender @NotNull Player player,
                           @Arg("language") @NotNull String language) {
        String newLanguage = language.toLowerCase();
        UUID playerId = player.getUniqueId();

        // Validate language
        List<String> availableLanguages = getAvailableLanguages();
        if (!availableLanguages.contains(newLanguage)) {
            msgService.send(player, key("messages.language.invalid"),
                    Placeholder.of("language", newLanguage));
            msgService.send(player, key("messages.language.invalid_hint"));
            return;
        }

        // Get old language
        String oldLanguage = registry.getPlayerLanguageRepo()
                .getCachedLanguage(playerId)
                .orElse(registry.getDefaultLanguage().code());

        // Save async
        registry.getPlayerLanguageRepo().setLanguage(playerId, newLanguage, false)
                .thenAccept(v -> {
                    // Send confirmation message in NEW language
                    msgService.send(player, key("messages.language.changed"),
                            Placeholder.of("language", newLanguage));

                    // Execute language-change actions if configured
                    executeLanguageChangeActions(player, oldLanguage, newLanguage);
                })
                .exceptionally(ex -> {
                    msgService.send(player, key("messages.error.save_failed"));
                    registry.getLogger().warning("[LangCommand] Failed to save language for " +
                            player.getName() + ": " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Lists all available languages.
     * Command: /lang list
     */
    @Subcommand("list")
    public void listLanguages(CommandContext ctx, @Sender @NotNull Player player) {
        String currentLang = registry.getPlayerLanguageRepo()
                .getCachedLanguage(player.getUniqueId())
                .orElse(registry.getDefaultLanguage().code());

        // Header
        msgService.send(player, key("messages.language_list_header"));

        // List each language
        List<String> availableLanguages = getAvailableLanguages();
        for (String langCode : availableLanguages) {
            String langName = getLanguageName(langCode);

            if (langCode.equals(currentLang)) {
                msgService.send(player, key("messages.language_list_entry_current"),
                        Placeholder.of("code", langCode),
                        Placeholder.of("name", langName));
            } else {
                msgService.send(player, key("messages.language_list_entry"),
                        Placeholder.of("code", langCode),
                        Placeholder.of("name", langName));
            }
        }
    }

    /**
     * Shows current language info.
     * Command: /lang info
     */
    @Subcommand("info")
    public void showInfo(CommandContext ctx, @Sender @NotNull Player player) {
        String currentLang = registry.getPlayerLanguageRepo()
                .getCachedLanguage(player.getUniqueId())
                .orElse(registry.getDefaultLanguage().code());

        String langName = getLanguageName(currentLang);

        msgService.send(player, key("messages.language.current_info"),
                Placeholder.of("language", currentLang),
                Placeholder.of("name", langName));
        msgService.send(player, key("messages.language.change_hint"));
    }

    // ══════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════

    /**
     * Opens the language selector GUI for a player.
     */
    private void openLanguageSelector(@NotNull Player player) {
        try {
            // Get AfterCore InventoryService
            AfterCoreAPI afterCore = com.afterlands.core.api.AfterCore.get();
            if (afterCore == null) {
                registry.getMessageService().send(player, key("messages.error.aftercore_unavailable"));
                return;
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
                    .resolve(currentLang, "afterlanguage", "messages.gui.selector.current_lang");
            placeholders.put("current_lang_indicator", indicator);

            // Create context with player ID and inventory ID
            InventoryContext context = new InventoryContext(player.getUniqueId(), "language_selector")
                    .withPlaceholders(placeholders);

            // Open inventory using inventory ID directly
            inventoryService.openInventory(player, "language_selector", context);

        } catch (Exception e) {
            registry.getMessageService().send(player, key("messages.error.gui_failed"));
            registry.getLogger().warning("[LangCommand] Failed to open GUI for " +
                    player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Gets available language codes from config.
     */
    @NotNull
    private List<String> getAvailableLanguages() {
        List<String> enabled = registry.getPlugin().getConfig().getStringList("enabled-languages");
        if (!enabled.isEmpty()) {
            return enabled;
        }
        return List.of(registry.getDefaultLanguage().code());
    }

    /**
     * Gets language display name.
     */
    @NotNull
    private String getLanguageName(@NotNull String code) {
        String configName = registry.getPlugin().getConfig()
                .getString("language-names." + code);

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
     * Creates a MessageKey for the "afterlanguage" namespace.
     */
    @NotNull
    private static MessageKey key(@NotNull String path) {
        return MessageKey.of("afterlanguage", path);
    }

    /**
     * Executes language-change actions from config.
     *
     * @param player Player
     * @param oldLang Old language
     * @param newLang New language
     */
    private void executeLanguageChangeActions(@NotNull Player player, @NotNull String oldLang,
                                             @NotNull String newLang) {
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
            registry.getLogger().warning("[LangCommand] Failed to execute language-change actions: " +
                    e.getMessage());
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
}
