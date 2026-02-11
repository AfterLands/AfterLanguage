package com.afterlands.afterlanguage.infra.crowdin;

import com.afterlands.afterlanguage.api.crowdin.CrowdinAPI;
import com.afterlands.afterlanguage.api.crowdin.SyncResult;
import com.afterlands.afterlanguage.core.crowdin.CrowdinConfig;
import com.afterlands.afterlanguage.core.crowdin.CrowdinSyncEngine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of the public CrowdinAPI interface.
 *
 * <p>Delegates to CrowdinSyncEngine for actual operations.</p>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class CrowdinAPIImpl implements CrowdinAPI {

    private final CrowdinSyncEngine syncEngine;
    private final CrowdinConfig config;
    private final String projectId;

    /**
     * Creates a new CrowdinAPIImpl.
     *
     * @param syncEngine The sync engine to delegate to
     * @param config Crowdin configuration
     * @param projectId Crowdin project ID
     */
    public CrowdinAPIImpl(
            @NotNull CrowdinSyncEngine syncEngine,
            @NotNull CrowdinConfig config,
            @NotNull String projectId
    ) {
        this.syncEngine = Objects.requireNonNull(syncEngine, "syncEngine cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId cannot be null");
    }

    @Override
    @NotNull
    public CompletableFuture<SyncResult> syncNamespace(@NotNull String namespace) {
        return syncEngine.syncNamespace(namespace);
    }

    @Override
    @NotNull
    public CompletableFuture<SyncResult> uploadNamespace(@NotNull String namespace) {
        return syncEngine.uploadNamespace(namespace);
    }

    @Override
    @NotNull
    public CompletableFuture<SyncResult> downloadNamespace(@NotNull String namespace) {
        return syncEngine.downloadNamespace(namespace);
    }

    @Override
    @NotNull
    public CompletableFuture<List<SyncResult>> syncAllNamespaces() {
        return syncEngine.syncAllNamespaces();
    }

    @Override
    public boolean isSyncInProgress() {
        return syncEngine.isSyncInProgress();
    }

    @Override
    @Nullable
    public SyncResult getLastSyncResult(@NotNull String namespace) {
        return syncEngine.getLastSyncResult(namespace);
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    @Nullable
    public String getProjectId() {
        return projectId.isEmpty() ? null : projectId;
    }

    @Override
    @NotNull
    public CompletableFuture<Boolean> testConnection() {
        return syncEngine.testConnection();
    }
}
