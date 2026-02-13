package com.afterlands.afterlanguage.bootstrap;

import com.afterlands.afterlanguage.AfterLanguagePlugin;
import com.afterlands.afterlanguage.api.model.Language;
import com.afterlands.afterlanguage.api.service.DynamicContentAPI;
import com.afterlands.afterlanguage.core.cache.TranslationCache;
import com.afterlands.afterlanguage.core.extractor.InventoryExtractor;
import com.afterlands.afterlanguage.core.extractor.MessageExtractor;
import com.afterlands.afterlanguage.core.io.TranslationBackupService;
import com.afterlands.afterlanguage.core.io.TranslationExporter;
import com.afterlands.afterlanguage.core.io.TranslationImporter;
import com.afterlands.afterlanguage.core.resolver.MessageResolver;
import com.afterlands.afterlanguage.core.resolver.NamespaceManager;
import com.afterlands.afterlanguage.core.resolver.TranslationRegistry;
import com.afterlands.afterlanguage.core.resolver.YamlTranslationLoader;
import com.afterlands.afterlanguage.core.service.DynamicContentAPIImpl;
import com.afterlands.afterlanguage.core.template.TemplateEngine;
import com.afterlands.afterlanguage.infra.command.AfterLangCommand;
import com.afterlands.afterlanguage.infra.command.LangCommand;
import com.afterlands.afterlanguage.infra.listener.PlayerLanguageListener;
import com.afterlands.afterlanguage.infra.papi.AfterLanguageExpansion;
import com.afterlands.afterlanguage.infra.persistence.DynamicTranslationRepository;
import com.afterlands.afterlanguage.infra.persistence.PlayerLanguageRepository;
import com.afterlands.afterlanguage.infra.protocol.ProtocolLibIntegration;
import com.afterlands.afterlanguage.infra.service.MessageServiceImpl;
import com.afterlands.afterlanguage.api.crowdin.CrowdinAPI;
import com.afterlands.afterlanguage.core.crowdin.*;
import com.afterlands.afterlanguage.infra.crowdin.*;
import com.afterlands.core.api.AfterCore;
import com.afterlands.core.api.AfterCoreAPI;
import com.afterlands.core.config.MessageService;
import com.afterlands.core.database.SqlDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Dependency Injection container for AfterLanguage.
 *
 * <p>
 * Initializes and manages all services in dependency order.
 * </p>
 *
 * <h3>Service Initialization Order:</h3>
 * <ol>
 * <li>Load configuration and validate AfterCore</li>
 * <li>Initialize database (migrations via AfterCore SqlService)</li>
 * <li>Create core services (Registry, Cache, TemplateEngine)</li>
 * <li>Create persistence (PlayerLanguageRepository,
 * DynamicTranslationRepository)</li>
 * <li>Create MessageResolver</li>
 * <li>Create file loading (YamlTranslationLoader, NamespaceManager)</li>
 * <li>Load default namespace ("afterlanguage")</li>
 * <li>Create MessageServiceImpl</li>
 * <li>Register as AfterCore MessageService provider</li>
 * <li>Register listeners and commands</li>
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

    // Extractors
    private MessageExtractor messageExtractor;
    private InventoryExtractor inventoryExtractor;

    // Provider
    private MessageServiceImpl messageService;

    // API
    private DynamicContentAPIImpl dynamicContentAPI;

    // Export/Import/Backup (v1.2.0)
    private TranslationExporter exporter;
    private TranslationImporter importer;
    private TranslationBackupService backupService;

    // Listeners
    private PlayerLanguageListener playerLanguageListener;

    // Commands
    private LangCommand langCommand;
    private AfterLangCommand afterLangCommand;

    // Integrations
    private ProtocolLibIntegration protocolLibIntegration;
    private AfterLanguageExpansion placeholderExpansion;

    // Crowdin Integration (v1.3.0)
    private boolean crowdinEnabled;
    private CrowdinConfig crowdinConfig;
    private CredentialManager crowdinCredentials;
    private CrowdinClient crowdinClient;
    private LocaleMapper localeMapper;
    private UploadStrategy uploadStrategy;
    private DownloadStrategy downloadStrategy;
    private ConflictResolver conflictResolver;
    private CrowdinSyncEngine crowdinSyncEngine;
    private CrowdinScheduler crowdinScheduler;
    private CrowdinWebhookServer crowdinWebhookServer;
    private CrowdinEventListener crowdinEventListener;
    private CrowdinCommand crowdinCommand;
    private CrowdinAPIImpl crowdinAPI;
    private RedisSyncBroadcaster redisSyncBroadcaster;

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
                throw new IllegalStateException(
                        "AfterCore not found! Ensure AfterCore is loaded before AfterLanguage.");
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
            String defaultLangCode = plugin.getConfig().getString("default-language", "pt_br");
            String defaultLangName = plugin.getConfig().getString("language-names." + defaultLangCode,
                    "Português (Brasil)");
            this.defaultLanguage = new Language(defaultLangCode, defaultLangName, true);

            logger.info("[Registry] Default language: " + defaultLanguage.name() + " [" + defaultLanguage.code() + "]");

            // 5. Create core services
            this.registry = new TranslationRegistry(debug);
            this.cache = new TranslationCache(
                    plugin.getConfig().getInt("cache.l1.max-size", 10000),
                    (int) (plugin.getConfig().getLong("cache.l1.ttl-seconds", 300) / 60),
                    plugin.getConfig().getInt("cache.l3.max-size", 5000),
                    (int) (plugin.getConfig().getLong("cache.l3.ttl-seconds", 600) / 60),
                    debug);
            this.templateEngine = new TemplateEngine(debug);

            logger.info("[Registry] Core services initialized (Registry, Cache, TemplateEngine)");

            // 6. Create persistence
            String playerTableName = plugin.getConfig().getString("database.tables.player-language",
                    "afterlanguage_player_language");
            this.playerLanguageRepo = new PlayerLanguageRepository(
                    dataSource,
                    playerTableName,
                    logger,
                    debug);

            String dynamicTableName = plugin.getConfig().getString("database.tables.dynamic-translations",
                    "afterlanguage_dynamic_translations");
            this.dynamicTranslationRepo = new DynamicTranslationRepository(
                    dataSource,
                    dynamicTableName,
                    logger,
                    debug);

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
                    debug);

            logger.info("[Registry] MessageResolver initialized");

            // 8. Create file loading
            Path languagesDir = plugin.getDataFolder().toPath().resolve("languages");
            boolean firstRun = !Files.exists(languagesDir);
            if (firstRun) {
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
                    debug);

            logger.info("[Registry] File loading initialized (YamlTranslationLoader, NamespaceManager)");

            // 8b. Create extractors
            List<String> enabledLanguages = plugin.getConfig().getStringList("enabled-languages");
            if (enabledLanguages.isEmpty()) {
                enabledLanguages = List.of(defaultLanguage.code());
            }

            this.messageExtractor = new MessageExtractor(
                    languagesDir, defaultLanguage.code(), enabledLanguages, logger, debug);
            this.inventoryExtractor = new InventoryExtractor(
                    languagesDir, defaultLanguage.code(), enabledLanguages, logger, debug);

            logger.info("[Registry] Extractors initialized (MessageExtractor, InventoryExtractor)");

            // 9. Register default namespace ("afterlanguage")
            // Provision default resources from JAR only on first run
            if (firstRun) {
                provisionDefaultResources();
                logger.info("[Registry] First run detected - provisioned default translation files");
            }

            Path defaultNamespaceFolder = plugin.getDataFolder().toPath().resolve("languages")
                    .resolve(defaultLanguage.code()).resolve("afterlanguage");

            // Create example translation file if doesn't exist
            if (!Files.exists(defaultNamespaceFolder)) {
                Files.createDirectories(defaultNamespaceFolder);
            }

            namespaceManager.registerNamespace("afterlanguage", defaultNamespaceFolder).join();
            logger.info("[Registry] Registered namespace: afterlanguage");

            // 9a. Register "aftercore" namespace (extracted from AfterCore's messages.yml)
            File afterCoreMsgs = new File("plugins/AfterCore/messages.yml");
            if (afterCoreMsgs.exists()) {
                messageExtractor.extract(afterCoreMsgs, "aftercore", "messages");
            }
            namespaceManager.registerNamespace("aftercore", null).join();
            logger.info("[Registry] Registered namespace: aftercore");

            // 9b. Discover and load any additional namespace folders on disk
            namespaceManager.discoverAndRegisterNewNamespaces();
            for (String ns : namespaceManager.getRegisteredNamespaces()) {
                if (!"afterlanguage".equals(ns)) {
                    namespaceManager.reloadNamespace(ns).join();
                    logger.info("[Registry] Auto-registered namespace from disk: " + ns);
                }
            }

            // 10. Create MessageServiceImpl
            boolean papiProcessMessages = plugin.getConfig().getBoolean("placeholderapi.process-in-messages", true);
            this.messageService = new MessageServiceImpl(
                    messageResolver,
                    playerLanguageRepo,
                    namespaceManager,
                    messageExtractor,
                    inventoryExtractor,
                    afterCore.metrics(),
                    defaultLanguage.code(),
                    logger,
                    debug,
                    papiProcessMessages);

            logger.info("[Registry] MessageServiceImpl created");

            // 11. Create Export/Import/Backup services (v1.2.0)
            this.exporter = new TranslationExporter(logger, debug);
            this.importer = new TranslationImporter(dynamicTranslationRepo, logger, debug);

            // Ensure imports directory exists
            Path importsDir = plugin.getDataFolder().toPath().resolve("imports");
            Files.createDirectories(importsDir);

            Path backupsDir = plugin.getDataFolder().toPath().resolve("backups");
            boolean backupsEnabled = plugin.getConfig().getBoolean("backup.enabled", true);
            int maxBackups = plugin.getConfig().getInt("backup.max-backups", 10);

            this.backupService = new TranslationBackupService(
                    backupsDir,
                    exporter,
                    importer,
                    logger,
                    backupsEnabled,
                    maxBackups);

            logger.info("[Registry] Export/Import/Backup services created (backups: " +
                    (backupsEnabled ? "enabled, max=" + maxBackups : "disabled") + ")");

            // 12. Create DynamicContentAPI (v1.2.0)
            this.dynamicContentAPI = new DynamicContentAPIImpl(
                    dynamicTranslationRepo,
                    registry,
                    cache,
                    logger,
                    debug);

            logger.info("[Registry] DynamicContentAPI created");

            // 13. Initialize Crowdin Integration (v1.3.0)
            initializeCrowdin();

            // 15. Create listeners
            this.playerLanguageListener = new PlayerLanguageListener(
                    playerLanguageRepo,
                    registry,
                    defaultLanguage.code(),
                    plugin.getConfig(),
                    afterCore,
                    logger,
                    debug);

            // 16. Create integrations
            this.protocolLibIntegration = new ProtocolLibIntegration(
                    plugin,
                    playerLanguageRepo,
                    logger,
                    debug);

            this.placeholderExpansion = new AfterLanguageExpansion(
                    plugin,
                    playerLanguageRepo,
                    messageResolver,
                    defaultLanguage,
                    logger,
                    debug);

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
        String playerTable = plugin.getConfig().getString("database.tables.player-language",
                "afterlanguage_player_language");
        String dynamicTable = plugin.getConfig().getString("database.tables.dynamic-translations",
                "afterlanguage_dynamic_translations");

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

        // Migration 3: Add plural forms columns (v1.2.0)
        afterCore.sql().registerMigration(datasourceName, "add_plural_forms_columns", conn -> {
            String[] alterSqls = {
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS plural_zero TEXT AFTER text".formatted(dynamicTable),
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS plural_one TEXT AFTER plural_zero".formatted(dynamicTable),
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS plural_two TEXT AFTER plural_one".formatted(dynamicTable),
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS plural_few TEXT AFTER plural_two".formatted(dynamicTable),
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS plural_many TEXT AFTER plural_few".formatted(dynamicTable),
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS plural_other TEXT AFTER plural_many"
                            .formatted(dynamicTable)
            };

            try (var stmt = conn.createStatement()) {
                for (String alterSql : alterSqls) {
                    try {
                        stmt.execute(alterSql);
                    } catch (Exception e) {
                        // Column might already exist, ignore
                    }
                }
            }
        });

        // Migration 4: Add Crowdin tracking columns (v1.3.0)
        afterCore.sql().registerMigration(datasourceName, "add_crowdin_columns_v130", conn -> {
            String[] alterSqls = {
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS crowdin_string_id BIGINT AFTER updated_at"
                            .formatted(dynamicTable),
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS crowdin_hash VARCHAR(64) AFTER crowdin_string_id"
                            .formatted(dynamicTable),
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS last_synced_at TIMESTAMP NULL AFTER crowdin_hash"
                            .formatted(dynamicTable),
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS sync_status VARCHAR(16) DEFAULT 'pending' AFTER last_synced_at"
                            .formatted(dynamicTable)
            };

            try (var stmt = conn.createStatement()) {
                for (String sql : alterSqls) {
                    try {
                        stmt.execute(sql);
                    } catch (Exception e) {
                        // Column might already exist, ignore
                    }
                }

                // Add indices
                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_crowdin_string_id ON %s(crowdin_string_id)"
                            .formatted(dynamicTable));
                } catch (Exception ignored) {
                }
                try {
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_sync_status ON %s(sync_status)".formatted(dynamicTable));
                } catch (Exception ignored) {
                }
            }

            logger.info("[Migration] Added Crowdin tracking columns (v1.3.0)");
        });

        // Migration 5: Create Crowdin sync log table (v1.3.0)
        String syncLogTable = plugin.getConfig().getString("database.tables.crowdin-sync-log",
                "afterlanguage_crowdin_sync_log");
        afterCore.sql().registerMigration(datasourceName, "create_crowdin_sync_log_v130", conn -> {
            String sql = """
                    CREATE TABLE IF NOT EXISTS %s (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        sync_id VARCHAR(36) NOT NULL,
                        operation VARCHAR(16) NOT NULL,
                        namespace VARCHAR(64),
                        language VARCHAR(10),
                        strings_uploaded INT DEFAULT 0,
                        strings_downloaded INT DEFAULT 0,
                        strings_skipped INT DEFAULT 0,
                        conflicts INT DEFAULT 0,
                        errors TEXT,
                        started_at TIMESTAMP NOT NULL,
                        completed_at TIMESTAMP NULL,
                        status VARCHAR(16) DEFAULT 'running',
                        INDEX idx_sync_id (sync_id),
                        INDEX idx_namespace (namespace),
                        INDEX idx_status (status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """.formatted(syncLogTable);

            try (var stmt = conn.createStatement()) {
                stmt.execute(sql);
            }

            logger.info("[Migration] Created crowdin_sync_log table (v1.3.0)");
        });

        logger.info("[Registry] Database migrations registered");
    }

    /**
     * Provisions default resources from the JAR.
     */
    private void provisionDefaultResources() {
        String[] resources = {
                "languages/en_us/afterlanguage/gui.yml",
                "languages/en_us/afterlanguage/messages.yml",
                "languages/es_es/afterlanguage/gui.yml",
                "languages/es_es/afterlanguage/messages.yml",
                "languages/pt_br/afterlanguage/gui.yml",
                "languages/pt_br/afterlanguage/messages.yml",
                "languages/pt_br/afterlanguage/pluralization_example.yml"
        };

        for (String resourcePath : resources) {
            File file = new File(plugin.getDataFolder(), resourcePath);
            if (!file.exists()) {
                plugin.saveResource(resourcePath, false);
                // logger.info("[Registry] Provisioned default resource: " + resourcePath);
            }
        }
    }

    /**
     * Creates example translation files for testing.
     */
    private void createExampleTranslations(Path namespaceDir) throws IOException {
        // No longer creates hardcoded files, relies on provisionDefaultResources
    }

    /**
     * Initializes Crowdin integration services (v1.3.0).
     *
     * <p>
     * Only initializes if crowdin.enabled is true in config.yml.
     * </p>
     */
    private void initializeCrowdin() {
        this.crowdinEnabled = plugin.getConfig().getBoolean("crowdin.enabled", false);

        if (!crowdinEnabled) {
            logger.info("[Registry] Crowdin integration disabled");
            return;
        }

        logger.info("[Registry] Initializing Crowdin integration...");

        try {
            // Load crowdin.yml configuration
            Path crowdinConfigPath = plugin.getDataFolder().toPath().resolve("crowdin.yml");
            if (!Files.exists(crowdinConfigPath)) {
                logger.warning("[Registry] crowdin.yml not found - creating default...");
                plugin.saveResource("crowdin.yml", false);
            }

            YamlConfiguration crowdinYml = YamlConfiguration.loadConfiguration(crowdinConfigPath.toFile());

            // Create CrowdinConfig from both crowdin.yml and config.yml
            this.crowdinConfig = new CrowdinConfig(
                    crowdinYml,
                    plugin.getConfig().getConfigurationSection("crowdin"));

            // Load credentials
            this.crowdinCredentials = new CredentialManager(crowdinYml);

            // Validate credentials
            if (!crowdinCredentials.isValid()) {
                logger.warning("[Registry] Crowdin credentials not configured properly.");
                logger.warning("[Registry] Set CROWDIN_PROJECT_ID and CROWDIN_API_TOKEN environment variables");
                logger.warning("[Registry] Crowdin integration disabled");
                this.crowdinEnabled = false;
                return;
            }

            logger.info("[Registry] Crowdin credentials loaded (project: " +
                    crowdinCredentials.getProjectId() + ", token: " + crowdinCredentials.getMaskedToken() + ")");

            // Create HTTP client
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(crowdinConfig.getTimeoutSeconds()))
                    .build();

            // Create Crowdin client
            this.crowdinClient = new CrowdinClient(
                    httpClient,
                    crowdinCredentials.getApiToken(),
                    crowdinCredentials.getProjectId(),
                    logger,
                    debug);

            // Create locale mapper
            this.localeMapper = new LocaleMapper(crowdinConfig.getLocaleMappings());
            logger.info("[Registry] Locale mappings: " + localeMapper.size() + " configured");

            // Create conflict resolver
            this.conflictResolver = ConflictResolver.create(
                    crowdinConfig.getConflictResolution(),
                    logger);
            logger.info("[Registry] Conflict resolution: " + conflictResolver.getName());

            // Create upload strategy
            this.uploadStrategy = new UploadStrategy(
                    crowdinClient,
                    crowdinConfig,
                    logger,
                    debug);

            // Create download strategy
            this.downloadStrategy = new DownloadStrategy(
                    crowdinClient,
                    dynamicContentAPI,
                    localeMapper,
                    crowdinConfig,
                    namespaceManager.getLanguagesDir(),
                    logger,
                    debug);

            // Create sync engine
            this.crowdinSyncEngine = new CrowdinSyncEngine(
                    crowdinClient,
                    dynamicContentAPI,
                    dynamicTranslationRepo,
                    registry,
                    namespaceManager,
                    cache,
                    backupService,
                    uploadStrategy,
                    downloadStrategy,
                    conflictResolver,
                    crowdinConfig,
                    logger,
                    debug);

            // Create scheduler (if auto-sync enabled)
            if (crowdinConfig.isAutoSyncEnabled()) {
                this.crowdinScheduler = new CrowdinScheduler(
                        plugin,
                        crowdinSyncEngine,
                        crowdinConfig,
                        logger);
                logger.info("[Registry] Auto-sync scheduler created (interval: " +
                        crowdinConfig.getAutoSyncIntervalMinutes() + " min)");
            }

            // Create webhook server (if enabled)
            if (crowdinConfig.isWebhookEnabled()) {
                this.crowdinWebhookServer = new CrowdinWebhookServer(
                        crowdinConfig.getWebhookPort(),
                        crowdinConfig.getWebhookSecret(),
                        crowdinSyncEngine,
                        logger,
                        debug);
                logger.info("[Registry] Webhook server created (port: " +
                        crowdinConfig.getWebhookPort() + ")");
            }

            // Create event listener
            this.crowdinEventListener = new CrowdinEventListener(
                    dynamicTranslationRepo,
                    crowdinConfig,
                    logger,
                    debug);

            // Create command handler
            this.crowdinCommand = new CrowdinCommand(
                    this,
                    crowdinSyncEngine,
                    crowdinConfig,
                    crowdinScheduler);

            // Create public API
            this.crowdinAPI = new CrowdinAPIImpl(
                    crowdinSyncEngine,
                    crowdinConfig,
                    crowdinCredentials.getProjectId());

            // Create Redis broadcaster (if enabled)
            if (plugin.getConfig().getBoolean("redis.enabled", false) &&
                    plugin.getConfig().getBoolean("redis.events.crowdin-sync", true)) {
                String channel = plugin.getConfig().getString("redis.channel", "afterlanguage:sync");
                this.redisSyncBroadcaster = new RedisSyncBroadcaster(
                        channel,
                        dynamicContentAPI,
                        cache,
                        logger,
                        debug);
                logger.info("[Registry] Redis sync broadcaster created (channel: " + channel + ")");
            }

            logger.info("[Registry] Crowdin integration initialized successfully!");

        } catch (Exception e) {
            logger.severe("[Registry] Failed to initialize Crowdin: " + e.getMessage());
            e.printStackTrace();
            this.crowdinEnabled = false;
        }
    }

    /**
     * Starts Crowdin services (scheduler, webhook).
     *
     * <p>
     * Called by PluginLifecycle after all services are initialized.
     * </p>
     */
    public void startCrowdinServices() {
        if (!crowdinEnabled) {
            return;
        }

        // Start scheduler
        if (crowdinScheduler != null) {
            crowdinScheduler.start();
        }

        // Start webhook server
        if (crowdinWebhookServer != null) {
            try {
                crowdinWebhookServer.startServer();
            } catch (Exception e) {
                logger.warning("[Registry] Failed to start webhook server: " + e.getMessage());
            }
        }

        // Register Crowdin event listener
        if (crowdinEventListener != null) {
            Bukkit.getPluginManager().registerEvents(crowdinEventListener, plugin);
        }

        // Subscribe to Redis
        if (redisSyncBroadcaster != null) {
            redisSyncBroadcaster.subscribe();
        }
    }

    /**
     * Stops Crowdin services.
     *
     * <p>
     * Called by PluginLifecycle during shutdown.
     * </p>
     */
    public void stopCrowdinServices() {
        if (!crowdinEnabled) {
            return;
        }

        // Stop scheduler
        if (crowdinScheduler != null) {
            crowdinScheduler.stop();
        }

        // Stop webhook server
        if (crowdinWebhookServer != null) {
            crowdinWebhookServer.stopServer();
        }

        // Unsubscribe from Redis
        if (redisSyncBroadcaster != null) {
            redisSyncBroadcaster.unsubscribe();
        }

        // Shutdown client
        if (crowdinClient != null) {
            crowdinClient.shutdown();
        }
    }

    /**
     * Registers MessageService provider in Bukkit ServicesManager.
     */
    public void registerMessageServiceProvider() {
        ServicesManager services = Bukkit.getServicesManager();
        services.register(MessageService.class, messageService, plugin, ServicePriority.Normal);
        logger.info("[Registry] Registered MessageService provider");
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
     * Replays namespace registrations that were buffered in AfterCore's
     * DefaultMessageService before AfterLanguage was ready.
     *
     * <p>Only processes namespaces that plugins explicitly requested via
     * {@code registerNamespace()} — no guessing or scanning.</p>
     */
    public void replayPendingNamespaceRegistrations() {
        var coreMessages = afterCore.messages();
        if (coreMessages instanceof com.afterlands.core.config.impl.DefaultMessageService dms) {
            var pending = dms.drainPendingNamespaceRegistrations();
            if (pending.isEmpty()) return;

            for (var entry : pending) {
                try {
                    messageService.registerNamespace(entry.plugin(), entry.namespace());
                } catch (Exception e) {
                    logger.warning("[Registry] Failed to replay namespace registration '"
                            + entry.namespace() + "' for " + entry.plugin().getName() + ": " + e.getMessage());
                }
            }
            logger.info("[Registry] Replayed " + pending.size() + " buffered namespace registration(s)");
        }
    }

    /**
     * Registers commands via AfterCore CommandFramework.
     */
    public void registerCommands() {
        // Register custom argument types
        var typeRegistry = com.afterlands.core.commands.parser.ArgumentTypeRegistry.instance();
        typeRegistry.registerForPlugin(plugin, "namespace",
                new com.afterlands.afterlanguage.infra.command.types.NamespaceType(namespaceManager));
        typeRegistry.registerForPlugin(plugin, "language",
                new com.afterlands.afterlanguage.infra.command.types.LanguageType(plugin.getConfig()));
        typeRegistry.registerForPlugin(plugin, "backupId",
                new com.afterlands.afterlanguage.infra.command.types.BackupIdType(backupService));
        typeRegistry.registerForPlugin(plugin, "importFile",
                new com.afterlands.afterlanguage.infra.command.types.ImportFileType(
                        plugin.getDataFolder().toPath().resolve("imports")));
        typeRegistry.registerForPlugin(plugin, "yamlFile",
                new com.afterlands.afterlanguage.infra.command.types.NamespaceYamlFileType(namespaceManager));
        logger.info("[Registry] Custom argument types registered");

        // Create command instances
        this.langCommand = new LangCommand(this);
        this.afterLangCommand = new AfterLangCommand(this);

        // Register commands via AfterCore CommandFramework
        afterCore.commands().register(plugin, langCommand);
        afterCore.commands().register(plugin, afterLangCommand);

        logger.info("[Registry] Commands registered via CommandFramework");
    }

    /**
     * Saves all pending player data.
     */
    public void saveAllPending() {
        if (playerLanguageRepo == null) {
            return;
        }

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
                placeholderExpansion.registerAll();
                logger.info("[Registry] PlaceholderAPI expansions registered (afterlang, alang, lang)");
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
    public MessageServiceImpl getMessageService() {
        return messageService;
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

    @NotNull
    public DynamicContentAPI getDynamicContentAPI() {
        return dynamicContentAPI;
    }

    @NotNull
    public DynamicTranslationRepository getDynamicTranslationRepo() {
        return dynamicTranslationRepo;
    }

    @NotNull
    public TranslationExporter getExporter() {
        return exporter;
    }

    @NotNull
    public TranslationImporter getImporter() {
        return importer;
    }

    @NotNull
    public TranslationBackupService getBackupService() {
        return backupService;
    }

    // ══════════════════════════════════════════════
    // CROWDIN GETTERS (v1.3.0)
    // ══════════════════════════════════════════════

    /**
     * Checks if Crowdin integration is enabled and initialized.
     */
    public boolean isCrowdinEnabled() {
        return crowdinEnabled;
    }

    /**
     * Gets the Crowdin API.
     *
     * @return CrowdinAPI or null if disabled
     */
    @Nullable
    public CrowdinAPI getCrowdinAPI() {
        return crowdinAPI;
    }

    /**
     * Gets the Crowdin sync engine.
     *
     * @return CrowdinSyncEngine or null if disabled
     */
    @Nullable
    public CrowdinSyncEngine getCrowdinSyncEngine() {
        return crowdinSyncEngine;
    }

    /**
     * Gets the Crowdin scheduler.
     *
     * @return CrowdinScheduler or null if disabled/not configured
     */
    @Nullable
    public CrowdinScheduler getCrowdinScheduler() {
        return crowdinScheduler;
    }

    /**
     * Gets the Crowdin command handler.
     *
     * @return CrowdinCommand or null if disabled
     */
    @Nullable
    public CrowdinCommand getCrowdinCommand() {
        return crowdinCommand;
    }

    /**
     * Gets the Crowdin configuration.
     *
     * @return CrowdinConfig or null if disabled
     */
    @Nullable
    public CrowdinConfig getCrowdinConfig() {
        return crowdinConfig;
    }
}
