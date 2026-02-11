package com.afterlands.afterlanguage.api.crowdin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Result of a Crowdin sync operation.
 *
 * <p>Immutable record containing details about upload/download operations.</p>
 *
 * @param syncId Unique identifier for this sync operation
 * @param operation Type of sync operation (UPLOAD, DOWNLOAD, FULL_SYNC)
 * @param namespace Namespace that was synced
 * @param status Result status (SUCCESS, PARTIAL, FAILED)
 * @param stringsUploaded Number of strings uploaded to Crowdin
 * @param stringsDownloaded Number of translations downloaded from Crowdin
 * @param stringsSkipped Number of strings skipped (no changes)
 * @param conflicts Number of conflicts detected
 * @param errors List of error messages (if any)
 * @param startedAt When the sync started
 * @param completedAt When the sync completed
 * @author AfterLands Team
 * @since 1.3.0
 */
public record SyncResult(
        @NotNull String syncId,
        @NotNull SyncOperation operation,
        @NotNull String namespace,
        @NotNull SyncStatus status,
        int stringsUploaded,
        int stringsDownloaded,
        int stringsSkipped,
        int conflicts,
        @NotNull List<String> errors,
        @NotNull Instant startedAt,
        @Nullable Instant completedAt
) {

    /**
     * Type of sync operation.
     */
    public enum SyncOperation {
        /** Upload local changes to Crowdin */
        UPLOAD,
        /** Download translations from Crowdin */
        DOWNLOAD,
        /** Bidirectional sync (upload + download) */
        FULL_SYNC,
        /** Webhook-triggered download */
        WEBHOOK
    }

    /**
     * Status of the sync operation.
     */
    public enum SyncStatus {
        /** Sync completed successfully */
        SUCCESS,
        /** Sync completed with some errors */
        PARTIAL,
        /** Sync failed completely */
        FAILED,
        /** Sync is in progress */
        RUNNING
    }

    /**
     * Creates a new SyncResult for a running operation.
     *
     * @param syncId Unique identifier
     * @param operation Type of operation
     * @param namespace Target namespace
     * @return SyncResult in RUNNING state
     */
    @NotNull
    public static SyncResult running(
            @NotNull String syncId,
            @NotNull SyncOperation operation,
            @NotNull String namespace
    ) {
        return new SyncResult(
                syncId,
                operation,
                namespace,
                SyncStatus.RUNNING,
                0,
                0,
                0,
                0,
                List.of(),
                Instant.now(),
                null
        );
    }

    /**
     * Creates a successful upload result.
     *
     * @param syncId Unique identifier
     * @param namespace Target namespace
     * @param uploaded Number of strings uploaded
     * @param skipped Number of strings skipped
     * @param startedAt When the operation started
     * @return SyncResult with SUCCESS status
     */
    @NotNull
    public static SyncResult uploadSuccess(
            @NotNull String syncId,
            @NotNull String namespace,
            int uploaded,
            int skipped,
            @NotNull Instant startedAt
    ) {
        return new SyncResult(
                syncId,
                SyncOperation.UPLOAD,
                namespace,
                SyncStatus.SUCCESS,
                uploaded,
                0,
                skipped,
                0,
                List.of(),
                startedAt,
                Instant.now()
        );
    }

    /**
     * Creates a successful download result.
     *
     * @param syncId Unique identifier
     * @param namespace Target namespace
     * @param downloaded Number of translations downloaded
     * @param skipped Number of translations skipped
     * @param conflicts Number of conflicts resolved
     * @param startedAt When the operation started
     * @return SyncResult with SUCCESS status
     */
    @NotNull
    public static SyncResult downloadSuccess(
            @NotNull String syncId,
            @NotNull String namespace,
            int downloaded,
            int skipped,
            int conflicts,
            @NotNull Instant startedAt
    ) {
        return new SyncResult(
                syncId,
                SyncOperation.DOWNLOAD,
                namespace,
                SyncStatus.SUCCESS,
                0,
                downloaded,
                skipped,
                conflicts,
                List.of(),
                startedAt,
                Instant.now()
        );
    }

    /**
     * Creates a failed result.
     *
     * @param syncId Unique identifier
     * @param operation Type of operation
     * @param namespace Target namespace
     * @param errors List of error messages
     * @param startedAt When the operation started
     * @return SyncResult with FAILED status
     */
    @NotNull
    public static SyncResult failed(
            @NotNull String syncId,
            @NotNull SyncOperation operation,
            @NotNull String namespace,
            @NotNull List<String> errors,
            @NotNull Instant startedAt
    ) {
        return new SyncResult(
                syncId,
                operation,
                namespace,
                SyncStatus.FAILED,
                0,
                0,
                0,
                0,
                errors,
                startedAt,
                Instant.now()
        );
    }

    /**
     * Gets the duration of the sync operation.
     *
     * @return Duration, or null if still running
     */
    @Nullable
    public Duration getDuration() {
        if (completedAt == null) {
            return null;
        }
        return Duration.between(startedAt, completedAt);
    }

    /**
     * Gets the duration in milliseconds.
     *
     * @return Duration in ms, or -1 if still running
     */
    public long getDurationMillis() {
        Duration duration = getDuration();
        return duration != null ? duration.toMillis() : -1;
    }

    /**
     * Checks if the sync was successful.
     *
     * @return true if status is SUCCESS
     */
    public boolean isSuccess() {
        return status == SyncStatus.SUCCESS;
    }

    /**
     * Checks if the sync failed.
     *
     * @return true if status is FAILED
     */
    public boolean isFailed() {
        return status == SyncStatus.FAILED;
    }

    /**
     * Checks if the sync is still running.
     *
     * @return true if status is RUNNING
     */
    public boolean isRunning() {
        return status == SyncStatus.RUNNING;
    }

    /**
     * Gets total strings processed.
     *
     * @return Sum of uploaded, downloaded, and skipped
     */
    public int getTotalProcessed() {
        return stringsUploaded + stringsDownloaded + stringsSkipped;
    }

    /**
     * Returns a copy with updated upload count.
     *
     * @param uploaded New upload count
     * @return New SyncResult instance
     */
    @NotNull
    public SyncResult withUploaded(int uploaded) {
        return new SyncResult(
                syncId, operation, namespace, status,
                uploaded, stringsDownloaded, stringsSkipped, conflicts,
                errors, startedAt, completedAt
        );
    }

    /**
     * Returns a copy with updated download count.
     *
     * @param downloaded New download count
     * @return New SyncResult instance
     */
    @NotNull
    public SyncResult withDownloaded(int downloaded) {
        return new SyncResult(
                syncId, operation, namespace, status,
                stringsUploaded, downloaded, stringsSkipped, conflicts,
                errors, startedAt, completedAt
        );
    }

    /**
     * Returns a copy marked as completed with SUCCESS status.
     *
     * @return New SyncResult instance
     */
    @NotNull
    public SyncResult complete() {
        return new SyncResult(
                syncId, operation, namespace, SyncStatus.SUCCESS,
                stringsUploaded, stringsDownloaded, stringsSkipped, conflicts,
                errors, startedAt, Instant.now()
        );
    }

    /**
     * Returns a copy marked as failed.
     *
     * @param errorMessages Error messages
     * @return New SyncResult instance
     */
    @NotNull
    public SyncResult fail(@NotNull List<String> errorMessages) {
        return new SyncResult(
                syncId, operation, namespace, SyncStatus.FAILED,
                stringsUploaded, stringsDownloaded, stringsSkipped, conflicts,
                errorMessages, startedAt, Instant.now()
        );
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SyncResult[")
          .append("id=").append(syncId)
          .append(", op=").append(operation)
          .append(", ns=").append(namespace)
          .append(", status=").append(status);

        if (stringsUploaded > 0) sb.append(", uploaded=").append(stringsUploaded);
        if (stringsDownloaded > 0) sb.append(", downloaded=").append(stringsDownloaded);
        if (stringsSkipped > 0) sb.append(", skipped=").append(stringsSkipped);
        if (conflicts > 0) sb.append(", conflicts=").append(conflicts);
        if (!errors.isEmpty()) sb.append(", errors=").append(errors.size());

        Duration duration = getDuration();
        if (duration != null) {
            sb.append(", duration=").append(duration.toMillis()).append("ms");
        }

        sb.append("]");
        return sb.toString();
    }
}
