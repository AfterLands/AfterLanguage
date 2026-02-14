package com.afterlands.afterlanguage.core.service;

import com.afterlands.afterlanguage.api.model.Translation;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Core port for dynamic translation persistence.
 *
 * <p>Implemented by infra adapters (e.g., JDBC repositories).</p>
 */
public interface DynamicTranslationStore {

    @NotNull CompletableFuture<List<Translation>> getNamespace(@NotNull String namespace);

    @NotNull CompletableFuture<Optional<Translation>> get(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    );

    @NotNull CompletableFuture<List<Translation>> getAllByLanguage(
            @NotNull String namespace,
            @NotNull String language
    );

    @NotNull CompletableFuture<Void> save(@NotNull Translation translation);

    @NotNull CompletableFuture<Boolean> delete(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    );

    @NotNull CompletableFuture<Integer> deleteNamespace(@NotNull String namespace);

    @NotNull CompletableFuture<Integer> count(@NotNull String namespace);

    @NotNull CompletableFuture<Boolean> exists(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language
    );
}
