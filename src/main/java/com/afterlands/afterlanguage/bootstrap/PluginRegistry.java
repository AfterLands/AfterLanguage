package com.afterlands.afterlanguage.bootstrap;

import com.afterlands.afterlanguage.AfterLanguagePlugin;
import com.afterlands.afterlanguage.api.model.Language;
import com.afterlands.afterlanguage.core.cache.TranslationCache;
import com.afterlands.afterlanguage.core.resolver.MessageResolver;
import com.afterlands.afterlanguage.core.resolver.NamespaceManager;
import com.afterlands.afterlanguage.core.resolver.TranslationRegistry;
import com.afterlands.afterlanguage.core.resolver.YamlTranslationLoader;
import com.afterlands.afterlanguage.core.template.TemplateEngine;
import com.afterlands.afterlanguage.infra.command.AfterLangCommand;
import com.afterlands.afterlanguage.infra.command.LangCommand;
import com.afterlands.afterlanguage.infra.listener.PlayerLanguageListener;
import com.afterlands.afterlanguage.infra.papi.AfterLanguageExpansion;
import com.afterlands.afterlanguage.infra.persistence.DynamicTranslationRepository;
import com.afterlands.afterlanguage.infra.persistence.PlayerLanguageRepository;
import com.afterlands.afterlanguage.infra.protocol.ProtocolLibIntegration;
import com.afterlands.afterlanguage.infra.service.MessageServiceImpl;
import com.afterlands.core.api.AfterCore;
import com.afterlands.core.api.AfterCoreAPI;
import com.afterlands.core.config.MessageService;
import com.afterlands.core.database.SqlDataSource;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Dependency Injection container for AfterLanguage.
 *
 * <p>Initializes and manages all services in dependency order.</p>
 *
 * <h3>Service Initialization Order:</h3>
 * <ol>
 *     <li>Load configuration and validate AfterCore</li>
 *     <li>Initialize database (migrations via AfterCore SqlService)</li>
 *     <li>Create core services (Registry, Cache, TemplateEngine)</li>
 *     <li>Create persistence (PlayerLanguageRepository, DynamicTranslationRepository)</li>
 *     <li>Create MessageResolver</li>
 *     <li>Create file loading (YamlTranslationLoader, NamespaceManager)</li>
 *     <li>Load default namespace ("afterlanguage")</li>
 *     <li>Create MessageServiceImpl</li>
 *     <li>Register as AfterCore MessageService provider</li>
 *     <li>Register listeners and commands</li>
 * </ol>
 */
public class PluginRegistry {

    private final AfterLanguagePlugin plugin;
    private final Logger logger;
    private final boolean debug;

    // AfterCore integration
    private AfterCoreAPI afterCore;
    private SqlDataSource dataSource;

    // Core services
    private Language defaultLanguage;
    private TranslationRegistry registry;
    private TranslationCache cache;
    private TemplateEngine templateEngine;

    // Persistence
    private PlayerLanguageRepository playerLanguageRepo;
    private DynamicTranslationRepository dynamicTranslationRepo;

    // Resolvers
    private MessageResolver messageResolver;

    // File loading
    private YamlTranslationLoader yamlLoader;
    private NamespaceManager namespaceManager;

    // Provider
    private MessageServiceImpl messageService;

    // Listeners
    private PlayerLanguageListener playerLanguageListener;

    // Commands
    private LangCommand langCommand;
    private AfterLangCommand afterLangCommand;

    // Integrations
    private ProtocolLibIntegration protocolLibIntegration;
    private AfterLanguageExpansion placeholderExpansion;

    public PluginRegistry(@NotNull AfterLanguagePlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.debug = plugin.getConfig().getBoolean("debug", false);
    }

    /**
     * Initializes all services in dependency order.
     */
    public void initialize() {
        logger.info("[Registry] Initializing services...");

        try {
            // 1. Validate AfterCore
            this.afterCore = AfterCore.get();
            if (afterCore == null) {
                throw new IllegalStateException("AfterCore not found! Ensure AfterCore is loaded before AfterLanguage.");
            }

            // 2. Get database datasource
            String datasourceName = plugin.getConfig().getString("database.datasource", "default");
            this.dataSource = afterCore.sql(datasourceName);
            if (dataSource == null) {
                throw new IllegalStateException("Datasource '" + datasourceName + "' not found in AfterCore!");
            }

            // 3. Register migrations
            registerMigrations();

            // 4. Initialize default language
            String defaultLangCode = plugin.getConfig().getString("language.default", "pt_br");
            String defaultLangName = plugin.getConfig().getString("language.languages." + defaultLangCode + ".name", "Português (Brasil)");
            this.defaultLanguage = new Language(defaultLangCode, defaultLangName, true);

            logger.info("[Registry] Default language: " + defaultLanguage.name() + " [" + defaultLanguage.code() + "]");

            // 5. Create core services
            this.registry = new TranslationRegistry(debug);
            this.cache = new TranslationCache(
                    plugin.getConfig().getInt("cache.l1.max-size", 10000),
                    (int) (plugin.getConfig().getLong("cache.l1.ttl-seconds", 300) / 60),
                    plugin.getConfig().getInt("cache.l3.max-size", 5000),
                    (int) (plugin.getConfig().getLong("cache.l3.ttl-seconds", 600) / 60),
                    debug
            );
            this.templateEngine = new TemplateEngine(debug);

            logger.info("[Registry] Core services initialized (Registry, Cache, TemplateEngine)");

            // 6. Create persistence
            String playerTableName = plugin.getConfig().getString("database.tables.player-language", "afterlanguage_player_language");
            this.playerLanguageRepo = new PlayerLanguageRepository(
                    dataSource,
                    playerTableName,
                    logger,
                    debug
            );

            String dynamicTableName = plugin.getConfig().getString("database.tables.dynamic-translations", "afterlanguage_dynamic_translations");
            this.dynamicTranslationRepo = new DynamicTranslationRepository(
                    dataSource,
                    dynamicTableName,
                    logger,
                    debug
            );

            logger.info("[Registry] Persistence initialized (PlayerLanguageRepository, DynamicTranslationRepository)");

            // 7. Create MessageResolver
            this.messageResolver = new MessageResolver(
                    registry,
                    cache,
                    templateEngine,
                    defaultLanguage.code(),
                    plugin.getConfig().getBoolean("missing.show-key", true),
                    plugin.getConfig().getString("missing.format", "&c[Missing: {key}]"),
                    plugin.getConfig().getBoolean("missing.log", false),
                    debug
            );

            logger.info("[Registry] MessageResolver initialized");

            // 8. Create file loading
            Path languagesDir = plugin.getDataFolder().toPath().resolve("languages");
            if (!Files.exists(languagesDir)) {
                Files.createDirectories(languagesDir);
                logger.info("[Registry] Created languages directory: " + languagesDir);
            }

            this.yamlLoader = new YamlTranslationLoader(languagesDir, logger, debug);
            this.namespaceManager = new NamespaceManager(
                    languagesDir,
                    defaultLanguage,
                    yamlLoader,
                    registry,
                    cache,
                    logger,
                    debug
            );

            logger.info("[Registry] File loading initialized (YamlTranslationLoader, NamespaceManager)");

            // 9. Register default namespace ("afterlanguage")
            Path defaultNamespaceFolder = plugin.getDataFolder().toPath().resolve("languages")
                    .resolve(defaultLanguage.code()).resolve("afterlanguage");

            // Create example translation file if doesn't exist
            if (!Files.exists(defaultNamespaceFolder)) {
                Files.createDirectories(defaultNamespaceFolder);
                createExampleTranslations(defaultNamespaceFolder);
            }

            namespaceManager.registerNamespace("afterlanguage", defaultNamespaceFolder).join();
            logger.info("[Registry] Registered namespace: afterlanguage");

            // 10. Create MessageServiceImpl
            this.messageService = new MessageServiceImpl(
                    messageResolver,
                    playerLanguageRepo,
                    afterCore.metrics(),
                    defaultLanguage.code(),
                    logger,
                    debug
            );

            logger.info("[Registry] MessageServiceImpl created");

            // 11. Create listeners
            this.playerLanguageListener = new PlayerLanguageListener(
                    playerLanguageRepo,
                    defaultLanguage.code(),
                    plugin.getConfig(),
                    afterCore,
                    logger,
                    debug
            );

            // 12. Create integrations
            this.protocolLibIntegration = new ProtocolLibIntegration(
                    plugin,
                    playerLanguageRepo,
                    logger,
                    debug
            );

            this.placeholderExpansion = new AfterLanguageExpansion(
                    plugin,
                    playerLanguageRepo,
                    messageResolver,
                    defaultLanguage,
                    logger,
                    debug
            );

            logger.info("[Registry] Services initialized successfully!");

        } catch (Exception e) {
            logger.severe("[Registry] Failed to initialize services: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Service initialization failed", e);
        }
    }

    /**
     * Registers database migrations.
     */
    private void registerMigrations() {
        String datasourceName = plugin.getConfig().getString("database.datasource", "default");
        String playerTable = plugin.getConfig().getString("database.tables.player-language", "afterlanguage_player_language");
        String dynamicTable = plugin.getConfig().getString("database.tables.dynamic-translations", "afterlanguage_dynamic_translations");

        // Migration 1: Create player_language table
        afterCore.sql().registerMigration(datasourceName, "create_player_language_table", conn -> {
            String sql = """
                CREATE TABLE IF NOT EXISTS %s (
                    uuid VARCHAR(36) PRIMARY KEY,
                    language VARCHAR(10) NOT NULL,
                    auto_detected BOOLEAN DEFAULT FALSE,
                    first_join TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    INDEX idx_language (language)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.formatted(playerTable);

            try (var stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        });

        // Migration 2: Create dynamic_translations table
        afterCore.sql().registerMigration(datasourceName, "create_dynamic_translations_table", conn -> {
            String sql = """
                CREATE TABLE IF NOT EXISTS %s (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    namespace VARCHAR(64) NOT NULL,
                    translation_key VARCHAR(128) NOT NULL,
                    language VARCHAR(10) NOT NULL,
                    text TEXT NOT NULL,
                    plural_text TEXT,
                    source VARCHAR(16) DEFAULT 'manual',
                    status VARCHAR(16) DEFAULT 'pending',
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    UNIQUE KEY uk_translation (namespace, translation_key, language),
                    INDEX idx_namespace (namespace),
                    INDEX idx_language (language),
                    INDEX idx_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.formatted(dynamicTable);

            try (var stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        });

        logger.info("[Registry] Database migrations registered");
    }

    /**
     * Creates example translation files for testing.
     */
    private void createExampleTranslations(Path namespaceDir) throws IOException {
        Path generalFile = namespaceDir.resolve("general.yml");
        String exampleContent = """
            # AfterLanguage - Example Translations
            prefix: "&7[&6AfterLang&7]"
            language_changed: "&aLanguage changed to: &f{language}"
            language_list_header: "&7Available languages:"
            language_list_entry: "&7- &e{code} &7({name})"
            reload_success: "&aTranslations reloaded successfully!"
            reload_failed: "&cFailed to reload translations."
            stats_header: "&7=== AfterLanguage Stats ==="
            stats_namespaces: "&7Registered namespaces: &e{count}"
            stats_translations: "&7Total translations: &e{count}"
            stats_players: "&7Players with custom language: &e{count}"
            """;

        Files.writeString(generalFile, exampleContent);
        logger.info("[Registry] Created example translations at: " + generalFile);
    }

    /**
     * Registers MessageService provider in Bukkit ServicesManager.
     */
    public void registerMessageServiceProvider() {
        ServicesManager services = Bukkit.getServicesManager();
        services.register(MessageService.class, messageService, plugin, ServicePriority.Normal);
        logger.info("[Registry] Registered as MessageService provider");
    }

    /**
     * Unregisters MessageService provider.
     */
    public void unregisterMessageServiceProvider() {
        ServicesManager services = Bukkit.getServicesManager();
        services.unregister(MessageService.class, messageService);
        logger.info("[Registry] Unregistered MessageService provider");
    }

    /**
     * Registers Bukkit listeners.
     */
    public void registerListeners() {
        Bukkit.getPluginManager().registerEvents(playerLanguageListener, plugin);
        logger.info("[Registry] Listeners registered");
    }

    /**
     * Registers commands.
     */
    public void registerCommands() {
        // Create command instances
        this.langCommand = new LangCommand(this);
        this.afterLangCommand = new AfterLangCommand(this);

        // Register commands via Bukkit
        var langCmd = plugin.getCommand("lang");
        if (langCmd != null) {
            langCmd.setExecutor(langCommand);
            langCmd.setTabCompleter(langCommand);
        } else {
            logger.warning("[Registry] Command /lang not found in plugin.yml!");
        }

        var afterLangCmd = plugin.getCommand("afterlang");
        if (afterLangCmd != null) {
            afterLangCmd.setExecutor(afterLangCommand);
            afterLangCmd.setTabCompleter(afterLangCommand);
        } else {
            logger.warning("[Registry] Command /afterlang not found in plugin.yml!");
        }

        logger.info("[Registry] Commands registered");
    }

    /**
     * Saves all pending player data.
     */
    public void saveAllPending() {
        logger.info("[Registry] Saving all pending data...");

        // Save all cached player languages
        playerLanguageRepo.saveAll()
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    logger.warning("[Registry] Failed to save all player data: " + ex.getMessage());
                    return null;
                })
                .join();

        logger.info("[Registry] All pending data saved");
    }

    /**
     * Enables integrations.
     */
    public void enableIntegrations() {
        // Enable ProtocolLib integration
        if (protocolLibIntegration != null) {
            protocolLibIntegration.enable();
        }

        // Register PlaceholderAPI expansion
        if (placeholderExpansion != null && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                placeholderExpansion.register();
                logger.info("[Registry] PlaceholderAPI expansion registered");
            } catch (Exception e) {
                logger.warning("[Registry] Failed to register PlaceholderAPI expansion: " + e.getMessage());
            }
        }
    }

    /**
     * Graceful shutdown of all services.
     */
    public void shutdown() {
        logger.info("[Registry] Shutting down services...");

        // Disable integrations
        if (protocolLibIntegration != null) {
            protocolLibIntegration.disable();
        }

        if (placeholderExpansion != null) {
            try {
                placeholderExpansion.unregister();
            } catch (Exception e) {
                // Ignore unregister errors
            }
        }

        // Clear caches
        if (cache != null) {
            cache.invalidateAll();
        }

        logger.info("[Registry] Services shut down successfully!");
    }

    // ══════════════════════════════════════════════
    // GETTERS
    // ══════════════════════════════════════════════

    @NotNull
    public AfterLanguagePlugin getPlugin() {
        return plugin;
    }

    @NotNull
    public Logger getLogger() {
        return logger;
    }

    @NotNull
    public MessageResolver getMessageResolver() {
        return messageResolver;
    }

    @NotNull
    public NamespaceManager getNamespaceManager() {
        return namespaceManager;
    }

    @NotNull
    public PlayerLanguageRepository getPlayerLanguageRepo() {
        return playerLanguageRepo;
    }

    @NotNull
    public TranslationRegistry getRegistry() {
        return registry;
    }

    @NotNull
    public TranslationCache getCache() {
        return cache;
    }

    @NotNull
    public Language getDefaultLanguage() {
        return defaultLanguage;
    }
}
