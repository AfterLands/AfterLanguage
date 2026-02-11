package com.afterlands.afterlanguage.infra.crowdin;

import com.afterlands.afterlanguage.api.crowdin.SyncResult;
import com.afterlands.afterlanguage.core.crowdin.CrowdinConfig;
import com.afterlands.afterlanguage.core.crowdin.CrowdinSyncEngine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Scheduler for automatic Crowdin synchronization.
 *
 * <p>Uses Bukkit scheduler to run periodic sync operations.</p>
 *
 * <h3>Features:</h3>
 * <ul>
 *     <li>Configurable sync interval (default: 30 minutes)</li>
 *     <li>Runs asynchronously to avoid blocking main thread</li>
 *     <li>Notifies admins on completion (configurable)</li>
 *     <li>Skips if sync already in progress</li>
 * </ul>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class CrowdinScheduler {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final CrowdinSyncEngine syncEngine;
    private final CrowdinConfig config;
    private final Logger logger;

    @Nullable
    private BukkitTask scheduledTask;
    private Instant lastSyncTime;
    private int syncCount = 0;

    /**
     * Creates a new CrowdinScheduler.
     *
     * @param plugin Plugin instance
     * @param syncEngine Sync engine to use
     * @param config Crowdin configuration
     * @param logger Logger for output
     */
    public CrowdinScheduler(
            @NotNull Plugin plugin,
            @NotNull CrowdinSyncEngine syncEngine,
            @NotNull CrowdinConfig config,
            @NotNull Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.syncEngine = Objects.requireNonNull(syncEngine, "syncEngine cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    /**
     * Starts the automatic sync scheduler.
     *
     * <p>Does nothing if auto-sync is disabled in config.</p>
     */
    public void start() {
        if (!config.isAutoSyncEnabled()) {
            logger.info("[CrowdinScheduler] Auto-sync disabled (interval = 0)");
            return;
        }

        if (scheduledTask != null) {
            logger.warning("[CrowdinScheduler] Scheduler already running");
            return;
        }

        int intervalMinutes = config.getAutoSyncIntervalMinutes();
        long intervalTicks = intervalMinutes * 60L * 20L; // minutes -> ticks

        // Schedule with delay = interval (don't run immediately on startup)
        this.scheduledTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::performAutoSync,
                intervalTicks, // Initial delay
                intervalTicks  // Repeat interval
        );

        logger.info("[CrowdinScheduler] Auto-sync started (interval: " + intervalMinutes + " minutes)");
    }

    /**
     * Stops the automatic sync scheduler.
     */
    public void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
            logger.info("[CrowdinScheduler] Auto-sync stopped");
        }
    }

    /**
     * Checks if the scheduler is running.
     *
     * @return true if scheduler is active
     */
    public boolean isRunning() {
        return scheduledTask != null;
    }

    /**
     * Gets the last sync time.
     *
     * @return Last sync time or null if never synced
     */
    @Nullable
    public Instant getLastSyncTime() {
        return lastSyncTime;
    }

    /**
     * Gets the total number of auto-syncs performed.
     *
     * @return Sync count
     */
    public int getSyncCount() {
        return syncCount;
    }

    /**
     * Triggers an immediate sync (outside of schedule).
     *
     * @return true if sync was started, false if already in progress
     */
    public boolean triggerSyncNow() {
        if (syncEngine.isSyncInProgress()) {
            return false;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::performAutoSync);
        return true;
    }

    /**
     * Performs the automatic sync operation.
     */
    private void performAutoSync() {
        if (syncEngine.isSyncInProgress()) {
            logger.info("[CrowdinScheduler] Skipping auto-sync: sync already in progress");
            return;
        }

        logger.info("[CrowdinScheduler] Starting auto-sync...");
        Instant startTime = Instant.now();

        syncEngine.syncAllNamespaces()
                .thenAccept(results -> {
                    lastSyncTime = Instant.now();
                    syncCount++;

                    // Summarize results
                    int totalUploaded = 0;
                    int totalDownloaded = 0;
                    int totalConflicts = 0;
                    int successCount = 0;
                    int failCount = 0;

                    for (SyncResult result : results) {
                        totalUploaded += result.stringsUploaded();
                        totalDownloaded += result.stringsDownloaded();
                        totalConflicts += result.conflicts();
                        if (result.isSuccess()) {
                            successCount++;
                        } else if (result.isFailed()) {
                            failCount++;
                        }
                    }

                    long durationMs = lastSyncTime.toEpochMilli() - startTime.toEpochMilli();

                    String summary = String.format(
                            "Auto-sync #%d completed in %dms: %d namespaces (%d ok, %d failed), " +
                            "%d uploaded, %d downloaded, %d conflicts",
                            syncCount, durationMs, results.size(), successCount, failCount,
                            totalUploaded, totalDownloaded, totalConflicts
                    );

                    logger.info("[CrowdinScheduler] " + summary);

                    // Notify admins
                    if (config.isNotifyAdmins()) {
                        notifyAdmins(results, durationMs);
                    }
                })
                .exceptionally(ex -> {
                    logger.severe("[CrowdinScheduler] Auto-sync failed: " + ex.getMessage());
                    ex.printStackTrace();
                    return null;
                });
    }

    /**
     * Notifies online admins about sync completion.
     */
    private void notifyAdmins(@NotNull List<SyncResult> results, long durationMs) {
        if (results.isEmpty()) {
            return;
        }

        // Build notification message
        StringBuilder message = new StringBuilder();
        message.append("§7[§6Crowdin§7] Auto-sync completed in ")
               .append(durationMs).append("ms\n");

        int totalUploaded = 0;
        int totalDownloaded = 0;
        int failCount = 0;

        for (SyncResult result : results) {
            totalUploaded += result.stringsUploaded();
            totalDownloaded += result.stringsDownloaded();
            if (result.isFailed()) failCount++;
        }

        if (totalUploaded > 0) {
            message.append("§7  ↑ Uploaded: §e").append(totalUploaded).append("\n");
        }
        if (totalDownloaded > 0) {
            message.append("§7  ↓ Downloaded: §a").append(totalDownloaded).append("\n");
        }
        if (failCount > 0) {
            message.append("§7  ✗ Failed: §c").append(failCount).append(" namespaces\n");
        }

        String[] lines = message.toString().split("\n");

        // Send to all admins (run on main thread)
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("afterlanguage.admin")) {
                    for (String line : lines) {
                        player.sendMessage(line);
                    }
                }
            }
        });
    }

    /**
     * Gets status information about the scheduler.
     *
     * @return Status string for display
     */
    @NotNull
    public String getStatusInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("§7Auto-sync: ");

        if (!config.isAutoSyncEnabled()) {
            sb.append("§cDisabled");
        } else if (isRunning()) {
            sb.append("§aRunning");
            sb.append(" §7(every ").append(config.getAutoSyncIntervalMinutes()).append(" min)");
        } else {
            sb.append("§eStopped");
        }

        sb.append("\n§7Total syncs: §e").append(syncCount);

        if (lastSyncTime != null) {
            sb.append("\n§7Last sync: §f").append(TIME_FORMAT.format(lastSyncTime));
        }

        if (syncEngine.isSyncInProgress()) {
            sb.append("\n§eSync in progress...");
        }

        return sb.toString();
    }
}
