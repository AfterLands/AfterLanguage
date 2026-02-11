package com.afterlands.afterlanguage.infra.event;

import com.afterlands.afterlanguage.api.model.Translation;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Event fired when an existing dynamic translation is updated (v1.2.0).
 *
 * <p>This event is called after the translation has been successfully
 * updated in the database but before the TranslationRegistry is refreshed.</p>
 *
 * <h3>Use Cases:</h3>
 * <ul>
 *     <li>Tracking translation changes for audit/history</li>
 *     <li>Triggering cache invalidation for updated keys</li>
 *     <li>Notifying translators about changes</li>
 *     <li>Syncing updates to external translation services</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * @EventHandler
 * public void onTranslationUpdated(TranslationUpdatedEvent event) {
 *     Translation old = event.getOldTranslation();
 *     Translation updated = event.getNewTranslation();
 *     
 *     if (old != null) {
 *         logger.info("Translation updated: " + updated.fullKey() +
 *                     " from '" + old.text() + "' to '" + updated.text() + "'");
 *     }
 * }
 * }</pre>
 *
 * @since 1.2.0
 */
public class TranslationUpdatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Translation oldTranslation;
    private final Translation newTranslation;
    private final String source;

    /**
     * Creates a new TranslationUpdatedEvent.
     *
     * @param oldTranslation Previous translation (null if not available)
     * @param newTranslation Updated translation
     * @param source Source of update (e.g., "api", "gui", "command")
     */
    public TranslationUpdatedEvent(
            @Nullable Translation oldTranslation,
            @NotNull Translation newTranslation,
            @NotNull String source
    ) {
        this.oldTranslation = oldTranslation;
        this.newTranslation = Objects.requireNonNull(newTranslation, "newTranslation cannot be null");
        this.source = Objects.requireNonNull(source, "source cannot be null");
    }

    /**
     * Gets the previous translation.
     *
     * @return Old translation, or null if not available
     */
    @Nullable
    public Translation getOldTranslation() {
        return oldTranslation;
    }

    /**
     * Gets the updated translation.
     *
     * @return New translation
     */
    @NotNull
    public Translation getNewTranslation() {
        return newTranslation;
    }

    /**
     * Gets the source of update.
     *
     * @return Source identifier (e.g., "api", "gui", "command")
     */
    @NotNull
    public String getSource() {
        return source;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public String toString() {
        return "TranslationUpdatedEvent{" +
               "newTranslation=" + newTranslation.fullKey() +
               ", language=" + newTranslation.language() +
               ", hasOldValue=" + (oldTranslation != null) +
               ", source='" + source + '\'' +
               '}';
    }
}
