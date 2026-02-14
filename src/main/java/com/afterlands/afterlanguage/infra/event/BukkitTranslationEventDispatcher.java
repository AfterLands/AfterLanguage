package com.afterlands.afterlanguage.infra.event;

import com.afterlands.afterlanguage.AfterLanguagePlugin;
import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.afterlanguage.core.service.TranslationEventDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Bukkit-backed event dispatcher for translation lifecycle events.
 */
public class BukkitTranslationEventDispatcher implements TranslationEventDispatcher {

    private final AfterLanguagePlugin plugin;

    public BukkitTranslationEventDispatcher(@NotNull AfterLanguagePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void translationCreated(@NotNull Translation translation, @NotNull String source) {
        callEvent(new TranslationCreatedEvent(translation, source));
    }

    @Override
    public void translationUpdated(
            @Nullable Translation oldTranslation,
            @NotNull Translation newTranslation,
            @NotNull String source
    ) {
        callEvent(new TranslationUpdatedEvent(oldTranslation, newTranslation, source));
    }

    @Override
    public void translationDeleted(@NotNull Translation deletedTranslation, @NotNull String source) {
        callEvent(new TranslationDeletedEvent(deletedTranslation, source));
    }

    @Override
    public void translationDeleted(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String source
    ) {
        callEvent(new TranslationDeletedEvent(namespace, key, language, source));
    }

    private void callEvent(@NotNull Event event) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
    }
}
