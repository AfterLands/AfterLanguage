package com.afterlands.afterlanguage.infra.event;

import com.afterlands.afterlanguage.api.model.Translation;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Event fired when a new dynamic translation is created (v1.2.0).
 *
 * <p>This event is called after the translation has been successfully
 * saved to the database but before it's added to the TranslationRegistry.</p>
 *
 * <h3>Use Cases:</h3>
 * <ul>
 *     <li>Logging translation creation for audit trails</li>
 *     <li>Triggering cache invalidation</li>
 *     <li>Notifying other plugins about new translations</li>
 *     <li>Syncing translations to external services (e.g., Crowdin)</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * @EventHandler
 * public void onTranslationCreated(TranslationCreatedEvent event) {
 *     Translation translation = event.getTranslation();
 *     logger.info("New translation: " + translation.fullKey() +
 *                 " [" + translation.language() + "]");
 * }
 * }</pre>
 *
 * @since 1.2.0
 */
public class TranslationCreatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Translation translation;
    private final String source;

    /**
     * Creates a new TranslationCreatedEvent.
     *
     * @param translation The created translation
     * @param source Source of creation (e.g., "api", "gui", "command")
     */
    public TranslationCreatedEvent(@NotNull Translation translation, @NotNull String source) {
        this.translation = Objects.requireNonNull(translation, "translation cannot be null");
        this.source = Objects.requireNonNull(source, "source cannot be null");
    }

    /**
     * Gets the created translation.
     *
     * @return The translation
     */
    @NotNull
    public Translation getTranslation() {
        return translation;
    }

    /**
     * Gets the source of creation.
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
        return "TranslationCreatedEvent{" +
               "translation=" + translation.fullKey() +
               ", language=" + translation.language() +
               ", source='" + source + '\'' +
               '}';
    }
}
