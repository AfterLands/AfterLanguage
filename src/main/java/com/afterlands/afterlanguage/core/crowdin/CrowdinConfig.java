package com.afterlands.afterlanguage.core.crowdin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

/**
 * Configuration loader for Crowdin integration.
 *
 * <p>Loads settings from both crowdin.yml and the crowdin section of config.yml.</p>
 *
 * <h3>Configuration Sources:</h3>
 * <ul>
 *     <li><b>crowdin.yml</b>: API credentials, locale mappings, file patterns</li>
 *     <li><b>config.yml (crowdin section)</b>: enabled state, auto-sync interval, webhook settings</li>
 * </ul>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class CrowdinConfig {

    // From crowdin.yml
    private final String sourceLanguage;
    private final Map<String, String> localeMappings;
    private final List<String> syncNamespaces;
    private final int batchSize;
    private final int timeoutSeconds;
    private final int maxRetries;

    // Upload settings
    private final boolean autoUpload;
    private final boolean updateStrings;
    private final boolean cleanupMode;

    // Download settings
    private final boolean skipUntranslated;
    private final boolean exportApprovedOnly;

    // From config.yml crowdin section
    private final boolean enabled;
    private final String serverId;
    private final Map<String, String> namespaceDirectories;
    private final int autoSyncIntervalMinutes;
    private final boolean webhookEnabled;
    private final int webhookPort;
    private final String webhookSecret;
    private final boolean hotReload;
    private final boolean notifyAdmins;
    private final boolean backupBeforeSync;
    private final boolean uploadTranslationsEnabled;
    private final ConflictResolutionStrategy conflictResolution;

    /**
     * Conflict resolution strategies.
     */
    public enum ConflictResolutionStrategy {
        /** Crowdin version wins (default - approved translations are authoritative) */
        CROWDIN_WINS,
        /** Local version wins (preserve local changes) */
        LOCAL_WINS,
        /** Manual resolution (store in conflict table for admin review) */
        MANUAL
    }

    /**
     * Creates a CrowdinConfig from configuration sections.
     *
     * @param crowdinYml The crowdin.yml configuration
     * @param configYml The config.yml crowdin section
     */
    public CrowdinConfig(
            @NotNull ConfigurationSection crowdinYml,
            @Nullable ConfigurationSection configYml
    ) {
        Objects.requireNonNull(crowdinYml, "crowdinYml cannot be null");

        // From crowdin.yml
        this.sourceLanguage = crowdinYml.getString("source-language", "pt-BR");
        this.localeMappings = loadLocaleMappings(crowdinYml.getConfigurationSection("locale-mapping"));
        this.syncNamespaces = crowdinYml.getStringList("sync-namespaces");

        // Advanced settings
        ConfigurationSection advanced = crowdinYml.getConfigurationSection("advanced");
        this.batchSize = advanced != null ? advanced.getInt("batch-size", 100) : 100;
        this.timeoutSeconds = advanced != null ? advanced.getInt("timeout-seconds", 30) : 30;
        this.maxRetries = advanced != null ? advanced.getInt("max-retries", 3) : 3;

        // Upload settings
        ConfigurationSection upload = crowdinYml.getConfigurationSection("upload");
        this.autoUpload = upload != null && upload.getBoolean("auto-upload", true);
        this.updateStrings = upload != null && upload.getBoolean("update-strings", true);
        this.cleanupMode = upload != null && upload.getBoolean("cleanup-mode", false);

        // Download settings
        ConfigurationSection download = crowdinYml.getConfigurationSection("download");
        this.skipUntranslated = download == null || download.getBoolean("skip-untranslated", true);
        this.exportApprovedOnly = download == null || download.getBoolean("export-approved-only", true);

        // From config.yml crowdin section (or defaults)
        if (configYml != null) {
            this.enabled = configYml.getBoolean("enabled", false);
            this.serverId = configYml.getString("server-id", "");
            this.namespaceDirectories = loadNamespaceDirectories(configYml);
            this.autoSyncIntervalMinutes = configYml.getInt("auto-sync-interval-minutes", 30);
            this.hotReload = configYml.getBoolean("hot-reload", true);
            this.notifyAdmins = configYml.getBoolean("notify-admins", true);
            this.backupBeforeSync = configYml.getBoolean("backup-before-sync", true);
            this.uploadTranslationsEnabled = configYml.getBoolean("upload-translations", false);

            // Webhook settings
            ConfigurationSection webhook = configYml.getConfigurationSection("webhook");
            this.webhookEnabled = webhook != null && webhook.getBoolean("enabled", false);
            this.webhookPort = webhook != null ? webhook.getInt("port", 8432) : 8432;
            this.webhookSecret = webhook != null
                    ? CredentialManager.resolveEnvVar(webhook.getString("secret", ""))
                    : "";

            // Conflict resolution
            String conflictStr = configYml.getString("conflict-resolution", "crowdin-wins");
            this.conflictResolution = parseConflictResolution(conflictStr);
        } else {
            // Defaults if config.yml section not present
            this.enabled = false;
            this.serverId = "";
            this.namespaceDirectories = Collections.emptyMap();
            this.autoSyncIntervalMinutes = 30;
            this.webhookEnabled = false;
            this.webhookPort = 8432;
            this.webhookSecret = "";
            this.hotReload = true;
            this.notifyAdmins = true;
            this.backupBeforeSync = true;
            this.uploadTranslationsEnabled = false;
            this.conflictResolution = ConflictResolutionStrategy.CROWDIN_WINS;
        }
    }

    /**
     * Creates a CrowdinConfig from a single crowdin.yml configuration.
     *
     * @param crowdinYml The crowdin.yml configuration
     */
    public CrowdinConfig(@NotNull ConfigurationSection crowdinYml) {
        this(crowdinYml, null);
    }

    /**
     * Loads a CrowdinConfig from file paths.
     *
     * @param crowdinYmlPath Path to crowdin.yml
     * @param configYml The main config.yml (optional)
     * @return CrowdinConfig instance
     */
    @NotNull
    public static CrowdinConfig load(@NotNull Path crowdinYmlPath, @Nullable ConfigurationSection configYml) {
        YamlConfiguration crowdinConfig = YamlConfiguration.loadConfiguration(crowdinYmlPath.toFile());
        return new CrowdinConfig(crowdinConfig, configYml);
    }

    /**
     * Loads locale mappings from configuration.
     */
    @NotNull
    private Map<String, String> loadLocaleMappings(@Nullable ConfigurationSection section) {
        Map<String, String> mappings = new LinkedHashMap<>();

        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null) {
                    mappings.put(key, value);
                }
            }
        }

        // Add default mappings if empty
        if (mappings.isEmpty()) {
            mappings.put("pt-BR", "pt_br");
            mappings.put("en", "en_us");
            mappings.put("es-ES", "es_es");
        }

        return Collections.unmodifiableMap(mappings);
    }

    /**
     * Loads namespace-to-directory mappings from configuration.
     *
     * <p>Maps namespace names to custom directory paths. An empty string value
     * means the namespace goes to root level (global, shared by all servers).</p>
     *
     * <p>Supports backward compatibility with deprecated 'namespace-branches' key.</p>
     */
    @NotNull
    private Map<String, String> loadNamespaceDirectories(@NotNull ConfigurationSection configYml) {
        // Try new key first
        ConfigurationSection section = configYml.getConfigurationSection("namespace-directories");

        // Fallback to old key with deprecation warning
        if (section == null) {
            section = configYml.getConfigurationSection("namespace-branches");
            if (section != null) {
                java.util.logging.Logger.getLogger("AfterLanguage").warning(
                    "[CrowdinConfig] Config key 'namespace-branches' is deprecated. " +
                    "Please rename to 'namespace-directories'. Branches are not supported on Crowdin Free plan."
                );
            }
        }

        if (section == null) {
            return Collections.emptyMap();
        }

        Map<String, String> mappings = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String value = section.getString(key, "");
            mappings.put(key, value);
        }
        return Collections.unmodifiableMap(mappings);
    }

    /**
     * Parses conflict resolution strategy from string.
     */
    @NotNull
    private ConflictResolutionStrategy parseConflictResolution(@Nullable String value) {
        if (value == null) {
            return ConflictResolutionStrategy.CROWDIN_WINS;
        }

        return switch (value.toLowerCase().replace("-", "_")) {
            case "local_wins", "local-wins", "local" -> ConflictResolutionStrategy.LOCAL_WINS;
            case "manual" -> ConflictResolutionStrategy.MANUAL;
            default -> ConflictResolutionStrategy.CROWDIN_WINS;
        };
    }

    // ══════════════════════════════════════════════
    // GETTERS
    // ══════════════════════════════════════════════

    /**
     * Checks if Crowdin integration is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Gets the server identifier for Crowdin directory isolation.
     *
     * <p>When non-empty, all Crowdin operations are scoped to a directory with this name.
     * Each server in the network MUST use a unique server-id.</p>
     *
     * @return Server ID, or empty string if directory isolation is disabled
     */
    @NotNull
    public String getServerId() {
        return serverId;
    }

    /**
     * Checks if server-scoped directory isolation is enabled.
     *
     * @return true if server-id is configured
     */
    public boolean isServerScoped() {
        return !serverId.isEmpty();
    }

    /**
     * Gets the namespace-to-directory mappings.
     *
     * @return Unmodifiable map of namespace -> directory path segment
     */
    @NotNull
    public Map<String, String> getNamespaceDirectories() {
        return namespaceDirectories;
    }

    /**
     * Resolves the Crowdin directory path segments for a namespace.
     *
     * <p>Resolution order:</p>
     * <ol>
     *     <li>If namespace is in {@code namespace-directories}: use that value
     *         <ul>
     *             <li>{@code ""} (empty) → [namespace] (root level, globally shared)</li>
     *             <li>{@code "group-name"} → [group-name, namespace] (shared group)</li>
     *         </ul>
     *     </li>
     *     <li>Otherwise: use {@code server-id} → [server-id, namespace] (server-isolated)</li>
     *     <li>If {@code server-id} is also empty: [namespace] (no isolation)</li>
     * </ol>
     *
     * @param namespace Namespace identifier
     * @return Directory path segments (e.g., ["quests-pve", "afterquests"] or ["afterlanguage"])
     */
    @NotNull
    public List<String> getDirectoryPathForNamespace(@NotNull String namespace) {
        if (namespaceDirectories.containsKey(namespace)) {
            String override = namespaceDirectories.get(namespace);
            if (override.isEmpty()) {
                return List.of(namespace);
            } else {
                return List.of(override, namespace);
            }
        } else if (!serverId.isEmpty()) {
            return List.of(serverId, namespace);
        } else {
            return List.of(namespace);
        }
    }

    /**
     * Gets the full Crowdin file path for a namespace.
     *
     * <p>Example outputs:</p>
     * <ul>
     *     <li>{@code /afterlanguage/afterlanguage.yml} (root, global)</li>
     *     <li>{@code /tutorial/aftertutorial/aftertutorial.yml} (server-isolated)</li>
     *     <li>{@code /quests-pve/afterquests/afterquests.yml} (shared group)</li>
     * </ul>
     *
     * @param namespace Namespace identifier
     * @return Full Crowdin file path (e.g., "/quests-pve/afterquests/afterquests.yml")
     */
    @NotNull
    public String getCrowdinFilePathForNamespace(@NotNull String namespace) {
        List<String> segments = getDirectoryPathForNamespace(namespace);
        return "/" + String.join("/", segments) + "/" + namespace + ".yml";
    }

    /**
     * Gets the source language (e.g., "pt-BR").
     */
    @NotNull
    public String getSourceLanguage() {
        return sourceLanguage;
    }

    /**
     * Gets the locale mappings (Crowdin locale -> AfterLanguage code).
     */
    @NotNull
    public Map<String, String> getLocaleMappings() {
        return localeMappings;
    }

    /**
     * Gets the list of namespaces to sync (empty = sync all).
     */
    @NotNull
    public List<String> getSyncNamespaces() {
        return syncNamespaces;
    }

    /**
     * Checks if a namespace should be synced.
     *
     * @param namespace Namespace to check
     * @return true if should be synced (empty list = all namespaces)
     */
    public boolean shouldSyncNamespace(@NotNull String namespace) {
        return syncNamespaces.isEmpty() || syncNamespaces.contains(namespace);
    }

    /**
     * Gets the batch size for API requests.
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Gets the request timeout in seconds.
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Gets the maximum retry attempts.
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Checks if auto-upload is enabled.
     */
    public boolean isAutoUpload() {
        return autoUpload;
    }

    /**
     * Checks if updating existing strings is enabled.
     */
    public boolean isUpdateStrings() {
        return updateStrings;
    }

    /**
     * Checks if cleanup mode is enabled (delete removed strings).
     */
    public boolean isCleanupMode() {
        return cleanupMode;
    }

    /**
     * Checks if untranslated strings should be skipped.
     */
    public boolean isSkipUntranslated() {
        return skipUntranslated;
    }

    /**
     * Checks if only approved translations should be exported.
     */
    public boolean isExportApprovedOnly() {
        return exportApprovedOnly;
    }

    /**
     * Gets the auto-sync interval in minutes (0 = disabled).
     */
    public int getAutoSyncIntervalMinutes() {
        return autoSyncIntervalMinutes;
    }

    /**
     * Checks if auto-sync is enabled.
     */
    public boolean isAutoSyncEnabled() {
        return autoSyncIntervalMinutes > 0;
    }

    /**
     * Checks if webhook is enabled.
     */
    public boolean isWebhookEnabled() {
        return webhookEnabled;
    }

    /**
     * Gets the webhook server port.
     */
    public int getWebhookPort() {
        return webhookPort;
    }

    /**
     * Gets the webhook secret for signature verification.
     */
    @NotNull
    public String getWebhookSecret() {
        return webhookSecret != null ? webhookSecret : "";
    }

    /**
     * Checks if hot-reload after sync is enabled.
     */
    public boolean isHotReload() {
        return hotReload;
    }

    /**
     * Checks if admin notifications are enabled.
     */
    public boolean isNotifyAdmins() {
        return notifyAdmins;
    }

    /**
     * Checks if backup before sync is enabled.
     */
    public boolean isBackupBeforeSync() {
        return backupBeforeSync;
    }

    /**
     * Checks if uploading local translations for non-source languages is enabled during sync.
     *
     * <p>When false (default), use {@code /afterlang crowdin uploadtranslation} for manual upload.</p>
     */
    public boolean isUploadTranslationsEnabled() {
        return uploadTranslationsEnabled;
    }

    /**
     * Gets the conflict resolution strategy.
     */
    @NotNull
    public ConflictResolutionStrategy getConflictResolution() {
        return conflictResolution;
    }

    @Override
    public String toString() {
        return "CrowdinConfig[" +
               "enabled=" + enabled +
               ", serverId=" + (serverId.isEmpty() ? "(none)" : serverId) +
               ", sourceLanguage=" + sourceLanguage +
               ", autoSync=" + autoSyncIntervalMinutes + "min" +
               ", webhook=" + (webhookEnabled ? webhookPort : "disabled") +
               ", conflictResolution=" + conflictResolution +
               ", locales=" + localeMappings.size() +
               "]";
    }
}
