package com.afterlands.afterlanguage.bootstrap;

import com.afterlands.afterlanguage.AfterLanguagePlugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

/**
 * Manages plugin lifecycle and orchestrates startup/shutdown flows.
 *
 * <p>Implements the startup sequence defined in spec section 12.1.</p>
 *
 * <h3>Startup Sequence:</h3>
 * <ol>
 *     <li>Load config.yml and crowdin.yml</li>
 *     <li>Connect to database via AfterCore SqlService</li>
 *     <li>Create tables if not exist (migrations)</li>
 *     <li>Load translation files for enabled languages (populate L2)</li>
 *     <li>Pre-compile templates if enabled</li>
 *     <li>Register own namespace ("afterlanguage")</li>
 *     <li>Register PAPI expansion if available</li>
 *     <li>Start Crowdin auto-sync scheduler if enabled</li>
 *     <li>Register as MessageService provider in AfterCore</li>
 *     <li>Register listeners (PlayerJoin, PlayerQuit)</li>
 *     <li>Register commands (/lang, /afterlang)</li>
 * </ol>
 */
public class PluginLifecycle {

    private final AfterLanguagePlugin plugin;
    private final PluginRegistry registry;
    private final Logger logger;

    public PluginLifecycle(@NotNull AfterLanguagePlugin plugin, @NotNull PluginRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.logger = plugin.getLogger();
    }

    /**
     * Executes the startup sequence.
     *
     * <p>Initializes services and registers components.</p>
     */
    public void startup() {
        logger.info("[Lifecycle] Starting plugin...");

        // 1. Save default configs
        saveDefaultConfigs();

        // 2. Initialize services (creates all services and loads translations)
        registry.initialize();

        // 3. Register as AfterCore MessageService provider
        registry.registerMessageServiceProvider();

        // 4. Register listeners
        registry.registerListeners();

        // 5. Register commands
        registry.registerCommands();

        // 6. Enable integrations (ProtocolLib, PlaceholderAPI)
        registry.enableIntegrations();

        logger.info("[Lifecycle] Startup complete!");
    }

    /**
     * Executes the shutdown sequence.
     *
     * <p>Saves pending data and gracefully shuts down services.</p>
     */
    public void shutdown() {
        logger.info("[Lifecycle] Shutting down...");

        // 1. Unregister as provider
        registry.unregisterMessageServiceProvider();

        // 2. Save all pending player data
        registry.saveAllPending();

        // 3. Shutdown services
        registry.shutdown();

        logger.info("[Lifecycle] Shutdown complete!");
    }

    /**
     * Saves default configuration files.
     */
    private void saveDefaultConfigs() {
        plugin.saveDefaultConfig();

        if (!fileExists("crowdin.yml")) {
            plugin.saveResource("crowdin.yml", false);
        }

        if (!fileExists("messages.yml")) {
            plugin.saveResource("messages.yml", false);
        }

        logger.info("[Lifecycle] Default configs saved.");
    }

    /**
     * Checks if a file exists in the plugin's data folder.
     */
    private boolean fileExists(String fileName) {
        return new java.io.File(plugin.getDataFolder(), fileName).exists();
    }
}
