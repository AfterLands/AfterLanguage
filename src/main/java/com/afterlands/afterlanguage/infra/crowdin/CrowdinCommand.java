package com.afterlands.afterlanguage.infra.crowdin;

import com.afterlands.afterlanguage.api.crowdin.CrowdinAPI;
import com.afterlands.afterlanguage.api.crowdin.SyncResult;
import com.afterlands.afterlanguage.bootstrap.PluginRegistry;
import com.afterlands.afterlanguage.core.crowdin.CrowdinConfig;
import com.afterlands.afterlanguage.core.crowdin.CrowdinSyncEngine;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Command handler for Crowdin administration.
 *
 * <h3>Commands:</h3>
 * <ul>
 *     <li>/afterlang crowdin sync [namespace] - Full bidirectional sync</li>
 *     <li>/afterlang crowdin upload [namespace] - Upload local changes</li>
 *     <li>/afterlang crowdin download [namespace] - Download from Crowdin</li>
 *     <li>/afterlang crowdin status - Show sync status</li>
 *     <li>/afterlang crowdin test - Test Crowdin connection</li>
 * </ul>
 *
 * <p>This class provides the command methods that are called from AfterLangCommand.</p>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class CrowdinCommand {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final PluginRegistry registry;
    private final CrowdinSyncEngine syncEngine;
    private final CrowdinConfig crowdinConfig;
    private final CrowdinScheduler scheduler;

    /**
     * Creates a new CrowdinCommand handler.
     *
     * @param registry Plugin registry
     * @param syncEngine Sync engine
     * @param crowdinConfig Crowdin configuration
     * @param scheduler Auto-sync scheduler (may be null)
     */
    public CrowdinCommand(
            @NotNull PluginRegistry registry,
            @NotNull CrowdinSyncEngine syncEngine,
            @NotNull CrowdinConfig crowdinConfig,
            @Nullable CrowdinScheduler scheduler
    ) {
        this.registry = registry;
        this.syncEngine = syncEngine;
        this.crowdinConfig = crowdinConfig;
        this.scheduler = scheduler;
    }

    /**
     * Handles /afterlang crowdin sync [namespace]
     *
     * <p>Performs a full bidirectional sync.</p>
     *
     * @param sender Command sender
     * @param namespace Namespace to sync (null = all)
     */
    public void handleSync(@NotNull CommandSender sender, @Nullable String namespace) {
        if (syncEngine.isSyncInProgress()) {
            sender.sendMessage("§cSync already in progress. Please wait...");
            return;
        }

        if (namespace != null && !namespace.isEmpty()) {
            sender.sendMessage("§7Starting sync for namespace: §e" + namespace);

            syncEngine.syncNamespace(namespace)
                    .thenAccept(result -> sendSyncResult(sender, result))
                    .exceptionally(ex -> {
                        sender.sendMessage("§cSync failed: " + ex.getMessage());
                        return null;
                    });
        } else {
            sender.sendMessage("§7Starting sync for all namespaces...");

            syncEngine.syncAllNamespaces()
                    .thenAccept(results -> {
                        sender.sendMessage("§aSynced §e" + results.size() + "§a namespaces:");
                        for (SyncResult result : results) {
                            sendSyncResultCompact(sender, result);
                        }
                    })
                    .exceptionally(ex -> {
                        sender.sendMessage("§cSync failed: " + ex.getMessage());
                        return null;
                    });
        }
    }

    /**
     * Handles /afterlang crowdin upload [namespace]
     *
     * <p>Uploads local changes to Crowdin.</p>
     *
     * @param sender Command sender
     * @param namespace Namespace to upload (null = all)
     */
    public void handleUpload(@NotNull CommandSender sender, @Nullable String namespace) {
        if (syncEngine.isSyncInProgress()) {
            sender.sendMessage("§cSync already in progress. Please wait...");
            return;
        }

        if (namespace != null && !namespace.isEmpty()) {
            sender.sendMessage("§7Uploading namespace: §e" + namespace);

            syncEngine.uploadNamespace(namespace)
                    .thenAccept(result -> sendSyncResult(sender, result))
                    .exceptionally(ex -> {
                        sender.sendMessage("§cUpload failed: " + ex.getMessage());
                        return null;
                    });
        } else {
            sender.sendMessage("§7Uploading all namespaces...");

            syncEngine.syncAllNamespaces() // TODO: Add uploadAllNamespaces method
                    .thenAccept(results -> {
                        int totalUploaded = results.stream().mapToInt(SyncResult::stringsUploaded).sum();
                        sender.sendMessage("§aUpload complete: §e" + totalUploaded + "§a strings uploaded");
                    })
                    .exceptionally(ex -> {
                        sender.sendMessage("§cUpload failed: " + ex.getMessage());
                        return null;
                    });
        }
    }

    /**
     * Handles /afterlang crowdin download [namespace]
     *
     * <p>Downloads translations from Crowdin.</p>
     *
     * @param sender Command sender
     * @param namespace Namespace to download (null = all)
     */
    public void handleDownload(@NotNull CommandSender sender, @Nullable String namespace) {
        if (syncEngine.isSyncInProgress()) {
            sender.sendMessage("§cSync already in progress. Please wait...");
            return;
        }

        if (namespace != null && !namespace.isEmpty()) {
            sender.sendMessage("§7Downloading namespace: §e" + namespace);

            syncEngine.downloadNamespace(namespace)
                    .thenAccept(result -> sendSyncResult(sender, result))
                    .exceptionally(ex -> {
                        sender.sendMessage("§cDownload failed: " + ex.getMessage());
                        return null;
                    });
        } else {
            sender.sendMessage("§7Downloading all namespaces...");

            syncEngine.syncAllNamespaces() // TODO: Add downloadAllNamespaces method
                    .thenAccept(results -> {
                        int totalDownloaded = results.stream().mapToInt(SyncResult::stringsDownloaded).sum();
                        sender.sendMessage("§aDownload complete: §e" + totalDownloaded + "§a translations downloaded");
                    })
                    .exceptionally(ex -> {
                        sender.sendMessage("§cDownload failed: " + ex.getMessage());
                        return null;
                    });
        }
    }

    /**
     * Handles /afterlang crowdin status
     *
     * <p>Shows current sync status and scheduler info.</p>
     *
     * @param sender Command sender
     */
    public void handleStatus(@NotNull CommandSender sender) {
        sender.sendMessage("§7§m                                    ");
        sender.sendMessage("§6§lCrowdin Integration Status");
        sender.sendMessage("");

        // Server ID / Directory isolation
        if (crowdinConfig.isServerScoped()) {
            sender.sendMessage("§7Server ID: §e" + crowdinConfig.getServerId() + " §7(directory isolation: §aON§7)");
        } else {
            sender.sendMessage("§7Server ID: §8(none) §7(directory isolation: §cOFF§7)");
        }

        // Namespace directory overrides
        Map<String, String> nsDirectories = crowdinConfig.getNamespaceDirectories();
        if (!nsDirectories.isEmpty()) {
            sender.sendMessage("§7Namespace directories:");
            for (Map.Entry<String, String> entry : nsDirectories.entrySet()) {
                String directoryDisplay = entry.getValue().isEmpty() ? "§a(root/global)" : "§e" + entry.getValue();
                String fullPath = crowdinConfig.getCrowdinFilePathForNamespace(entry.getKey());
                sender.sendMessage("  §7- §e" + entry.getKey() + " §7-> " + directoryDisplay + " §8(" + fullPath + ")");
            }
        }

        // Connection status
        sender.sendMessage("§7Connection: §eChecking...");
        syncEngine.testConnection()
                .thenAccept(connected -> {
                    if (connected) {
                        sender.sendMessage("§7Connection: §aConnected ✓");
                    } else {
                        sender.sendMessage("§7Connection: §cFailed ✗");
                    }
                });

        // Sync in progress
        if (syncEngine.isSyncInProgress()) {
            sender.sendMessage("§7Status: §eSync in progress...");
        } else {
            sender.sendMessage("§7Status: §aIdle");
        }

        // Scheduler status
        sender.sendMessage("");
        if (scheduler != null) {
            sender.sendMessage(scheduler.getStatusInfo());
        } else {
            sender.sendMessage("§7Auto-sync: §cNot configured");
        }

        // Last sync results
        sender.sendMessage("");
        sender.sendMessage("§7Recent Syncs:");
        Map<String, SyncResult> lastResults = syncEngine.getAllLastSyncResults();
        if (lastResults.isEmpty()) {
            sender.sendMessage("  §8(no recent syncs)");
        } else {
            for (Map.Entry<String, SyncResult> entry : lastResults.entrySet()) {
                SyncResult result = entry.getValue();
                String statusColor = result.isSuccess() ? "§a" : (result.isFailed() ? "§c" : "§e");
                sender.sendMessage("  §7- §e" + entry.getKey() + " " + statusColor + result.status());
                if (result.completedAt() != null) {
                    sender.sendMessage("    §7at " + TIME_FORMAT.format(result.completedAt()));
                }
            }
        }

        sender.sendMessage("§7§m                                    ");
    }

    /**
     * Handles /afterlang crowdin test
     *
     * <p>Tests the Crowdin API connection.</p>
     *
     * @param sender Command sender
     */
    public void handleTest(@NotNull CommandSender sender) {
        sender.sendMessage("§7Testing Crowdin connection...");

        syncEngine.testConnection()
                .thenAccept(success -> {
                    if (success) {
                        sender.sendMessage("§aConnection successful! ✓");
                        sender.sendMessage("§7Crowdin API is accessible and credentials are valid.");
                    } else {
                        sender.sendMessage("§cConnection failed! ✗");
                        sender.sendMessage("§7Check your API token and project ID in crowdin.yml");
                    }
                })
                .exceptionally(ex -> {
                    sender.sendMessage("§cConnection error: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Handles /afterlang crowdin uploadtranslation <language> <namespace>
     *
     * <p>Uploads local translations for a specific non-source language to Crowdin.</p>
     *
     * @param sender Command sender
     * @param language Language code (e.g., "en_us")
     * @param namespace Namespace to upload
     */
    public void handleUploadTranslation(@NotNull CommandSender sender,
                                         @NotNull String language,
                                         @NotNull String namespace) {
        if (syncEngine.isSyncInProgress()) {
            sender.sendMessage("§cSync already in progress. Please wait...");
            return;
        }

        sender.sendMessage("§7Uploading translations for §e" + namespace + " §7[§e" + language + "§7]...");

        syncEngine.uploadTranslations(namespace, language)
                .thenAccept(result -> sendSyncResult(sender, result))
                .exceptionally(ex -> {
                    sender.sendMessage("§cUpload failed: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Sends formatted sync result to sender.
     */
    private void sendSyncResult(@NotNull CommandSender sender, @NotNull SyncResult result) {
        sender.sendMessage("§7§m                                    ");

        String statusColor = result.isSuccess() ? "§a" : (result.isFailed() ? "§c" : "§e");
        sender.sendMessage("§7Sync Result: " + statusColor + result.status());
        sender.sendMessage("§7Namespace: §e" + result.namespace());
        sender.sendMessage("§7Operation: §e" + result.operation());

        if (result.stringsUploaded() > 0) {
            sender.sendMessage("§7  ↑ Uploaded: §e" + result.stringsUploaded());
        }
        if (result.stringsDownloaded() > 0) {
            sender.sendMessage("§7  ↓ Downloaded: §a" + result.stringsDownloaded());
        }
        if (result.stringsSkipped() > 0) {
            sender.sendMessage("§7  ○ Skipped: §7" + result.stringsSkipped());
        }
        if (result.conflicts() > 0) {
            sender.sendMessage("§7  ⚠ Conflicts: §e" + result.conflicts());
        }

        if (!result.errors().isEmpty()) {
            sender.sendMessage("§7Errors:");
            for (String error : result.errors()) {
                sender.sendMessage("  §c- " + error);
            }
        }

        long duration = result.getDurationMillis();
        if (duration > 0) {
            sender.sendMessage("§7Duration: §e" + duration + "ms");
        }

        sender.sendMessage("§7§m                                    ");
    }

    /**
     * Sends compact sync result for multiple namespaces.
     */
    private void sendSyncResultCompact(@NotNull CommandSender sender, @NotNull SyncResult result) {
        String statusIcon = result.isSuccess() ? "§a✓" : (result.isFailed() ? "§c✗" : "§e~");
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(statusIcon).append(" §e").append(result.namespace());

        if (result.stringsUploaded() > 0 || result.stringsDownloaded() > 0) {
            sb.append(" §7(");
            if (result.stringsUploaded() > 0) {
                sb.append("↑").append(result.stringsUploaded());
            }
            if (result.stringsDownloaded() > 0) {
                if (result.stringsUploaded() > 0) sb.append(" ");
                sb.append("↓").append(result.stringsDownloaded());
            }
            sb.append(")");
        }

        sender.sendMessage(sb.toString());
    }
}
