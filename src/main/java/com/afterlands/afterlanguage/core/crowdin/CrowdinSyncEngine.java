package com.afterlands.afterlanguage.core.crowdin;

import com.afterlands.afterlanguage.api.crowdin.SyncResult;
import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.afterlanguage.api.service.DynamicContentAPI;
import com.afterlands.afterlanguage.core.cache.TranslationCache;
import com.afterlands.afterlanguage.core.io.TranslationBackupService;
import com.afterlands.afterlanguage.core.resolver.NamespaceManager;
import com.afterlands.afterlanguage.core.resolver.TranslationRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Main orchestrator for Crowdin synchronization.
 *
 * <p>Coordinates upload and download operations, manages backups,
 * and handles error recovery.</p>
 *
 * <h3>Sync Flow:</h3>
 * <ol>
 *     <li>Create backup (via TranslationBackupService)</li>
 *     <li>Upload phase: Detect changes (MD5), upload to Crowdin</li>
 *     <li>Download phase: Build export, download, parse, merge</li>
 *     <li>Update registry and invalidate caches</li>
 *     <li>Fire events and notify admins</li>
 *     <li>On error: Rollback to backup</li>
 * </ol>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class CrowdinSyncEngine {

    private final CrowdinClient client;
    private final DynamicContentAPI dynamicAPI;
    private final TranslationRegistry registry;
    private final NamespaceManager namespaceManager;
    private final TranslationCache cache;
    private final TranslationBackupService backupService;
    private final UploadStrategy uploadStrategy;
    private final DownloadStrategy downloadStrategy;
    private final ConflictResolver conflictResolver;
    private final CrowdinConfig config;
    private final Logger logger;
    private final boolean debug;

    private final Set<String> syncsInProgress = ConcurrentHashMap.newKeySet();
    private final Map<String, SyncResult> lastSyncResults = new ConcurrentHashMap<>();
    private final Map<String, Long> resolvedDirectoryIds = new ConcurrentHashMap<>();

    // Shared build cache to prevent 409 conflicts when multiple namespaces download simultaneously
    private volatile CompletableFuture<Map<String, String>> sharedBuildCache = null;
    private volatile Instant sharedBuildTimestamp = null;
    private static final long BUILD_CACHE_TTL_MS = 30_000; // 30 seconds

    /**
     * Creates a new CrowdinSyncEngine.
     */
    public CrowdinSyncEngine(
            @NotNull CrowdinClient client,
            @NotNull DynamicContentAPI dynamicAPI,
            @NotNull TranslationRegistry registry,
            @NotNull NamespaceManager namespaceManager,
            @NotNull TranslationCache cache,
            @NotNull TranslationBackupService backupService,
            @NotNull UploadStrategy uploadStrategy,
            @NotNull DownloadStrategy downloadStrategy,
            @NotNull ConflictResolver conflictResolver,
            @NotNull CrowdinConfig config,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.client = Objects.requireNonNull(client);
        this.dynamicAPI = Objects.requireNonNull(dynamicAPI);
        this.registry = Objects.requireNonNull(registry);
        this.namespaceManager = Objects.requireNonNull(namespaceManager);
        this.cache = Objects.requireNonNull(cache);
        this.backupService = Objects.requireNonNull(backupService);
        this.uploadStrategy = Objects.requireNonNull(uploadStrategy);
        this.downloadStrategy = Objects.requireNonNull(downloadStrategy);
        this.conflictResolver = Objects.requireNonNull(conflictResolver);
        this.config = Objects.requireNonNull(config);
        this.logger = Objects.requireNonNull(logger);
        this.debug = debug;
    }

    /**
     * Resolves the Crowdin directory ID for a namespace.
     *
     * <p>Resolution is based on {@code CrowdinConfig.getDirectoryPathForNamespace()}:</p>
     * <ul>
     *     <li>Empty path → 0 (root, no directory)</li>
     *     <li>Non-empty → gets or creates the directory path on Crowdin, cached by path</li>
     * </ul>
     *
     * @param namespace Namespace to resolve directory for
     * @return CompletableFuture with directory ID (0 for root)
     */
    @NotNull
    private CompletableFuture<Long> resolveDirectoryId(@NotNull String namespace) {
        List<String> directoryPath = config.getDirectoryPathForNamespace(namespace);

        // Root-level namespace (e.g., ["afterlanguage"])
        if (directoryPath.size() == 1 && directoryPath.get(0).equals(namespace)) {
            return CompletableFuture.completedFuture(0L);
        }

        // Build cache key from full path
        String pathKey = String.join("/", directoryPath);

        // Check cache
        Long cached = resolvedDirectoryIds.get(pathKey);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return client.getOrCreateDirectoryPath(directoryPath)
                .thenApply(id -> {
                    resolvedDirectoryIds.put(pathKey, id);
                    logger.info("[CrowdinSyncEngine] Resolved directory '" +
                                pathKey + "' -> ID " + id +
                                " (namespace: " + namespace + ")");
                    return id;
                });
    }

    /**
     * Performs a full bidirectional sync for a namespace.
     *
     * <p>Executes upload followed by download with backup and rollback support.</p>
     *
     * @param namespace Namespace to sync
     * @return CompletableFuture with sync result
     */
    @NotNull
    public CompletableFuture<SyncResult> syncNamespace(@NotNull String namespace) {
        // Check if this specific namespace is already being synced
        if (!syncsInProgress.add(namespace)) {
            logger.warning("[CrowdinSyncEngine] Sync already in progress for namespace: " + namespace);
            return CompletableFuture.completedFuture(
                    SyncResult.failed(
                            generateSyncId(),
                            SyncResult.SyncOperation.FULL_SYNC,
                            namespace,
                            List.of("Sync already in progress for this namespace"),
                            Instant.now()
                    )
            );
        }

        String syncId = generateSyncId();
        Instant startedAt = Instant.now();

        logger.info("[CrowdinSyncEngine] Starting sync for namespace: " + namespace + " (id: " + syncId + ")");

        // Track the result
        SyncResult running = SyncResult.running(syncId, SyncResult.SyncOperation.FULL_SYNC, namespace);
        lastSyncResults.put(namespace, running);

        // Create backup before sync
        CompletableFuture<String> backupFuture;
        if (config.isBackupBeforeSync() && backupService.isEnabled()) {
            backupFuture = dynamicAPI.getAllTranslations(namespace)
                    .thenCompose(translations -> {
                        if (translations.isEmpty()) {
                            return CompletableFuture.completedFuture("");
                        }
                        return backupService.createBackup(namespace, translations);
                    });
        } else {
            backupFuture = CompletableFuture.completedFuture("");
        }

        return backupFuture.thenCompose(backupId -> {
            if (!backupId.isEmpty()) {
                logger.info("[CrowdinSyncEngine] Created backup: " + backupId);
            }

            // Resolve branch before starting sync operations
            return resolveDirectoryId(namespace).thenCompose(directoryId -> {

            // Execute upload phase
            return uploadNamespace(namespace, directoryId).thenCompose(uploadResult -> {
                // Conditionally upload translations for non-source languages
                CompletableFuture<Void> translationUploadFuture;
                if (config.isUploadTranslationsEnabled()) {
                    String srcLang = config.getSourceLanguage().replace("-", "_").toLowerCase();
                    Set<String> allLanguages = new HashSet<>(namespaceManager.getLanguagesDir().toFile().list() != null
                            ? Arrays.asList(namespaceManager.getLanguagesDir().toFile().list())
                            : List.of());
                    allLanguages.remove(srcLang);

                    List<CompletableFuture<SyncResult>> langFutures = new ArrayList<>();
                    for (String lang : allLanguages) {
                        langFutures.add(uploadTranslations(namespace, lang));
                    }
                    translationUploadFuture = CompletableFuture.allOf(
                            langFutures.toArray(new CompletableFuture[0]));
                } else {
                    translationUploadFuture = CompletableFuture.completedFuture(null);
                }

                return translationUploadFuture.thenCompose(v ->
                // Execute download phase
                downloadNamespace(namespace, directoryId).thenApply(downloadResult -> {
                    // Combine results
                    int totalUploaded = uploadResult.stringsUploaded();
                    int totalDownloaded = downloadResult.stringsDownloaded();
                    int totalSkipped = uploadResult.stringsSkipped() + downloadResult.stringsSkipped();
                    int totalConflicts = downloadResult.conflicts();

                    List<String> allErrors = new ArrayList<>();
                    allErrors.addAll(uploadResult.errors());
                    allErrors.addAll(downloadResult.errors());

                    SyncResult.SyncStatus status = allErrors.isEmpty()
                            ? SyncResult.SyncStatus.SUCCESS
                            : SyncResult.SyncStatus.PARTIAL;

                    SyncResult result = new SyncResult(
                            syncId,
                            SyncResult.SyncOperation.FULL_SYNC,
                            namespace,
                            status,
                            totalUploaded,
                            totalDownloaded,
                            totalSkipped,
                            totalConflicts,
                            allErrors,
                            startedAt,
                            Instant.now()
                    );

                    lastSyncResults.put(namespace, result);
                    logger.info("[CrowdinSyncEngine] Sync completed: " + result);

                    // Reload namespace to update caches
                    if (config.isHotReload() && totalDownloaded > 0) {
                        dynamicAPI.reloadNamespace(namespace)
                                .thenRun(() -> {
                                    cache.invalidateNamespace(namespace);
                                    logger.info("[CrowdinSyncEngine] Namespace reloaded: " + namespace);
                                });
                    }

                    // Note: Cleanup is handled in syncAllNamespaces() to avoid running multiple times
                    // Individual namespace sync does NOT trigger cleanup to prevent duplicates

                    return result;
                }));
            });
            }); // close resolveDirectoryId
        }).exceptionally(ex -> {
            logger.severe("[CrowdinSyncEngine] Sync failed for " + namespace + ": " + ex.getMessage());
            ex.printStackTrace();

            SyncResult failed = SyncResult.failed(
                    syncId,
                    SyncResult.SyncOperation.FULL_SYNC,
                    namespace,
                    List.of(ex.getMessage()),
                    startedAt
            );
            lastSyncResults.put(namespace, failed);
            return failed;

        }).whenComplete((result, ex) -> {
            syncsInProgress.remove(namespace);
        });
    }

    /**
     * Uploads local translations to Crowdin (branch-aware, for internal use by syncNamespace).
     *
     * @param namespace Namespace to upload
     * @param directoryId Branch ID (0 for root)
     * @return CompletableFuture with sync result
     */
    @NotNull
    private CompletableFuture<SyncResult> uploadNamespace(@NotNull String namespace, long directoryId) {
        String syncId = generateSyncId();
        Instant startedAt = Instant.now();

        logger.info("[CrowdinSyncEngine] Starting upload for namespace: " + namespace);

        // Get source language translations from the registry (YAML-loaded).
        // The registry is the source of truth — it always has the latest translations
        // from YAML files, including newly added keys that the DB doesn't know about.
        String sourceLanguage = config.getSourceLanguage().replace("-", "_").toLowerCase();

        List<Translation> translations = registry.getAllForNamespace(namespace, sourceLanguage);

        if (translations.isEmpty()) {
            logger.info("[CrowdinSyncEngine] No source translations found for: " + namespace);
            return CompletableFuture.completedFuture(
                    SyncResult.uploadSuccess(syncId, namespace, 0, 0, startedAt)
            );
        }

        logger.info("[CrowdinSyncEngine] Found " + translations.size() +
                " source translations in registry for upload (namespace: " + namespace + ")");

        // Get stored hashes for change detection
        return getStoredHashes(namespace).thenCompose(storedHashes -> {
            // Execute upload (branch-aware)
            return uploadStrategy.uploadNamespace(namespace, translations, storedHashes, directoryId)
                    .thenApply(uploadResult -> {
                        SyncResult.SyncStatus status = uploadResult.errors().isEmpty()
                                ? SyncResult.SyncStatus.SUCCESS
                                : SyncResult.SyncStatus.PARTIAL;

                        return new SyncResult(
                                syncId,
                                SyncResult.SyncOperation.UPLOAD,
                                namespace,
                                status,
                                uploadResult.uploaded(),
                                0,
                                uploadResult.skipped(),
                                0,
                                uploadResult.errors(),
                                startedAt,
                                Instant.now()
                        );
                    });
        }).exceptionally(ex -> {
            logger.warning("[CrowdinSyncEngine] Upload failed for " + namespace + ": " + ex.getMessage());
            return SyncResult.failed(
                    syncId,
                    SyncResult.SyncOperation.UPLOAD,
                    namespace,
                    List.of(ex.getMessage()),
                    startedAt
            );
        });
    }

    /**
     * Uploads local translations to Crowdin (public, resolves branch automatically).
     *
     * @param namespace Namespace to upload
     * @return CompletableFuture with sync result
     */
    @NotNull
    public CompletableFuture<SyncResult> uploadNamespace(@NotNull String namespace) {
        return resolveDirectoryId(namespace).thenCompose(directoryId -> uploadNamespace(namespace, directoryId));
    }

    /**
     * Downloads translations from Crowdin (branch-aware, for internal use by syncNamespace).
     *
     * @param namespace Namespace to download
     * @param directoryId Branch ID (0 for root)
     * @return CompletableFuture with sync result
     */
    @NotNull
    private CompletableFuture<SyncResult> downloadNamespace(@NotNull String namespace, long directoryId) {
        String syncId = generateSyncId();
        Instant startedAt = Instant.now();

        logger.info("[CrowdinSyncEngine] Starting download for namespace: " + namespace);

        // Note: directoryId is not used in download anymore (filtering happens in parse)
        // Use shared build to avoid 409 conflicts when multiple namespaces download simultaneously
        return getOrCreateSharedBuild()
                .thenCompose(extractedFiles ->
                    downloadStrategy.downloadNamespaceFromExtractedFiles(namespace, conflictResolver, extractedFiles)
                )
                .thenCompose(downloadResult -> {
                    // Reload namespace from YAML files so registry reflects the changes
                    return namespaceManager.reloadNamespace(namespace)
                            .thenApply(v -> {
                                logger.info("[CrowdinSyncEngine] Reloaded namespace '" + namespace + "' after download");
                                return downloadResult;
                            })
                            .exceptionally(ex -> {
                                logger.warning("[CrowdinSyncEngine] Failed to reload namespace after download: " + ex.getMessage());
                                return downloadResult;
                            });
                })
                .thenApply(downloadResult -> {
                    SyncResult.SyncStatus status = downloadResult.errors().isEmpty()
                            ? SyncResult.SyncStatus.SUCCESS
                            : SyncResult.SyncStatus.PARTIAL;

                    return new SyncResult(
                            syncId,
                            SyncResult.SyncOperation.DOWNLOAD,
                            namespace,
                            status,
                            0,
                            downloadResult.downloaded(),
                            downloadResult.skipped(),
                            downloadResult.conflicts(),
                            downloadResult.errors(),
                            startedAt,
                            Instant.now()
                    );
                })
                .exceptionally(ex -> {
                    logger.warning("[CrowdinSyncEngine] Download failed for " + namespace + ": " + ex.getMessage());
                    return SyncResult.failed(
                            syncId,
                            SyncResult.SyncOperation.DOWNLOAD,
                            namespace,
                            List.of(ex.getMessage()),
                            startedAt
                    );
                });
    }

    /**
     * Downloads translations from Crowdin (public, resolves branch automatically).
     *
     * @param namespace Namespace to download
     * @return CompletableFuture with sync result
     */
    @NotNull
    public CompletableFuture<SyncResult> downloadNamespace(@NotNull String namespace) {
        return resolveDirectoryId(namespace).thenCompose(directoryId -> downloadNamespace(namespace, directoryId));
    }

    /**
     * Uploads translations for a specific (non-source) language to Crowdin.
     *
     * <p>Fetches translations from the registry for the given language and namespace,
     * then uploads them as translation files to Crowdin.</p>
     *
     * @param namespace Namespace to upload translations for
     * @param language AfterLanguage language code (e.g., "en_us")
     * @return CompletableFuture with sync result
     */
    @NotNull
    public CompletableFuture<SyncResult> uploadTranslations(@NotNull String namespace, @NotNull String language) {
        String syncId = generateSyncId();
        Instant startedAt = Instant.now();

        logger.info("[CrowdinSyncEngine] Starting translation upload for " + namespace + " [" + language + "]");

        List<Translation> translations = registry.getAllForNamespace(namespace, language);

        if (translations.isEmpty()) {
            logger.info("[CrowdinSyncEngine] No translations found for " + namespace + " [" + language + "]");
            return CompletableFuture.completedFuture(
                    SyncResult.uploadSuccess(syncId, namespace, 0, 0, startedAt)
            );
        }

        // Resolve the fileId for this namespace on Crowdin
        String filePath = config.getCrowdinFilePathForNamespace(namespace);
        return client.getFileByPath(filePath)
                .thenCompose(file -> {
                    if (file == null) {
                        return CompletableFuture.completedFuture(
                                SyncResult.failed(syncId, SyncResult.SyncOperation.UPLOAD, namespace,
                                        List.of("Namespace file not found on Crowdin: " + filePath +
                                                ". Upload source translations first."),
                                        startedAt)
                        );
                    }

                    long fileId = file.get("id").getAsLong();
                    return uploadStrategy.uploadTranslationsForLanguage(namespace, language, translations, fileId)
                            .thenApply(uploadResult -> {
                                SyncResult.SyncStatus status = uploadResult.errors().isEmpty()
                                        ? SyncResult.SyncStatus.SUCCESS
                                        : SyncResult.SyncStatus.PARTIAL;

                                return new SyncResult(
                                        syncId,
                                        SyncResult.SyncOperation.UPLOAD,
                                        namespace,
                                        status,
                                        uploadResult.uploaded(),
                                        0,
                                        uploadResult.skipped(),
                                        0,
                                        uploadResult.errors(),
                                        startedAt,
                                        Instant.now()
                                );
                            });
                })
                .exceptionally(ex -> {
                    logger.warning("[CrowdinSyncEngine] Translation upload failed for " +
                                 namespace + " [" + language + "]: " + ex.getMessage());
                    return SyncResult.failed(syncId, SyncResult.SyncOperation.UPLOAD, namespace,
                            List.of(ex.getMessage()), startedAt);
                });
    }

    /**
     * Syncs all configured namespaces.
     *
     * @return CompletableFuture with list of results
     */
    @NotNull
    public CompletableFuture<List<SyncResult>> syncAllNamespaces() {
        // Keep in-memory namespace index aligned with disk before deciding what to sync.
        namespaceManager.discoverAndRegisterNewNamespaces();

        List<String> namespaces = config.getSyncNamespaces();

        if (namespaces.isEmpty()) {
            // Get all registered namespaces from namespace manager
            namespaces = new ArrayList<>(namespaceManager.getRegisteredNamespaces());
        }

        if (namespaces.isEmpty()) {
            logger.info("[CrowdinSyncEngine] No namespaces to sync");
            return CompletableFuture.completedFuture(List.of());
        }

        List<CompletableFuture<SyncResult>> futures = new ArrayList<>();
        for (String namespace : namespaces) {
            if (config.shouldSyncNamespace(namespace)) {
                futures.add(syncNamespace(namespace));
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<SyncResult> results = futures.stream()
                            .map(CompletableFuture::join)
                            .toList();

                    // Run cleanup once after all namespaces synced (if enabled)
                    if (config.isCleanupOnSync() && config.isServerScoped()) {
                        boolean anySuccess = results.stream().anyMatch(SyncResult::isSuccess);
                        if (anySuccess) {
                            logger.info("[CrowdinSyncEngine] Running automatic cleanup after batch sync (cleanup-on-sync enabled)");
                            findOrphanedResources()
                                    .thenCompose(cleanupResult -> {
                                        if (cleanupResult.hasOrphans()) {
                                            logger.info("[CrowdinSyncEngine] Found " + cleanupResult.totalOrphans() +
                                                       " orphaned resources, deleting...");
                                            return cleanupOrphanedResources(
                                                    cleanupResult.orphanedFiles(),
                                                    cleanupResult.orphanedDirectories()
                                            );
                                        } else {
                                            logger.info("[CrowdinSyncEngine] No orphaned resources found");
                                            return CompletableFuture.completedFuture(cleanupResult);
                                        }
                                    })
                                    .thenAccept(cleanupResult -> {
                                        if (cleanupResult.filesDeleted() > 0 || cleanupResult.directoriesDeleted() > 0) {
                                            logger.info("[CrowdinSyncEngine] Cleanup complete: " +
                                                       cleanupResult.filesDeleted() + " files, " +
                                                       cleanupResult.directoriesDeleted() + " directories deleted");
                                        }
                                    })
                                    .exceptionally(ex -> {
                                        logger.warning("[CrowdinSyncEngine] Automatic cleanup failed: " + ex.getMessage());
                                        return null;
                                    });
                        }
                    }

                    return results;
                });
    }

    /**
     * Gets or creates a shared Crowdin build for downloading translations.
     *
     * <p>When multiple namespaces sync simultaneously, they share the same build to avoid
     * 409 "Another build is currently in progress" errors from Crowdin API.</p>
     *
     * <p>The build is cached for 30 seconds. After that, a new build is triggered.</p>
     *
     * @return CompletableFuture with extracted YAML files from the build ZIP
     */
    @NotNull
    private synchronized CompletableFuture<Map<String, String>> getOrCreateSharedBuild() {
        Instant now = Instant.now();

        // Check if cached build is still valid
        if (sharedBuildCache != null && sharedBuildTimestamp != null) {
            long ageMs = now.toEpochMilli() - sharedBuildTimestamp.toEpochMilli();
            if (ageMs < BUILD_CACHE_TTL_MS) {
                if (debug) {
                    logger.info("[CrowdinSyncEngine] Reusing cached build (age: " + ageMs + "ms)");
                }
                return sharedBuildCache;
            }
        }

        // Create new build
        logger.info("[CrowdinSyncEngine] Creating new shared Crowdin build for all namespaces");
        sharedBuildTimestamp = now;

        List<String> targetLanguages = List.of();
        sharedBuildCache = client.buildTranslations(
                targetLanguages,
                config.isSkipUntranslated(),
                config.isExportApprovedOnly()
        ).thenCompose(buildId -> {
            if (debug) {
                logger.info("[CrowdinSyncEngine] Shared build started: " + buildId);
            }
            return client.waitForBuild(buildId, 60);
        }).thenCompose(buildStatus -> {
            long buildId = buildStatus.get("id").getAsLong();
            return client.getDownloadUrl(buildId);
        }).thenCompose(downloadUrl -> {
            if (debug) {
                logger.info("[CrowdinSyncEngine] Downloading shared build from: " + downloadUrl);
            }
            return client.downloadExport(downloadUrl);
        }).thenApply(zipData -> {
            try {
                Map<String, String> files = client.extractYamlFromZip(zipData);
                logger.info("[CrowdinSyncEngine] Shared build extracted: " + files.size() + " files");
                return files;
            } catch (IOException e) {
                throw new RuntimeException("Failed to extract shared build ZIP", e);
            }
        }).exceptionally(ex -> {
            logger.warning("[CrowdinSyncEngine] Shared build failed: " + ex.getMessage());
            // Clear cache on error so next attempt will retry
            synchronized (this) {
                if (sharedBuildCache != null && sharedBuildCache.isCompletedExceptionally()) {
                    sharedBuildCache = null;
                    sharedBuildTimestamp = null;
                }
            }
            throw new RuntimeException(ex);
        });

        return sharedBuildCache;
    }

    /**
     * Gets stored MD5 hashes for change detection.
     *
     * <p>Returns an empty map so that all translations are always considered "changed".
     * Since updateFile replaces the entire file on Crowdin, we always send the full
     * content anyway — the change detection only controls WHETHER to upload, and
     * with empty hashes we always upload (ensuring Crowdin stays in sync).</p>
     */
    @NotNull
    private CompletableFuture<Map<String, String>> getStoredHashes(@NotNull String namespace) {
        // Always return empty so all translations are considered changed,
        // triggering an upload. The full file is sent regardless.
        return CompletableFuture.completedFuture(new HashMap<>());
    }

    /**
     * Updates stored MD5 hashes after successful upload.
     */
    @NotNull
    private CompletableFuture<Void> updateStoredHashes(
            @NotNull String namespace,
            @NotNull List<Translation> translations,
            @NotNull List<String> uploadedKeys
    ) {
        // Build a set of uploaded keys for fast lookup
        Set<String> uploadedSet = new HashSet<>(uploadedKeys);

        // Update hash for each uploaded translation
        List<CompletableFuture<Void>> updateFutures = new ArrayList<>();
        for (Translation t : translations) {
            if (uploadedSet.contains(t.fullKey())) {
                String hash = UploadStrategy.calculateMd5(t.text());
                // Store hash persistence is intentionally skipped here.
                // Upload strategy currently always uploads full content.
                if (debug) {
                    logger.fine("[CrowdinSyncEngine] Would update hash for " + t.fullKey() + ": " + hash);
                }
            }
        }

        return CompletableFuture.allOf(updateFutures.toArray(new CompletableFuture[0]));
    }

    /**
     * Checks if a sync is currently in progress.
     *
     * @return true if sync is running
     */
    public boolean isSyncInProgress() {
        return !syncsInProgress.isEmpty();
    }

    /**
     * Checks if a specific namespace is currently being synced.
     *
     * @param namespace Namespace to check
     * @return true if sync is in progress for this namespace
     */
    public boolean isSyncInProgress(@NotNull String namespace) {
        return syncsInProgress.contains(namespace);
    }

    /**
     * Gets the last sync result for a namespace.
     *
     * @param namespace Namespace to check
     * @return Last sync result or null
     */
    @Nullable
    public SyncResult getLastSyncResult(@NotNull String namespace) {
        return lastSyncResults.get(namespace);
    }

    /**
     * Gets all last sync results.
     *
     * @return Map of namespace to last result
     */
    @NotNull
    public Map<String, SyncResult> getAllLastSyncResults() {
        return Collections.unmodifiableMap(lastSyncResults);
    }

    /**
     * Generates a unique sync ID.
     */
    @NotNull
    private String generateSyncId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Tests the Crowdin connection.
     *
     * @return CompletableFuture with true if successful
     */
    @NotNull
    public CompletableFuture<Boolean> testConnection() {
        return client.testConnection();
    }

    /**
     * Result of a cleanup operation.
     *
     * @param orphanedFiles List of orphaned files found
     * @param orphanedDirectories List of orphaned directories found
     * @param filesDeleted Number of files deleted
     * @param directoriesDeleted Number of directories deleted
     * @param errors List of errors during cleanup
     */
    public record CleanupResult(
            @NotNull List<OrphanedResource> orphanedFiles,
            @NotNull List<OrphanedResource> orphanedDirectories,
            int filesDeleted,
            int directoriesDeleted,
            @NotNull List<String> errors
    ) {
        public boolean hasOrphans() {
            return !orphanedFiles.isEmpty() || !orphanedDirectories.isEmpty();
        }

        public int totalOrphans() {
            return orphanedFiles.size() + orphanedDirectories.size();
        }
    }

    /**
     * Represents an orphaned resource on Crowdin.
     *
     * @param id Resource ID
     * @param name Resource name
     * @param path Full path on Crowdin
     * @param type Resource type (file or directory)
     */
    public record OrphanedResource(
            long id,
            @NotNull String name,
            @NotNull String path,
            @NotNull String type
    ) {
        @Override
        public String toString() {
            return type + ": " + path + " (id: " + id + ")";
        }
    }

    /**
     * Finds orphaned files and directories on Crowdin.
     *
     * <p>An orphaned resource is one that exists on Crowdin but no longer has
     * a corresponding namespace registered locally.</p>
     *
     * <p><b>Safety Rules:</b></p>
     * <ul>
     *     <li>Only scans within the server's own directory (server-id)</li>
     *     <li>NEVER touches namespace-directories overrides (shared namespaces)</li>
     *     <li>NEVER touches root-level resources (no server-id isolation)</li>
     *     <li>Requires server-id to be configured</li>
     * </ul>
     *
     * @return CompletableFuture with cleanup result (preview only, no deletion)
     */
    @NotNull
    public CompletableFuture<CleanupResult> findOrphanedResources() {
        // Ensure newly discovered namespaces on disk are not treated as orphaned.
        namespaceManager.discoverAndRegisterNewNamespaces();

        // Safety check: server-id must be configured
        if (!config.isServerScoped()) {
            return CompletableFuture.completedFuture(
                    new CleanupResult(
                            List.of(),
                            List.of(),
                            0,
                            0,
                            List.of("Cleanup requires server-id to be configured in config.yml for safety. " +
                                    "Cannot cleanup without directory isolation.")
                    )
            );
        }

        String serverId = config.getServerId();
        logger.info("[CrowdinSyncEngine] Finding orphaned resources in server directory: /" + serverId);

        // Get all registered namespaces
        Set<String> registeredNamespaces = namespaceManager.getRegisteredNamespaces();

        // Build a set of namespaces that should exist under /server-id/
        // A namespace belongs to /server-id/ if its resolved directory path starts with [server-id, ...]
        Set<String> serverOwnedNamespaces = new HashSet<>();
        for (String ns : registeredNamespaces) {
            List<String> dirPath = config.getDirectoryPathForNamespace(ns);
            // Check if first segment is the server-id
            if (!dirPath.isEmpty() && dirPath.get(0).equals(serverId)) {
                serverOwnedNamespaces.add(ns);
            }
        }

        logger.info("[CrowdinSyncEngine] Server owns " + serverOwnedNamespaces.size() +
                   " namespaces under /" + serverId + "/ (total registered: " + registeredNamespaces.size() + ")");

        // List all files and directories on Crowdin using the same futures
        CompletableFuture<List<com.google.gson.JsonObject>> filesFuture = client.listFiles();
        CompletableFuture<List<com.google.gson.JsonObject>> directoriesFuture = client.listDirectories();
        return filesFuture.thenCombine(directoriesFuture, (allFiles, allDirectories) -> {
            List<OrphanedResource> orphanedFiles = new ArrayList<>();
            List<OrphanedResource> orphanedDirs = new ArrayList<>();

            // Find orphaned files
            for (com.google.gson.JsonObject file : allFiles) {
                String path = file.get("path").getAsString();
                long id = file.get("id").getAsLong();
                String name = file.get("name").getAsString();

                // Only consider files within our server directory
                if (!path.startsWith("/" + serverId + "/")) {
                    continue;
                }

                // Extract namespace from path: /server-id/namespace/file.yml
                String[] parts = path.substring(1).split("/");
                if (parts.length < 2) {
                    continue;
                }

                String namespace = parts[1];

                // Check if namespace is still registered locally
                if (!serverOwnedNamespaces.contains(namespace)) {
                    orphanedFiles.add(new OrphanedResource(id, name, path, "file"));
                }
            }

            // Build a map of directoryId -> directory name for path resolution
            Map<Long, String> dirIdToName = new HashMap<>();
            Map<Long, Long> dirIdToParentId = new HashMap<>();
            for (com.google.gson.JsonObject dir : allDirectories) {
                long id = dir.get("id").getAsLong();
                String name = dir.get("name").getAsString();
                Long parentId = dir.has("directoryId") && !dir.get("directoryId").isJsonNull()
                        ? dir.get("directoryId").getAsLong()
                        : null;

                dirIdToName.put(id, name);
                if (parentId != null) {
                    dirIdToParentId.put(id, parentId);
                }
            }

            // Find the server directory ID by name
            Long serverDirId = null;
            for (com.google.gson.JsonObject dir : allDirectories) {
                long id = dir.get("id").getAsLong();
                String name = dir.get("name").getAsString();
                Long parentId = dir.has("directoryId") && !dir.get("directoryId").isJsonNull()
                        ? dir.get("directoryId").getAsLong()
                        : null;

                // Server directory is at root level (parentId == null) with name == serverId
                if (parentId == null && name.equals(serverId)) {
                    serverDirId = id;
                    break;
                }
            }

            // Find orphaned directories (only immediate children of server directory)
            if (serverDirId != null) {
                for (com.google.gson.JsonObject dir : allDirectories) {
                    long id = dir.get("id").getAsLong();
                    String name = dir.get("name").getAsString();
                    Long parentId = dir.has("directoryId") && !dir.get("directoryId").isJsonNull()
                            ? dir.get("directoryId").getAsLong()
                            : null;

                    // Only consider directories that are direct children of server directory
                    if (parentId != null && parentId.equals(serverDirId)) {
                        // This is a direct child of /server-id/
                        if (!serverOwnedNamespaces.contains(name)) {
                            String path = "/" + serverId + "/" + name;
                            orphanedDirs.add(new OrphanedResource(id, name, path, "directory"));
                        }
                    }
                }
            }

            logger.info("[CrowdinSyncEngine] Found " + orphanedFiles.size() + " orphaned files, " +
                       orphanedDirs.size() + " orphaned directories");

            return new CleanupResult(orphanedFiles, orphanedDirs, 0, 0, List.of());
        }).exceptionally(ex -> {
            logger.warning("[CrowdinSyncEngine] Failed to find orphaned resources: " + ex.getMessage());
            return new CleanupResult(
                    List.of(),
                    List.of(),
                    0,
                    0,
                    List.of("Failed to list Crowdin resources: " + ex.getMessage())
            );
        });
    }

    /**
     * Cleans up orphaned resources on Crowdin by deleting them.
     *
     * <p>This method deletes files and directories that were identified as orphaned
     * by {@link #findOrphanedResources()}.</p>
     *
     * @param orphanedFiles List of orphaned files to delete
     * @param orphanedDirectories List of orphaned directories to delete
     * @return CompletableFuture with cleanup result
     */
    @NotNull
    public CompletableFuture<CleanupResult> cleanupOrphanedResources(
            @NotNull List<OrphanedResource> orphanedFiles,
            @NotNull List<OrphanedResource> orphanedDirectories
    ) {
        logger.info("[CrowdinSyncEngine] Cleaning up " + orphanedFiles.size() + " files and " +
                   orphanedDirectories.size() + " directories");

        List<String> errors = new ArrayList<>();
        int filesDeleted = 0;
        int dirsDeleted = 0;

        // Delete files first
        List<CompletableFuture<Void>> fileDeletions = new ArrayList<>();
        for (OrphanedResource file : orphanedFiles) {
            fileDeletions.add(
                    client.deleteFile(file.id())
                            .thenRun(() -> logger.info("[CrowdinSyncEngine] Deleted file: " + file.path()))
                            .exceptionally(ex -> {
                                String error = "Failed to delete file " + file.path() + ": " + ex.getMessage();
                                logger.warning("[CrowdinSyncEngine] " + error);
                                errors.add(error);
                                return null;
                            })
            );
        }

        return CompletableFuture.allOf(fileDeletions.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    long deleted = fileDeletions.stream()
                            .filter(f -> !f.isCompletedExceptionally())
                            .count();
                    return (int) deleted;
                })
                .thenCompose(deletedFiles -> {
                    // Delete directories after files
                    List<CompletableFuture<Void>> dirDeletions = new ArrayList<>();
                    for (OrphanedResource dir : orphanedDirectories) {
                        dirDeletions.add(
                                client.deleteDirectory(dir.id())
                                        .thenRun(() -> logger.info("[CrowdinSyncEngine] Deleted directory: " + dir.path()))
                                        .exceptionally(ex -> {
                                            String error = "Failed to delete directory " + dir.path() + ": " + ex.getMessage();
                                            logger.warning("[CrowdinSyncEngine] " + error);
                                            errors.add(error);
                                            return null;
                                        })
                        );
                    }

                    return CompletableFuture.allOf(dirDeletions.toArray(new CompletableFuture[0]))
                            .thenApply(v2 -> {
                                long deleted = dirDeletions.stream()
                                        .filter(f -> !f.isCompletedExceptionally())
                                        .count();
                                return new int[]{deletedFiles, (int) deleted};
                            });
                })
                .thenApply(counts -> {
                    logger.info("[CrowdinSyncEngine] Cleanup complete: " + counts[0] + " files, " +
                               counts[1] + " directories deleted");
                    return new CleanupResult(
                            orphanedFiles,
                            orphanedDirectories,
                            counts[0],
                            counts[1],
                            errors
                    );
                })
                .exceptionally(ex -> {
                    logger.severe("[CrowdinSyncEngine] Cleanup failed: " + ex.getMessage());
                    errors.add("Cleanup operation failed: " + ex.getMessage());
                    return new CleanupResult(
                            orphanedFiles,
                            orphanedDirectories,
                            0,
                            0,
                            errors
                    );
                });
    }
}
