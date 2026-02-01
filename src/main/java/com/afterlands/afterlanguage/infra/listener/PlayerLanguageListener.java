package com.afterlands.afterlanguage.infra.listener;

import com.afterlands.afterlanguage.infra.persistence.PlayerLanguageRepository;
import com.afterlands.core.actions.ActionService;
import com.afterlands.core.actions.ActionSpec;
import com.afterlands.core.api.AfterCoreAPI;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Handles player join/quit events for language persistence.
 *
 * <h3>Responsibilities:</h3>
 * <ul>
 *     <li>Load player language preference on join</li>
 *     <li>Auto-detect language from client locale if first join</li>
 *     <li>Execute configured actions on language events</li>
 *     <li>Save player language on quit</li>
 * </ul>
 *
 * <h3>Actions Configuration:</h3>
 * <pre>
 * actions:
 *   first-join:
 *     - "[open_panel] language_selector"
 *   language-change:
 *     any:
 *       - "[sound] LEVEL_UP 1.0 1.0"
 *     pt_br:
 *       - "[message] &aIdioma alterado para Português!"
 * </pre>
 *
 * <h3>Thread Safety:</h3>
 * <p>Event handlers are lightweight. Heavy I/O is delegated async.</p>
 */
public class PlayerLanguageListener implements Listener {

    private final PlayerLanguageRepository repository;
    private final String defaultLanguage;
    private final Logger logger;
    private final boolean debug;
    private final FileConfiguration config;
    private final AfterCoreAPI afterCore;

    // Track first join players to execute first-join actions
    private final Set<UUID> firstJoinPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Creates player language listener.
     *
     * @param repository Player language repository
     * @param defaultLanguage Default language code
     * @param config Plugin configuration
     * @param afterCore AfterCore API
     * @param logger Logger
     * @param debug Enable debug logging
     */
    public PlayerLanguageListener(
            @NotNull PlayerLanguageRepository repository,
            @NotNull String defaultLanguage,
            @NotNull FileConfiguration config,
            @NotNull AfterCoreAPI afterCore,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.repository = repository;
        this.defaultLanguage = defaultLanguage;
        this.config = config;
        this.afterCore = afterCore;
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Loads player language on join.
     *
     * <p>If player has no saved language, auto-detects from client locale.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Load async (don't block main thread)
        repository.get(playerId)
                .thenAccept(dataOpt -> {
                    if (dataOpt.isEmpty()) {
                        // First join - mark for first-join actions
                        firstJoinPlayers.add(playerId);

                        // Auto-detect from client locale
                        String clientLocale = detectClientLocale(player);
                        String language = normalizeLocale(clientLocale);

                        if (debug) {
                            logger.info("[PlayerLanguageListener] First join for " + player.getName() +
                                       " - detected locale: " + clientLocale + " -> " + language);
                        }

                        // Save with auto-detected flag
                        repository.setLanguage(playerId, language, true)
                                .thenRun(() -> {
                                    // Execute first-join actions
                                    executeFirstJoinActions(player);
                                })
                                .exceptionally(ex -> {
                                    logger.warning("[PlayerLanguageListener] Failed to save language for " +
                                                  player.getName() + ": " + ex.getMessage());
                                    return null;
                                });
                    } else {
                        if (debug) {
                            logger.fine("[PlayerLanguageListener] Loaded language for " + player.getName() +
                                       ": " + dataOpt.get().language());
                        }
                    }
                })
                .exceptionally(ex -> {
                    logger.warning("[PlayerLanguageListener] Failed to load language for " +
                                  player.getName() + ": " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Saves player language on quit.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Trigger async save (repository handles caching)
        repository.getCachedLanguage(playerId).ifPresent(language -> {
            if (debug) {
                logger.fine("[PlayerLanguageListener] Saving language for " + player.getName() + ": " + language);
            }

            repository.setLanguage(playerId, language, false)
                    .exceptionally(ex -> {
                        logger.warning("[PlayerLanguageListener] Failed to save language on quit for " +
                                      player.getName() + ": " + ex.getMessage());
                        return null;
                    });
        });
    }

    /**
     * Detects client locale from player.
     *
     * <p>Uses Spigot API to get client locale (e.g., "pt_BR", "en_US").</p>
     *
     * @param player Player
     * @return Client locale or default if unavailable
     */
    @NotNull
    private String detectClientLocale(@NotNull Player player) {
        try {
            // Spigot 1.8.8+ has getLocale() but it's not in all builds
            // For safety, we'll try reflection
            String locale = player.spigot().getLocale();
            if (locale != null && !locale.isEmpty()) {
                return locale;
            }
        } catch (Exception e) {
            if (debug) {
                logger.fine("[PlayerLanguageListener] Failed to detect client locale: " + e.getMessage());
            }
        }

        return defaultLanguage;
    }

    /**
     * Normalizes locale to language code.
     *
     * <p>Converts "pt_BR" -> "pt_br", "en_US" -> "en_us".</p>
     *
     * @param locale Client locale
     * @return Normalized language code
     */
    @NotNull
    private String normalizeLocale(@NotNull String locale) {
        if (locale == null || locale.isEmpty()) {
            return defaultLanguage;
        }

        // Convert to lowercase and replace hyphens with underscores
        String normalized = locale.toLowerCase().replace("-", "_");

        // Validate format (e.g., "pt_br", "en_us")
        if (normalized.matches("[a-z]{2}_[a-z]{2}")) {
            return normalized;
        }

        // If only language code (e.g., "pt", "en"), try to map to default variant
        if (normalized.matches("[a-z]{2}")) {
            return switch (normalized) {
                case "pt" -> "pt_br";
                case "en" -> "en_us";
                case "es" -> "es_es";
                case "fr" -> "fr_fr";
                case "de" -> "de_de";
                default -> defaultLanguage;
            };
        }

        return defaultLanguage;
    }

    /**
     * Executes first-join actions from config.
     *
     * @param player Player
     */
    private void executeFirstJoinActions(@NotNull Player player) {
        List<String> actions = config.getStringList("actions.first-join");

        if (actions.isEmpty()) {
            return;
        }

        if (debug) {
            logger.info("[PlayerLanguageListener] Executing first-join actions for " + player.getName());
        }

        executeActions(player, actions, Map.of());
    }

    /**
     * Executes language-change actions from config.
     *
     * @param player Player
     * @param oldLang Old language code
     * @param newLang New language code
     */
    public void executeLanguageChangeActions(@NotNull Player player, @NotNull String oldLang, @NotNull String newLang) {
        // Execute "any" actions
        List<String> anyActions = config.getStringList("actions.language-change.any");
        if (!anyActions.isEmpty()) {
            executeActions(player, anyActions, Map.of(
                    "old_language", oldLang,
                    "new_language", newLang,
                    "language_name", getLanguageName(newLang)
            ));
        }

        // Execute language-specific actions
        List<String> langActions = config.getStringList("actions.language-change." + newLang);
        if (!langActions.isEmpty()) {
            executeActions(player, langActions, Map.of(
                    "old_language", oldLang,
                    "new_language", newLang,
                    "language_name", getLanguageName(newLang)
            ));
        }
    }

    /**
     * Executes a list of actions with placeholder replacement.
     *
     * @param player Player
     * @param actions List of action strings
     * @param placeholders Placeholder replacements
     */
    private void executeActions(@NotNull Player player, @NotNull List<String> actions, @NotNull Map<String, String> placeholders) {
        ActionService actionService = afterCore.actions();

        for (String action : actions) {
            // Replace placeholders
            String processed = action;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                processed = processed.replace("{" + entry.getKey() + "}", entry.getValue());
            }

            try {
                // Parse and execute action
                ActionSpec spec = actionService.parse(processed);
                afterCore.executeAction(spec, player);
            } catch (Exception e) {
                logger.warning("[PlayerLanguageListener] Failed to execute action '" + action + "': " + e.getMessage());
                if (debug) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Gets language display name.
     *
     * @param code Language code
     * @return Display name
     */
    @NotNull
    private String getLanguageName(@NotNull String code) {
        String configName = config.getString("language-names." + code);

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
