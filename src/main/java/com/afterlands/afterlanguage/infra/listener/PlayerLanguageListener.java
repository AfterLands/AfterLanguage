package com.afterlands.afterlanguage.infra.listener;

import com.afterlands.afterlanguage.infra.persistence.PlayerLanguageRepository;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles player join/quit events for language persistence.
 *
 * <h3>Responsibilities:</h3>
 * <ul>
 *     <li>Load player language preference on join</li>
 *     <li>Auto-detect language from client locale if first join</li>
 *     <li>Save player language on quit</li>
 * </ul>
 *
 * <h3>Thread Safety:</h3>
 * <p>Event handlers are lightweight. Heavy I/O is delegated async.</p>
 */
public class PlayerLanguageListener implements Listener {

    private final PlayerLanguageRepository repository;
    private final String defaultLanguage;
    private final Logger logger;
    private final boolean debug;

    /**
     * Creates player language listener.
     *
     * @param repository Player language repository
     * @param defaultLanguage Default language code
     * @param logger Logger
     * @param debug Enable debug logging
     */
    public PlayerLanguageListener(
            @NotNull PlayerLanguageRepository repository,
            @NotNull String defaultLanguage,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.repository = repository;
        this.defaultLanguage = defaultLanguage;
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
                        // First join - auto-detect from client locale
                        String clientLocale = detectClientLocale(player);
                        String language = normalizeLocale(clientLocale);

                        if (debug) {
                            logger.info("[PlayerLanguageListener] First join for " + player.getName() +
                                       " - detected locale: " + clientLocale + " -> " + language);
                        }

                        // Save with auto-detected flag
                        repository.setLanguage(playerId, language, true)
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
}
