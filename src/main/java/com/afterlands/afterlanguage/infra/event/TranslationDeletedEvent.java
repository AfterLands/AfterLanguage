package com.afterlands.afterlanguage.infra.event;

import com.afterlands.afterlanguage.api.model.Translation;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Event fired when a dynamic translation is deleted (v1.2.0).
 *
 * <p>This event is called after the translation has been successfully
 * removed from the database but before the TranslationRegistry is updated.</p>
 *
 * <h3>Use Cases:</h3>
 * <ul>
 *     <li>Logging translation deletions for audit trails</li>
 *     <li>Triggering cache invalidation for deleted keys</li>
 *     <li>Notifying administrators about deletions</li>
 *     <li>Syncing deletions to external translation services</li>
 *     <li>Archiving deleted translations before removal</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * @EventHandler
 * public void onTranslationDeleted(TranslationDeletedEvent event) {
 *     Translation deleted = event.getDeletedTranslation();
 *     
 *     if (deleted != null) {
 *         logger.warning("Translation deleted: " + deleted.fullKey() +
 *                       " [" + deleted.language() + "]");
 *     } else {
 *         logger.warning("Translation deleted: " + event.getNamespace() +
 *                       ":" + event.getKey() + " [" + event.getLanguage() + "]");
 *     }
 * }
 * }</pre>
 *
 * @since 1.2.0
 */
public class TranslationDeletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Translation deletedTranslation;
    private final String namespace;
    private final String key;
    private final String language;
    private final String source;

    /**
     * Creates a new TranslationDeletedEvent with the deleted translation.
     *
     * @param deletedTranslation The deleted translation
     * @param source Source of deletion (e.g., "api", "gui", "command")
     */
    public TranslationDeletedEvent(@NotNull Translation deletedTranslation, @NotNull String source) {
        this.deletedTranslation = Objects.requireNonNull(deletedTranslation, "deletedTranslation cannot be null");
        this.namespace = deletedTranslation.namespace();
        this.key = deletedTranslation.key();
        this.language = deletedTranslation.language();
        this.source = Objects.requireNonNull(source, "source cannot be null");
    }

    /**
     * Creates a new TranslationDeletedEvent without the full translation object.
     *
     * <p>Use this constructor when the translation was not loaded before deletion.</p>
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @param source Source of deletion (e.g., "api", "gui", "command")
     */
    public TranslationDeletedEvent(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String source
    ) {
        this.deletedTranslation = null;
        this.namespace = Objects.requireNonNull(namespace, "namespace cannot be null");
        this.key = Objects.requireNonNull(key, "key cannot be null");
        this.language = Objects.requireNonNull(language, "language cannot be null");
        this.source = Objects.requireNonNull(source, "source cannot be null");
    }

    /**
     * Gets the deleted translation if available.
     *
     * @return Deleted translation, or null if not loaded
     */
    @Nullable
    public Translation getDeletedTranslation() {
        return deletedTranslation;
    }

    /**
     * Gets the namespace of the deleted translation.
     *
     * @return Namespace
     */
    @NotNull
    public String getNamespace() {
        return namespace;
    }

    /**
     * Gets the key of the deleted translation.
     *
     * @return Translation key
     */
    @NotNull
    public String getKey() {
        return key;
    }

    /**
     * Gets the language code of the deleted translation.
     *
     * @return Language code
     */
    @NotNull
    public String getLanguage() {
        return language;
    }

    /**
     * Gets the full key (namespace:key) of the deleted translation.
     *
     * @return Full key
     */
    @NotNull
    public String getFullKey() {
        return namespace + ":" + key;
    }

    /**
     * Gets the source of deletion.
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
        return "TranslationDeletedEvent{" +
               "fullKey=" + getFullKey() +
               ", language=" + language +
               ", hasDeletedObject=" + (deletedTranslation != null) +
               ", source='" + source + '\'' +
               '}';
    }
}
