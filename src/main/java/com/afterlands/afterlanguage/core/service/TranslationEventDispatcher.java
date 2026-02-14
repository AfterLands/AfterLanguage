package com.afterlands.afterlanguage.core.service;

import com.afterlands.afterlanguage.api.model.Translation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Core port for publishing translation lifecycle events.
 */
public interface TranslationEventDispatcher {

    void translationCreated(@NotNull Translation translation, @NotNull String source);

    void translationUpdated(
            @Nullable Translation oldTranslation,
            @NotNull Translation newTranslation,
            @NotNull String source
    );

    void translationDeleted(@NotNull Translation deletedTranslation, @NotNull String source);

    void translationDeleted(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String source
    );
}
