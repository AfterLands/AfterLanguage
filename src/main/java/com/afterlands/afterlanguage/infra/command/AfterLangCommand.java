package com.afterlands.afterlanguage.infra.command;

import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.afterlanguage.api.service.DynamicContentAPI;
import com.afterlands.afterlanguage.bootstrap.PluginRegistry;
import com.afterlands.afterlanguage.core.io.TranslationBackupService;
import com.afterlands.afterlanguage.core.io.TranslationExporter;
import com.afterlands.afterlanguage.core.io.TranslationImporter;
import com.afterlands.afterlanguage.infra.crowdin.CrowdinCommand;
import com.afterlands.core.api.messages.MessageKey;
import com.afterlands.core.api.messages.Placeholder;
import com.afterlands.core.commands.annotations.Arg;
import com.afterlands.core.commands.annotations.Command;
import com.afterlands.core.commands.annotations.Permission;
import com.afterlands.core.commands.annotations.Sender;
import com.afterlands.core.commands.annotations.Subcommand;
import com.afterlands.core.commands.execution.CommandContext;
import com.afterlands.core.config.MessageService;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Administration command for AfterLanguage.
 *
 * <h3>Usage:</h3>
 * <ul>
 *     <li>/afterlang - Show plugin statistics (default)</li>
 *     <li>/afterlang reload [namespace] - Reload translations</li>
 *     <li>/afterlang stats - Show plugin statistics</li>
 *     <li>/afterlang cache - Show cache statistics</li>
 *     <li>/afterlang dynamic create &lt;namespace&gt; &lt;key&gt; &lt;language&gt; &lt;text&gt; - Create dynamic translation (v1.2.0)</li>
 *     <li>/afterlang dynamic delete &lt;namespace&gt; &lt;key&gt; &lt;language&gt; - Delete dynamic translation (v1.2.0)</li>
 *     <li>/afterlang dynamic list &lt;namespace&gt; [language] - List dynamic translations (v1.2.0)</li>
 *     <li>/afterlang dynamic reload &lt;namespace&gt; - Reload dynamic translations (v1.2.0)</li>
 *     <li>/afterlang export &lt;namespace&gt; [language] [outputDir] - Export translations to YAML (v1.2.0)</li>
 *     <li>/afterlang import &lt;namespace&gt; &lt;language&gt; &lt;file&gt; [overwrite] - Import translations from YAML (v1.2.0)</li>
 *     <li>/afterlang backup create &lt;namespace&gt; - Create backup of dynamic translations (v1.2.0)</li>
 *     <li>/afterlang backup list [namespace] - List available backups (v1.2.0)</li>
 *     <li>/afterlang backup restore &lt;backupId&gt; &lt;namespace&gt; - Restore from backup (v1.2.0)</li>
 *     <li>/afterlang backup delete &lt;backupId&gt; - Delete a backup (v1.2.0)</li>
 * </ul>
 *
 * @author AfterLands Team
 * @since 1.1.0
 */
@Command(name = "afterlang", aliases = {"alang"}, description = "AfterLanguage administration")
@Permission("afterlanguage.admin")
public class AfterLangCommand {

    private final PluginRegistry registry;

    public AfterLangCommand(@NotNull PluginRegistry registry) {
        this.registry = registry;
    }

    // ══════════════════════════════════════════════
    // HELPER METHODS FOR I18N
    // ══════════════════════════════════════════════

    /**
     * Creates a MessageKey for the "afterlanguage" namespace.
     */
    @NotNull
    private static MessageKey key(@NotNull String path) {
        return MessageKey.of("afterlanguage", path);
    }

    /**
     * Resolves a translated message for a sender.
     * Uses MessageService for Players (full pipeline with PAPI), MessageResolver for Console.
     */
    @NotNull
    private String msg(@NotNull CommandSender sender, @NotNull String key, @NotNull Placeholder... placeholders) {
        if (sender instanceof Player player) {
            return registry.getMessageService().get(player, key(key), placeholders);
        }
        // Console: resolve via MessageResolver directly using default language
        String lang = registry.getDefaultLanguage().code();
        return registry.getMessageResolver().resolve(lang, "afterlanguage", key, toLocal(placeholders));
    }

    /**
     * Sends a translated message to a sender.
     * Uses MessageService for Players (full pipeline with PAPI), MessageResolver for Console.
     */
    private void sendMsg(@NotNull CommandSender sender, @NotNull String key, @NotNull Placeholder... placeholders) {
        if (sender instanceof Player player) {
            registry.getMessageService().send(player, key(key), placeholders);
        } else {
            // Console: resolve via MessageResolver and format manually
            String lang = registry.getDefaultLanguage().code();
            String message = registry.getMessageResolver().resolve(lang, "afterlanguage", key, toLocal(placeholders));
            if (!message.isEmpty()) {
                sender.sendMessage(registry.getMessageService().format(message));
            }
        }
    }

    /**
     * Converts AfterCore Placeholder array to local Placeholder array
     * for MessageResolver compatibility (Console fallback).
     */
    @NotNull
    private com.afterlands.afterlanguage.api.model.Placeholder[] toLocal(@NotNull Placeholder... placeholders) {
        com.afterlands.afterlanguage.api.model.Placeholder[] local =
                new com.afterlands.afterlanguage.api.model.Placeholder[placeholders.length];
        for (int i = 0; i < placeholders.length; i++) {
            local[i] = com.afterlands.afterlanguage.api.model.Placeholder.of(
                    placeholders[i].key(), String.valueOf(placeholders[i].value()));
        }
        return local;
    }

    /**
     * Default command - shows stats.
     * Executes when admin runs /afterlang without arguments.
     */
    @Subcommand("")
    public void defaultCommand(CommandContext ctx, @Sender @NotNull CommandSender sender) {
        handleStats(sender);
    }

    /**
     * Reloads translations.
     * Command: /afterlang reload [namespace]
     *
     * @param sender Command sender
     * @param namespace Optional namespace to reload (null = reload all)
     */
    @Subcommand("reload")
    public void reload(CommandContext ctx,
                      @Sender @NotNull CommandSender sender,
                      @Arg(value = "namespace", optional = true) @Nullable String namespace) {
        sendMsg(sender, "messages.admin.reload-started");

        long startTime = System.currentTimeMillis();

        try {
            if (namespace != null && !namespace.isEmpty()) {
                // Discover new namespaces before checking registration
                registry.getNamespaceManager().discoverAndRegisterNewNamespaces();

                if (!registry.getNamespaceManager().isRegistered(namespace)) {
                    sendMsg(sender, "messages.namespace.not-found",
                            Placeholder.of("namespace", namespace));
                    return;
                }

                registry.getNamespaceManager().reloadNamespace(namespace).join();
                sendMsg(sender, "messages.namespace.reloaded",
                        Placeholder.of("namespace", namespace));

            } else {
                // Reload all namespaces
                registry.getNamespaceManager().reloadAll().join();
                sendMsg(sender, "messages.admin.reload_all");
            }

            long elapsed = System.currentTimeMillis() - startTime;
            sendMsg(sender, "messages.admin.reload-complete",
                    Placeholder.of("time", String.valueOf(elapsed)));

            registry.getLogger().info("[AfterLangCommand] Translations reloaded by " +
                    sender.getName() + " in " + elapsed + "ms");

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sendMsg(sender, "messages.admin.reload_failed",
                    Placeholder.of("error", errorMsg));
            registry.getLogger().warning("[AfterLangCommand] Reload failed: " + errorMsg);
            e.printStackTrace();
        }
    }

    /**
     * Shows plugin statistics.
     * Command: /afterlang stats
     */
    @Subcommand("stats")
    public void stats(CommandContext ctx, @Sender @NotNull CommandSender sender) {
        handleStats(sender);
    }

    /**
     * Shows cache statistics.
     * Command: /afterlang cache
     */
    @Subcommand("cache")
    public void cacheStats(CommandContext ctx, @Sender @NotNull CommandSender sender) {
        handleCacheStats(sender);
    }

    // ══════════════════════════════════════════════
    // DYNAMIC CONTENT MANAGEMENT (v1.2.0)
    // ══════════════════════════════════════════════

    /**
     * Creates a new dynamic translation.
     * Command: /afterlang dynamic create <namespace> <key> <language> <text...>
     *
     * @param sender Command sender
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @param text Translation text (multi-word supported)
     */
    @Subcommand("dynamic create")
    public void dynamicCreate(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("namespace") @NotNull String namespace,
            @Arg("key") @NotNull String key,
            @Arg("language") @NotNull String language,
            @Arg("text") @NotNull String text
    ) {
        DynamicContentAPI api = registry.getDynamicContentAPI();

        sendMsg(sender, "messages.admin.dynamic_creating");

        api.createTranslation(namespace, key, language, text)
                .thenRun(() -> {
                    sendMsg(sender, "messages.admin.dynamic_created",
                            Placeholder.of("namespace", namespace),
                            Placeholder.of("key", key),
                            Placeholder.of("language", language),
                            Placeholder.of("text", text));

                    registry.getLogger().info("[AfterLangCommand] Dynamic translation created by " +
                            sender.getName() + ": " + namespace + ":" + key + " [" + language + "]");
                })
                .exceptionally(ex -> {
                    sendMsg(sender, "messages.admin.dynamic_create_failed",
                            Placeholder.of("error", ex.getMessage()));
                    registry.getLogger().warning("[AfterLangCommand] Failed to create translation: " +
                            ex.getMessage());
                    return null;
                });
    }

    /**
     * Updates an existing dynamic translation.
     * Command: /afterlang dynamic update <namespace> <key> <language> <text...>
     *
     * @param sender Command sender
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @param text New translation text (multi-word supported)
     */
    @Subcommand("dynamic update")
    public void dynamicUpdate(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("namespace") @NotNull String namespace,
            @Arg("key") @NotNull String key,
            @Arg("language") @NotNull String language,
            @Arg("text") @NotNull String text
    ) {
        DynamicContentAPI api = registry.getDynamicContentAPI();

        sendMsg(sender, "messages.admin.dynamic_updating");

        api.updateTranslation(namespace, key, language, text)
                .thenRun(() -> {
                    sendMsg(sender, "messages.admin.dynamic_updated",
                            Placeholder.of("namespace", namespace),
                            Placeholder.of("key", key),
                            Placeholder.of("language", language),
                            Placeholder.of("text", text));

                    registry.getLogger().info("[AfterLangCommand] Dynamic translation updated by " +
                            sender.getName() + ": " + namespace + ":" + key + " [" + language + "]");
                })
                .exceptionally(ex -> {
                    sendMsg(sender, "messages.admin.dynamic_update_failed",
                            Placeholder.of("error", ex.getMessage()));
                    registry.getLogger().warning("[AfterLangCommand] Failed to update translation: " +
                            ex.getMessage());
                    return null;
                });
    }

    /**
     * Deletes a dynamic translation.
     * Command: /afterlang dynamic delete <namespace> <key> <language>
     *
     * @param sender Command sender
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     */
    @Subcommand("dynamic delete")
    public void dynamicDelete(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("namespace") @NotNull String namespace,
            @Arg("key") @NotNull String key,
            @Arg("language") @NotNull String language
    ) {
        DynamicContentAPI api = registry.getDynamicContentAPI();

        sendMsg(sender, "messages.admin.dynamic_deleting");

        api.deleteTranslation(namespace, key, language)
                .thenAccept(deleted -> {
                    if (deleted) {
                        sendMsg(sender, "messages.admin.dynamic_deleted",
                                Placeholder.of("namespace", namespace),
                                Placeholder.of("key", key),
                                Placeholder.of("language", language));

                        registry.getLogger().info("[AfterLangCommand] Dynamic translation deleted by " +
                                sender.getName() + ": " + namespace + ":" + key + " [" + language + "]");
                    } else {
                        sendMsg(sender, "messages.admin.dynamic_not_found",
                                Placeholder.of("namespace", namespace),
                                Placeholder.of("key", key),
                                Placeholder.of("language", language));
                    }
                })
                .exceptionally(ex -> {
                    sendMsg(sender, "messages.admin.dynamic_delete_failed",
                            Placeholder.of("error", ex.getMessage()));
                    registry.getLogger().warning("[AfterLangCommand] Failed to delete translation: " +
                            ex.getMessage());
                    return null;
                });
    }

    /**
     * Lists dynamic translations for a namespace.
     * Command: /afterlang dynamic list <namespace> [language]
     *
     * @param sender Command sender
     * @param namespace Namespace
     * @param language Optional language filter
     */
    @Subcommand("dynamic list")
    public void dynamicList(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("namespace") @NotNull String namespace,
            @Arg(value = "language", optional = true) @Nullable String language
    ) {
        DynamicContentAPI api = registry.getDynamicContentAPI();

        sendMsg(sender, "messages.admin.dynamic_loading");

        var future = language != null
                ? api.getTranslationsByLanguage(namespace, language)
                : api.getAllTranslations(namespace);

        future.thenAccept(translations -> {
            if (translations.isEmpty()) {
                sendMsg(sender, "messages.admin.dynamic_empty",
                        Placeholder.of("namespace", namespace));
                return;
            }

            sendMsg(sender, "messages.admin.separator");
            sendMsg(sender, "messages.admin.dynamic_list_header",
                    Placeholder.of("namespace", namespace + (language != null ? " [" + language + "]" : "")));
            sender.sendMessage("");

            for (Translation t : translations) {
                sendMsg(sender, "messages.admin.dynamic_entry",
                        Placeholder.of("key", t.key()),
                        Placeholder.of("language", t.language()));
                String preview = t.text().length() > 50
                        ? t.text().substring(0, 47) + "..."
                        : t.text();
                sendMsg(sender, "messages.admin.dynamic_entry_text",
                        Placeholder.of("text", preview));

                if (t.hasPlural()) {
                    sendMsg(sender, "messages.admin.dynamic_has_plural");
                }
            }

            sender.sendMessage("");
            sendMsg(sender, "messages.admin.dynamic_total",
                    Placeholder.of("count", String.valueOf(translations.size())));
            sendMsg(sender, "messages.admin.separator");
        })
        .exceptionally(ex -> {
            sendMsg(sender, "messages.admin.dynamic_delete_failed",
                    Placeholder.of("error", ex.getMessage()));
            registry.getLogger().warning("[AfterLangCommand] Failed to list translations: " +
                    ex.getMessage());
            return null;
        });
    }

    /**
     * Reloads dynamic translations from database.
     * Command: /afterlang dynamic reload <namespace>
     *
     * @param sender Command sender
     * @param namespace Namespace to reload
     */
    @Subcommand("dynamic reload")
    public void dynamicReload(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("namespace") @NotNull String namespace
    ) {
        DynamicContentAPI api = registry.getDynamicContentAPI();

        sendMsg(sender, "messages.admin.dynamic_reloading",
                Placeholder.of("namespace", namespace));

        long startTime = System.currentTimeMillis();

        api.reloadNamespace(namespace)
                .thenRun(() -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    sendMsg(sender, "messages.admin.dynamic_reloaded",
                            Placeholder.of("namespace", namespace));
                    sendMsg(sender, "messages.admin.reload-complete",
                            Placeholder.of("time", String.valueOf(elapsed)));

                    registry.getLogger().info("[AfterLangCommand] Dynamic translations reloaded by " +
                            sender.getName() + " for namespace: " + namespace + " in " + elapsed + "ms");
                })
                .exceptionally(ex -> {
                    sendMsg(sender, "messages.admin.dynamic_reload_failed",
                            Placeholder.of("error", ex.getMessage()));
                    registry.getLogger().warning("[AfterLangCommand] Failed to reload dynamic translations: " +
                            ex.getMessage());
                    return null;
                });
    }

    // ══════════════════════════════════════════════
    // EXPORT/IMPORT TOOLS (v1.2.0)
    // ══════════════════════════════════════════════

    /**
     * Exports dynamic translations to YAML files.
     * Command: /afterlang export <namespace> [language] [outputDir]
     *
     * @param sender Command sender
     * @param namespace Namespace to export
     * @param language Optional language filter
     * @param outputDir Optional output directory (defaults to plugins/AfterLanguage/exports)
     */
    @Subcommand("export")
    public void export(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("namespace") @NotNull String namespace,
            @Arg(value = "language", optional = true) @Nullable String language,
            @Arg(value = "outputDir", optional = true) @Nullable String outputDir
    ) {
        sendMsg(sender, "messages.admin.export_started",
                Placeholder.of("namespace", namespace));

        // Determine output directory
        java.nio.file.Path outputPath = outputDir != null
                ? java.nio.file.Paths.get(outputDir)
                : registry.getPlugin().getDataFolder().toPath().resolve("exports");

        // Get translations to export
        DynamicContentAPI api = registry.getDynamicContentAPI();
        var future = language != null
                ? api.getTranslationsByLanguage(namespace, language)
                : api.getAllTranslations(namespace);

        future.thenAccept(translations -> {
            if (translations.isEmpty()) {
                sendMsg(sender, "messages.admin.export_empty",
                        Placeholder.of("namespace", namespace));
                return;
            }

            try {
                TranslationExporter exporter = new TranslationExporter(registry.getLogger(), false);
                TranslationExporter.ExportResult result;

                if (language != null) {
                    // Single timestamped file: namespace_language_yyyy-MM-dd_HH-mm-ss.yml
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                            .withZone(ZoneId.systemDefault());
                    String timestamp = fmt.format(Instant.now());
                    String fileName = namespace + "_" + language + "_" + timestamp + ".yml";
                    java.nio.file.Path outputFile = outputPath.resolve(fileName);
                    result = exporter.exportToSingleFile(namespace, language, translations, outputFile);
                } else {
                    result = exporter.exportNamespace(namespace, translations, outputPath);
                }

                sendMsg(sender, "messages.admin.export_done",
                        Placeholder.of("count", String.valueOf(result.exportedCount())));
                sendMsg(sender, "messages.admin.export_files",
                        Placeholder.of("count", String.valueOf(result.files().size())));
                sendMsg(sender, "messages.admin.export_dir",
                        Placeholder.of("path", outputPath.toString()));

                registry.getLogger().info("[AfterLangCommand] Exported " + result.exportedCount() +
                        " translations by " + sender.getName() + " to " + outputPath);

            } catch (Exception e) {
                sendMsg(sender, "messages.admin.export_failed",
                        Placeholder.of("error", e.getMessage()));
                registry.getLogger().warning("[AfterLangCommand] Export failed: " + e.getMessage());
                e.printStackTrace();
            }
        })
        .exceptionally(ex -> {
            sendMsg(sender, "messages.admin.export_fetch_failed",
                    Placeholder.of("error", ex.getMessage()));
            return null;
        });
    }

    /**
     * Imports translations from a YAML file.
     * Command: /afterlang import <namespace> <language> <file> [overwrite]
     *
     * @param sender Command sender
     * @param namespace Target namespace
     * @param language Target language
     * @param file Path to YAML file
     * @param overwrite Optional: overwrite existing translations (default: false)
     */
    @Subcommand("import")
    public void importTranslations(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("namespace") @NotNull String namespace,
            @Arg("language") @NotNull String language,
            @Arg("file") @NotNull String file,
            @Arg(value = "overwrite", optional = true) @Nullable String overwrite
    ) {
        boolean shouldOverwrite = "true".equalsIgnoreCase(overwrite) || "yes".equalsIgnoreCase(overwrite);

        sendMsg(sender, "messages.admin.import_started",
                Placeholder.of("file", file));
        sendMsg(sender, "messages.admin.import_target",
                Placeholder.of("namespace", namespace),
                Placeholder.of("language", language));
        sendMsg(sender, "messages.admin.import_overwrite",
                Placeholder.of("value", msg(sender, shouldOverwrite
                        ? "messages.admin.import_overwrite_yes"
                        : "messages.admin.import_overwrite_no")));

        java.nio.file.Path filePath = java.nio.file.Paths.get(file);

        if (!java.nio.file.Files.exists(filePath)) {
            sendMsg(sender, "messages.admin.import_file_not_found",
                    Placeholder.of("file", file));
            return;
        }

        TranslationImporter importer = new TranslationImporter(
                registry.getDynamicTranslationRepo(),
                registry.getLogger(),
                false
        );

        importer.importFromFile(filePath, namespace, language, shouldOverwrite)
                .thenAccept(result -> {
                    sendMsg(sender, "messages.admin.import_done");
                    sendMsg(sender, "messages.admin.import_imported",
                            Placeholder.of("count", String.valueOf(result.importedCount())));
                    sendMsg(sender, "messages.admin.import_skipped",
                            Placeholder.of("count", String.valueOf(result.skippedCount())));
                    sendMsg(sender, "messages.admin.import_total",
                            Placeholder.of("count", String.valueOf(result.totalProcessed())));

                    if (!result.importedKeys().isEmpty()) {
                        String sample = result.importedKeys().stream().limit(5).reduce((a, b) -> a + ", " + b).orElse("");
                        sendMsg(sender, "messages.admin.import_sample",
                                Placeholder.of("keys", sample));
                    }

                    registry.getLogger().info("[AfterLangCommand] Imported " + result.importedCount() +
                            " translations by " + sender.getName() + " from " + file);

                    // Reload namespace to update cache
                    sendMsg(sender, "messages.admin.import_reloading");
                    registry.getDynamicContentAPI().reloadNamespace(namespace)
                            .thenRun(() -> sendMsg(sender, "messages.admin.import_cache_reloaded"));
                })
                .exceptionally(ex -> {
                    sendMsg(sender, "messages.admin.import_failed",
                            Placeholder.of("error", ex.getMessage()));
                    registry.getLogger().warning("[AfterLangCommand] Import failed: " + ex.getMessage());
                    ex.printStackTrace();
                    return null;
                });
    }

    // ══════════════════════════════════════════════
    // BACKUP MANAGEMENT (v1.2.0)
    // ══════════════════════════════════════════════

    /**
     * Creates a backup of dynamic translations.
     * Command: /afterlang backup create <namespace>
     *
     * @param sender Command sender
     * @param namespace Namespace to backup
     */
    @Subcommand("backup create")
    public void backupCreate(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("namespace") @NotNull String namespace
    ) {
        TranslationBackupService backupService = registry.getBackupService();

        if (!backupService.isEnabled()) {
            sendMsg(sender, "messages.admin.backup_disabled");
            return;
        }

        sendMsg(sender, "messages.admin.backup_creating",
                Placeholder.of("namespace", namespace));

        DynamicContentAPI api = registry.getDynamicContentAPI();
        api.getAllTranslations(namespace)
                .thenCompose(translations -> {
                    if (translations.isEmpty()) {
                        sendMsg(sender, "messages.admin.backup_empty",
                                Placeholder.of("namespace", namespace));
                        return CompletableFuture.completedFuture("");
                    }

                    return backupService.createBackup(namespace, translations);
                })
                .thenAccept(backupId -> {
                    if (!backupId.isEmpty()) {
                        sendMsg(sender, "messages.admin.backup_created");
                        sendMsg(sender, "messages.admin.backup_id",
                                Placeholder.of("id", backupId));
                        sendMsg(sender, "messages.admin.backup_restore_hint",
                                Placeholder.of("id", backupId),
                                Placeholder.of("namespace", namespace));

                        registry.getLogger().info("[AfterLangCommand] Backup created by " +
                                sender.getName() + ": " + backupId);
                    }
                })
                .exceptionally(ex -> {
                    sendMsg(sender, "messages.admin.backup_failed",
                            Placeholder.of("error", ex.getMessage()));
                    registry.getLogger().warning("[AfterLangCommand] Backup failed: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Lists available backups.
     * Command: /afterlang backup list [namespace]
     *
     * @param sender Command sender
     * @param namespace Optional namespace filter
     */
    @Subcommand("backup list")
    public void backupList(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg(value = "namespace", optional = true) @Nullable String namespace
    ) {
        TranslationBackupService backupService = registry.getBackupService();

        sendMsg(sender, "messages.admin.backup_loading");

        var future = namespace != null
                ? backupService.listBackups(namespace)
                : backupService.listBackups();

        future.thenAccept(backups -> {
            if (backups.isEmpty()) {
                if (namespace != null) {
                    sendMsg(sender, "messages.admin.backup_none_ns",
                            Placeholder.of("namespace", namespace));
                } else {
                    sendMsg(sender, "messages.admin.backup_none");
                }
                return;
            }

            sendMsg(sender, "messages.admin.separator");
            if (namespace != null) {
                sendMsg(sender, "messages.admin.backup_list_header_ns",
                        Placeholder.of("namespace", namespace));
            } else {
                sendMsg(sender, "messages.admin.backup_list_header");
            }
            sender.sendMessage("");

            for (TranslationBackupService.BackupInfo backup : backups) {
                sendMsg(sender, "messages.admin.backup_entry",
                        Placeholder.of("id", backup.backupId()));
                sendMsg(sender, "messages.admin.backup_timestamp",
                        Placeholder.of("time", backup.formattedTimestamp()));
                sendMsg(sender, "messages.admin.backup_files",
                        Placeholder.of("count", String.valueOf(backup.translationCount())));
            }

            sender.sendMessage("");
            sendMsg(sender, "messages.admin.backup_list_total",
                    Placeholder.of("count", String.valueOf(backups.size())));
            sendMsg(sender, "messages.admin.separator");
        })
        .exceptionally(ex -> {
            sendMsg(sender, "messages.admin.backup_failed",
                    Placeholder.of("error", ex.getMessage()));
            return null;
        });
    }

    /**
     * Restores a backup.
     * Command: /afterlang backup restore <backupId> <namespace>
     *
     * @param sender Command sender
     * @param backupId Backup ID to restore
     * @param namespace Namespace to restore
     */
    @Subcommand("backup restore")
    public void backupRestore(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("backupId") @NotNull String backupId,
            @Arg("namespace") @NotNull String namespace
    ) {
        TranslationBackupService backupService = registry.getBackupService();

        sendMsg(sender, "messages.admin.backup_restoring",
                Placeholder.of("id", backupId));
        sendMsg(sender, "messages.admin.backup_restore_warning");

        backupService.restoreBackup(backupId, namespace, registry.getImporter())
                .thenAccept(count -> {
                    sendMsg(sender, "messages.admin.backup_restored");
                    sendMsg(sender, "messages.admin.backup_restored_count",
                            Placeholder.of("count", String.valueOf(count)));

                    // Reload namespace
                    sendMsg(sender, "messages.admin.import_reloading");
                    registry.getDynamicContentAPI().reloadNamespace(namespace)
                            .thenRun(() -> sendMsg(sender, "messages.admin.import_cache_reloaded"));

                    registry.getLogger().info("[AfterLangCommand] Backup restored by " +
                            sender.getName() + ": " + backupId + " -> " + namespace);
                })
                .exceptionally(ex -> {
                    sendMsg(sender, "messages.admin.backup_restore_failed",
                            Placeholder.of("error", ex.getMessage()));
                    registry.getLogger().warning("[AfterLangCommand] Restore failed: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Deletes a backup.
     * Command: /afterlang backup delete <backupId>
     *
     * @param sender Command sender
     * @param backupId Backup ID to delete
     */
    @Subcommand("backup delete")
    public void backupDelete(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("backupId") @NotNull String backupId
    ) {
        TranslationBackupService backupService = registry.getBackupService();

        sendMsg(sender, "messages.admin.backup_deleting",
                Placeholder.of("id", backupId));

        backupService.deleteBackup(backupId)
                .thenRun(() -> {
                    sendMsg(sender, "messages.admin.backup_deleted",
                            Placeholder.of("id", backupId));

                    registry.getLogger().info("[AfterLangCommand] Backup deleted by " +
                            sender.getName() + ": " + backupId);
                })
                .exceptionally(ex -> {
                    sendMsg(sender, "messages.admin.backup_delete_failed",
                            Placeholder.of("error", ex.getMessage()));
                    registry.getLogger().warning("[AfterLangCommand] Delete failed: " + ex.getMessage());
                    return null;
                });
    }

    // ══════════════════════════════════════════════
    // CROWDIN COMMANDS (v1.3.0)
    // ══════════════════════════════════════════════

    /**
     * Syncs translations with Crowdin.
     * Command: /afterlang crowdin sync [namespace]
     *
     * @param sender Command sender
     * @param namespace Optional namespace to sync
     */
    @Subcommand("crowdin sync")
    public void crowdinSync(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg(value = "namespace", optional = true) @Nullable String namespace
    ) {
        CrowdinCommand cmd = registry.getCrowdinCommand();
        if (cmd == null) {
            sendMsg(sender, "messages.admin.crowdin_disabled");
            sendMsg(sender, "messages.admin.crowdin_enable_hint");
            return;
        }
        cmd.handleSync(sender, namespace);
    }

    /**
     * Uploads translations to Crowdin.
     * Command: /afterlang crowdin upload [namespace]
     *
     * @param sender Command sender
     * @param namespace Optional namespace to upload
     */
    @Subcommand("crowdin upload")
    public void crowdinUpload(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg(value = "namespace", optional = true) @Nullable String namespace
    ) {
        CrowdinCommand cmd = registry.getCrowdinCommand();
        if (cmd == null) {
            sendMsg(sender, "messages.admin.crowdin_disabled");
            return;
        }
        cmd.handleUpload(sender, namespace);
    }

    /**
     * Downloads translations from Crowdin.
     * Command: /afterlang crowdin download [namespace]
     *
     * @param sender Command sender
     * @param namespace Optional namespace to download
     */
    @Subcommand("crowdin download")
    public void crowdinDownload(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg(value = "namespace", optional = true) @Nullable String namespace
    ) {
        CrowdinCommand cmd = registry.getCrowdinCommand();
        if (cmd == null) {
            sendMsg(sender, "messages.admin.crowdin_disabled");
            return;
        }
        cmd.handleDownload(sender, namespace);
    }

    /**
     * Shows Crowdin integration status.
     * Command: /afterlang crowdin status
     *
     * @param sender Command sender
     */
    @Subcommand("crowdin status")
    public void crowdinStatus(CommandContext ctx, @Sender @NotNull CommandSender sender) {
        CrowdinCommand cmd = registry.getCrowdinCommand();
        if (cmd == null) {
            sendMsg(sender, "messages.admin.crowdin_disabled");
            sendMsg(sender, "messages.admin.crowdin_enable_hint");
            sendMsg(sender, "messages.admin.crowdin_env_hint");
            sendMsg(sender, "messages.admin.crowdin_env_project");
            sendMsg(sender, "messages.admin.crowdin_env_token");
            return;
        }
        cmd.handleStatus(sender);
    }

    /**
     * Uploads translations for a specific language to Crowdin.
     * Command: /afterlang crowdin uploadtranslation <language> <namespace>
     *
     * @param sender Command sender
     * @param language Language code to upload (e.g., "en_us")
     * @param namespace Namespace to upload
     */
    @Subcommand("crowdin uploadtranslation")
    public void crowdinUploadTranslation(
            CommandContext ctx,
            @Sender @NotNull CommandSender sender,
            @Arg("language") @NotNull String language,
            @Arg("namespace") @NotNull String namespace
    ) {
        CrowdinCommand cmd = registry.getCrowdinCommand();
        if (cmd == null) {
            sendMsg(sender, "messages.admin.crowdin_disabled");
            return;
        }
        cmd.handleUploadTranslation(sender, language, namespace);
    }

    /**
     * Tests Crowdin API connection.
     * Command: /afterlang crowdin test
     *
     * @param sender Command sender
     */
    @Subcommand("crowdin test")
    public void crowdinTest(CommandContext ctx, @Sender @NotNull CommandSender sender) {
        CrowdinCommand cmd = registry.getCrowdinCommand();
        if (cmd == null) {
            sendMsg(sender, "messages.admin.crowdin_disabled");
            return;
        }
        cmd.handleTest(sender);
    }

    // ══════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════

    /**
     * Handles displaying plugin statistics.
     */
    private void handleStats(@NotNull CommandSender sender) {
        sendMsg(sender, "messages.admin.separator");
        sendMsg(sender, "messages.admin.stats_header");
        sender.sendMessage("");

        // Registered namespaces
        var namespaces = registry.getNamespaceManager().getRegisteredNamespaces();
        sendMsg(sender, "messages.admin.stats_namespaces",
                Placeholder.of("count", String.valueOf(namespaces.size())));

        for (String namespace : namespaces) {
            Map<String, Object> nsStats = registry.getNamespaceManager()
                    .getNamespaceStats(namespace);
            int translationCount = (int) nsStats.getOrDefault("translation_count", 0);
            sendMsg(sender, "messages.admin.stats_namespace_entry",
                    Placeholder.of("namespace", namespace),
                    Placeholder.of("count", String.valueOf(translationCount)));
        }

        sender.sendMessage("");

        // Registry stats
        int totalTranslations = registry.getRegistry().size();
        sendMsg(sender, "messages.admin.stats_total",
                Placeholder.of("count", String.valueOf(totalTranslations)));

        sender.sendMessage("");

        // Player stats
        int cachedPlayers = registry.getPlayerLanguageRepo().getCachedPlayerCount();
        sendMsg(sender, "messages.admin.stats_players",
                Placeholder.of("count", String.valueOf(cachedPlayers)));

        // Cache hit rates
        CacheStats hotStats = registry.getCache().getHotStats();
        sendMsg(sender, "messages.admin.stats_l1_hit",
                Placeholder.of("rate", formatPercent(hotStats.hitRate())));

        sendMsg(sender, "messages.admin.separator");
    }

    /**
     * Handles displaying cache statistics.
     */
    private void handleCacheStats(@NotNull CommandSender sender) {
        sendMsg(sender, "messages.admin.separator");
        sendMsg(sender, "messages.admin.cache_header");
        sender.sendMessage("");

        // L1 Cache (Hot Cache)
        CacheStats l1Stats = registry.getCache().getHotStats();
        sendMsg(sender, "messages.admin.cache_l1_title");
        sendMsg(sender, "messages.admin.cache_requests",
                Placeholder.of("count", String.valueOf(l1Stats.requestCount())));
        sendMsg(sender, "messages.admin.cache_hits",
                Placeholder.of("count", String.valueOf(l1Stats.hitCount())),
                Placeholder.of("rate", formatPercent(l1Stats.hitRate())));
        sendMsg(sender, "messages.admin.cache_misses",
                Placeholder.of("count", String.valueOf(l1Stats.missCount())),
                Placeholder.of("rate", formatPercent(l1Stats.missRate())));
        sendMsg(sender, "messages.admin.cache_evictions",
                Placeholder.of("count", String.valueOf(l1Stats.evictionCount())));
        sendMsg(sender, "messages.admin.cache_load_time",
                Placeholder.of("time", formatNanos(l1Stats.averageLoadPenalty())));

        sender.sendMessage("");

        // L3 Cache (Template Cache)
        CacheStats l3Stats = registry.getCache().getTemplateStats();
        sendMsg(sender, "messages.admin.cache_l3_title");
        sendMsg(sender, "messages.admin.cache_requests",
                Placeholder.of("count", String.valueOf(l3Stats.requestCount())));
        sendMsg(sender, "messages.admin.cache_hits",
                Placeholder.of("count", String.valueOf(l3Stats.hitCount())),
                Placeholder.of("rate", formatPercent(l3Stats.hitRate())));
        sendMsg(sender, "messages.admin.cache_misses",
                Placeholder.of("count", String.valueOf(l3Stats.missCount())),
                Placeholder.of("rate", formatPercent(l3Stats.missRate())));
        sendMsg(sender, "messages.admin.cache_evictions",
                Placeholder.of("count", String.valueOf(l3Stats.evictionCount())));
        sendMsg(sender, "messages.admin.cache_load_time",
                Placeholder.of("time", formatNanos(l3Stats.averageLoadPenalty())));

        sender.sendMessage("");

        // Cache sizes
        sendMsg(sender, "messages.admin.cache_l1_size",
                Placeholder.of("count", String.valueOf(registry.getCache().getHotSize())));
        sendMsg(sender, "messages.admin.cache_l3_size",
                Placeholder.of("count", String.valueOf(registry.getCache().getTemplateSize())));

        sender.sendMessage("");

        // Actions
        sendMsg(sender, "messages.admin.cache_clear_hint");

        sendMsg(sender, "messages.admin.separator");
    }

    /**
     * Formats percentage (0.0 - 1.0 -> "XX.X%").
     */
    @NotNull
    private String formatPercent(double rate) {
        return String.format("%.1f%%", rate * 100);
    }

    /**
     * Formats nanoseconds to readable time.
     */
    @NotNull
    private String formatNanos(double nanos) {
        if (nanos < 1000) {
            return String.format("%.0fns", nanos);
        } else if (nanos < 1_000_000) {
            return String.format("%.1fμs", nanos / 1000);
        } else {
            return String.format("%.2fms", nanos / 1_000_000);
        }
    }
}
