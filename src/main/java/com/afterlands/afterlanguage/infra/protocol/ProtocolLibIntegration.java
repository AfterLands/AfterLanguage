package com.afterlands.afterlanguage.infra.protocol;

import com.afterlands.afterlanguage.AfterLanguagePlugin;
import com.afterlands.afterlanguage.infra.persistence.PlayerLanguageRepository;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * ProtocolLib integration for auto-detecting client locale.
 *
 * <p>Listens to Client Settings packet to extract player's configured locale
 * and automatically maps it to server language configuration.</p>
 *
 * <h3>Features:</h3>
 * <ul>
 *     <li>Auto-detects client locale on join</li>
 *     <li>Maps client locale to server language (e.g., en_US -> en_us)</li>
 *     <li>Only sets language if not already configured or if auto-detected</li>
 *     <li>Graceful degradation if ProtocolLib is not available</li>
 * </ul>
 *
 * <h3>Configuration (config.yml):</h3>
 * <pre>
 * detection:
 *   auto-detect: true
 *   locale-mapping:
 *     en_gb: en_us
 *     pt_pt: pt_br
 * </pre>
 */
public class ProtocolLibIntegration {

    private final AfterLanguagePlugin plugin;
    private final PlayerLanguageRepository repository;
    private final Logger logger;
    private final boolean debug;

    private final Map<String, String> localeMapping;
    private final String defaultLanguage;
    private final Set<UUID> processedPlayers;

    private PacketAdapter packetAdapter;

    /**
     * Creates ProtocolLib integration.
     *
     * @param plugin Plugin instance
     * @param repository Player language repository
     * @param logger Logger
     * @param debug Enable debug logging
     */
    public ProtocolLibIntegration(
            @NotNull AfterLanguagePlugin plugin,
            @NotNull PlayerLanguageRepository repository,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.logger = logger;
        this.debug = debug;

        this.localeMapping = new ConcurrentHashMap<>();
        this.defaultLanguage = plugin.getConfig().getString("default-language", "pt_br");
        this.processedPlayers = ConcurrentHashMap.newKeySet();

        loadLocaleMapping();
    }

    /**
     * Enables ProtocolLib integration.
     *
     * <p>Registers packet listener if ProtocolLib is available.</p>
     */
    public void enable() {
        if (!plugin.getConfig().getBoolean("detection.auto-detect", true)) {
            logger.info("[ProtocolLib] Auto-detection disabled in config");
            return;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            logger.info("[ProtocolLib] Not found, locale auto-detection disabled");
            return;
        }

        try {
            setupPacketListener();
            logger.info("[ProtocolLib] Locale auto-detection enabled");
        } catch (Exception e) {
            logger.warning("[ProtocolLib] Failed to setup integration: " + e.getMessage());
            if (debug) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Disables ProtocolLib integration.
     *
     * <p>Unregisters packet listener and clears processed players cache.</p>
     */
    public void disable() {
        if (packetAdapter != null) {
            try {
                ProtocolLibrary.getProtocolManager().removePacketListener(packetAdapter);
                logger.info("[ProtocolLib] Packet listener unregistered");
            } catch (Exception e) {
                logger.warning("[ProtocolLib] Failed to unregister listener: " + e.getMessage());
            }
        }

        processedPlayers.clear();
    }

    /**
     * Sets up packet listener for Client Settings.
     */
    private void setupPacketListener() {
        this.packetAdapter = new PacketAdapter(plugin, PacketType.Play.Client.SETTINGS) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handleClientSettings(event);
            }
        };

        ProtocolLibrary.getProtocolManager().addPacketListener(packetAdapter);

        if (debug) {
            logger.info("[ProtocolLib] Registered packet listener for Client Settings");
        }
    }

    /**
     * Handles Client Settings packet.
     *
     * @param event Packet event
     */
    private void handleClientSettings(@NotNull PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();

        // Avoid processing multiple times in same session
        if (processedPlayers.contains(playerId)) {
            return;
        }

        try {
            // Read locale from packet (field 0 = locale string)
            String clientLocale = event.getPacket().getStrings().read(0);

            if (clientLocale == null || clientLocale.isEmpty()) {
                if (debug) {
                    logger.info("[ProtocolLib] No client locale for " + player.getName());
                }
                return;
            }

            String mappedLocale = mapLocale(clientLocale);

            if (debug) {
                logger.info(String.format(
                        "[ProtocolLib] Detected locale for %s: %s -> %s",
                        player.getName(), clientLocale, mappedLocale
                ));
            }

            // Check if player already has language configured
            repository.get(playerId).thenAccept(dataOpt -> {
                boolean shouldAutoSet = dataOpt.isEmpty() ||
                        (dataOpt.isPresent() && dataOpt.get().autoDetected());

                if (shouldAutoSet) {
                    // Auto-set language with auto_detected=true
                    repository.setLanguage(playerId, mappedLocale, true).thenRun(() -> {
                        logger.info(String.format(
                                "[ProtocolLib] Auto-set language for %s: %s",
                                player.getName(), mappedLocale
                        ));

                        // Mark as processed
                        processedPlayers.add(playerId);
                    }).exceptionally(ex -> {
                        logger.warning("[ProtocolLib] Failed to set language for " +
                                player.getName() + ": " + ex.getMessage());
                        return null;
                    });
                } else {
                    if (debug) {
                        logger.info("[ProtocolLib] Player " + player.getName() +
                                " has manual language preference, skipping auto-detect");
                    }
                    processedPlayers.add(playerId);
                }
            }).exceptionally(ex -> {
                logger.warning("[ProtocolLib] Failed to check language for " +
                        player.getName() + ": " + ex.getMessage());
                return null;
            });

        } catch (Exception e) {
            logger.warning("[ProtocolLib] Failed to read client locale for " +
                    player.getName() + ": " + e.getMessage());
            if (debug) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Maps client locale to server language code.
     *
     * <p>Applies normalization (e.g., en_US -> en_us) and configured mappings.</p>
     *
     * @param clientLocale Client locale string (e.g., "en_US", "pt-BR")
     * @return Mapped language code
     */
    @NotNull
    private String mapLocale(@NotNull String clientLocale) {
        if (clientLocale == null || clientLocale.isEmpty()) {
            return defaultLanguage;
        }

        // Normalize: en_US -> en_us, pt-BR -> pt_br
        String normalized = clientLocale.toLowerCase().replace('-', '_');

        // Apply mapping from config
        String mapped = localeMapping.getOrDefault(normalized, normalized);

        if (debug) {
            logger.info(String.format(
                    "[ProtocolLib] Locale mapping: %s -> %s -> %s",
                    clientLocale, normalized, mapped
            ));
        }

        return mapped;
    }

    /**
     * Loads locale mapping from config.
     */
    private void loadLocaleMapping() {
        ConfigurationSection mappingSection = plugin.getConfig()
                .getConfigurationSection("detection.locale-mapping");

        if (mappingSection == null) {
            if (debug) {
                logger.info("[ProtocolLib] No locale mappings configured");
            }
            return;
        }

        for (String key : mappingSection.getKeys(false)) {
            String value = mappingSection.getString(key);
            if (value != null) {
                localeMapping.put(key.toLowerCase(), value.toLowerCase());
            }
        }

        logger.info("[ProtocolLib] Loaded " + localeMapping.size() + " locale mappings");
    }

    /**
     * Clears processed players cache.
     *
     * <p>Allows re-detection on next settings packet.</p>
     */
    public void clearProcessedPlayers() {
        processedPlayers.clear();
        if (debug) {
            logger.info("[ProtocolLib] Cleared processed players cache");
        }
    }

    /**
     * Gets number of processed players.
     *
     * @return Number of players with detected locale
     */
    public int getProcessedPlayerCount() {
        return processedPlayers.size();
    }
}
