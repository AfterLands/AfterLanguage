package com.afterlands.afterlanguage.infra.crowdin;

import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.afterlanguage.core.crowdin.CrowdinConfig;
import com.afterlands.afterlanguage.infra.event.TranslationCreatedEvent;
import com.afterlands.afterlanguage.infra.event.TranslationDeletedEvent;
import com.afterlands.afterlanguage.infra.event.TranslationUpdatedEvent;
import com.afterlands.afterlanguage.infra.persistence.DynamicTranslationRepository;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Listens for local translation changes and marks them for Crowdin sync.
 *
 * <p>Tracks changes to pt_br (source language) translations and sets
 * their sync_status to 'pending' so they will be uploaded on next sync.</p>
 *
 * <h3>Events Tracked:</h3>
 * <ul>
 *     <li>{@link TranslationCreatedEvent} - New translation created</li>
 *     <li>{@link TranslationUpdatedEvent} - Translation text modified</li>
 *     <li>{@link TranslationDeletedEvent} - Translation deleted</li>
 * </ul>
 *
 * <h3>Source Language Only:</h3>
 * <p>Only pt_br translations are tracked for upload. Translations in
 * other languages are assumed to come from Crowdin and are not uploaded.</p>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class CrowdinEventListener implements Listener {

    private final DynamicTranslationRepository dynamicRepo;
    private final CrowdinConfig config;
    private final Logger logger;
    private final boolean debug;

    private final String sourceLanguage;

    /**
     * Creates a new CrowdinEventListener.
     *
     * @param dynamicRepo Repository for updating sync status
     * @param config Crowdin configuration
     * @param logger Logger for debug output
     * @param debug Whether debug logging is enabled
     */
    public CrowdinEventListener(
            @NotNull DynamicTranslationRepository dynamicRepo,
            @NotNull CrowdinConfig config,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.dynamicRepo = Objects.requireNonNull(dynamicRepo, "dynamicRepo cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.debug = debug;

        // Normalize source language (pt-BR -> pt_br)
        this.sourceLanguage = config.getSourceLanguage()
                .toLowerCase()
                .replace("-", "_");
    }

    /**
     * Handles new translation creation.
     *
     * <p>Marks source language translations as pending sync.</p>
     *
     * @param event The creation event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTranslationCreated(@NotNull TranslationCreatedEvent event) {
        Translation translation = event.getTranslation();

        // Only track source language (pt_br)
        if (!isSourceLanguage(translation.language())) {
            return;
        }

        // Check if namespace should be synced
        if (!config.shouldSyncNamespace(translation.namespace())) {
            return;
        }

        if (debug) {
            logger.info("[CrowdinEventListener] New translation created: " +
                       translation.fullKey() + " [" + translation.language() + "]");
        }

        markForSync(translation);
    }

    /**
     * Handles translation updates.
     *
     * <p>Marks source language translations as pending sync.</p>
     *
     * @param event The update event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTranslationUpdated(@NotNull TranslationUpdatedEvent event) {
        Translation translation = event.getNewTranslation();

        // Only track source language (pt_br)
        if (!isSourceLanguage(translation.language())) {
            return;
        }

        // Check if namespace should be synced
        if (!config.shouldSyncNamespace(translation.namespace())) {
            return;
        }

        // Only mark if text actually changed
        Translation oldTranslation = event.getOldTranslation();
        if (oldTranslation != null && oldTranslation.text().equals(translation.text())) {
            return;
        }

        if (debug) {
            logger.info("[CrowdinEventListener] Translation updated: " +
                       translation.fullKey() + " [" + translation.language() + "]");
        }

        markForSync(translation);
    }

    /**
     * Handles translation deletion.
     *
     * <p>For source language deletions, we may need to mark for cleanup
     * if cleanup mode is enabled in Crowdin config.</p>
     *
     * @param event The deletion event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTranslationDeleted(@NotNull TranslationDeletedEvent event) {
        Translation translation = event.getDeletedTranslation();

        // Only track source language (pt_br)
        if (translation != null && !isSourceLanguage(translation.language())) {
            return;
        }

        // Check if namespace should be synced
        if (!config.shouldSyncNamespace(translation.namespace())) {
            return;
        }

        if (debug) {
            logger.info("[CrowdinEventListener] Translation deleted: " +
                       translation.fullKey() + " [" + translation.language() + "]");
        }

        // If cleanup mode is enabled, we need to track deletions
        // For now, just log - cleanup is handled during sync
        if (config.isCleanupMode()) {
            logger.info("[CrowdinEventListener] Deletion will be synced to Crowdin: " +
                       translation.fullKey());
        }
    }

    /**
     * Checks if a language code is the source language.
     *
     * @param language Language code to check
     * @return true if it's the source language
     */
    private boolean isSourceLanguage(@NotNull String language) {
        return language.equalsIgnoreCase(sourceLanguage);
    }

    /**
     * Marks a translation as pending sync.
     *
     * <p>Updates the sync_status column in the database.</p>
     *
     * @param translation Translation to mark
     */
    private void markForSync(@NotNull Translation translation) {
        dynamicRepo.updateSyncStatus(
                translation.namespace(),
                translation.key(),
                translation.language(),
                "pending"
        ).exceptionally(ex -> {
            logger.warning("[CrowdinEventListener] Failed to mark for sync: " +
                          translation.fullKey() + " - " + ex.getMessage());
            return null;
        });
    }
}
