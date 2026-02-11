package com.afterlands.afterlanguage.api.crowdin;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for Crowdin integration.
 *
 * <p>Provides methods for syncing translations between AfterLanguage and Crowdin.
 * This interface is the public contract - implementations handle the actual
 * HTTP communication with Crowdin API v2.</p>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * CrowdinAPI crowdin = registry.getCrowdinAPI();
 *
 * // Full bidirectional sync
 * crowdin.syncNamespace("myplugin")
 *     .thenAccept(result -> {
 *         if (result.isSuccess()) {
 *             logger.info("Synced " + result.stringsDownloaded() + " translations");
 *         }
 *     });
 *
 * // Upload only (local -> Crowdin)
 * crowdin.uploadNamespace("myplugin")
 *     .thenAccept(result -> logger.info("Uploaded " + result.stringsUploaded()));
 *
 * // Download only (Crowdin -> local)
 * crowdin.downloadNamespace("myplugin")
 *     .thenAccept(result -> logger.info("Downloaded " + result.stringsDownloaded()));
 * }</pre>
 *
 * <h3>Flow Overview:</h3>
 * <ul>
 *     <li><b>Upload</b>: Scans local pt_br translations, detects changes via MD5 hash,
 *         uploads new/modified strings to Crowdin project.</li>
 *     <li><b>Download</b>: Builds export from Crowdin (approved translations only),
 *         downloads ZIP, parses YAML files, merges with local DB via conflict resolution.</li>
 *     <li><b>Sync</b>: Executes upload followed by download (bidirectional).</li>
 * </ul>
 *
 * @author AfterLands Team
 * @since 1.3.0
 * @see SyncResult
 */
public interface CrowdinAPI {

    /**
     * Performs a full bidirectional sync for a namespace.
     *
     * <p>Executes the following steps:</p>
     * <ol>
     *     <li>Creates a backup of existing translations</li>
     *     <li>Uploads local pt_br changes to Crowdin</li>
     *     <li>Downloads approved translations from Crowdin</li>
     *     <li>Merges downloaded translations with conflict resolution</li>
     *     <li>Updates the registry and invalidates caches</li>
     *     <li>Fires completion events and notifies admins</li>
     * </ol>
     *
     * <p>If any step fails, the operation attempts to rollback to the backup.</p>
     *
     * @param namespace The namespace to sync (e.g., "afterjournal")
     * @return CompletableFuture with sync results
     */
    @NotNull
    CompletableFuture<SyncResult> syncNamespace(@NotNull String namespace);

    /**
     * Uploads local translations to Crowdin.
     *
     * <p>Only uploads pt_br (source language) translations that have changed
     * since the last sync (detected via MD5 hash comparison).</p>
     *
     * <p>Strings are uploaded in batches of 100 to respect API rate limits.</p>
     *
     * @param namespace The namespace to upload (e.g., "afterjournal")
     * @return CompletableFuture with upload results
     */
    @NotNull
    CompletableFuture<SyncResult> uploadNamespace(@NotNull String namespace);

    /**
     * Downloads translations from Crowdin.
     *
     * <p>Downloads approved translations for all configured languages.
     * The download process:</p>
     * <ol>
     *     <li>Requests an export build from Crowdin</li>
     *     <li>Polls until the build is ready (max 60s)</li>
     *     <li>Downloads the export ZIP file</li>
     *     <li>Parses YAML files from the archive</li>
     *     <li>Merges translations using configured conflict resolution</li>
     * </ol>
     *
     * @param namespace The namespace to download (e.g., "afterjournal")
     * @return CompletableFuture with download results
     */
    @NotNull
    CompletableFuture<SyncResult> downloadNamespace(@NotNull String namespace);

    /**
     * Syncs all registered namespaces.
     *
     * <p>Performs bidirectional sync for each namespace sequentially.</p>
     *
     * @return CompletableFuture with list of results (one per namespace)
     */
    @NotNull
    CompletableFuture<List<SyncResult>> syncAllNamespaces();

    /**
     * Gets the current sync status.
     *
     * @return true if a sync is currently in progress
     */
    boolean isSyncInProgress();

    /**
     * Gets the last sync result for a namespace.
     *
     * @param namespace The namespace to check
     * @return The last sync result, or null if never synced
     */
    @org.jetbrains.annotations.Nullable
    SyncResult getLastSyncResult(@NotNull String namespace);

    /**
     * Checks if Crowdin integration is enabled.
     *
     * @return true if enabled in config
     */
    boolean isEnabled();

    /**
     * Gets the configured Crowdin project ID.
     *
     * @return Project ID or null if not configured
     */
    @org.jetbrains.annotations.Nullable
    String getProjectId();

    /**
     * Tests the connection to Crowdin API.
     *
     * <p>Makes a simple API call to verify credentials are valid.</p>
     *
     * @return CompletableFuture with true if connection successful
     */
    @NotNull
    CompletableFuture<Boolean> testConnection();
}
