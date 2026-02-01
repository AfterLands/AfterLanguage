package com.afterlands.afterlanguage.core.resolver;

import com.afterlands.afterlanguage.api.model.Translation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * L2 Cache - In-memory registry of all loaded translations.
 *
 * <p>Thread-safe registry that stores translations in a nested map structure:
 * {@code language -> namespace -> key -> Translation}</p>
 *
 * <p>Uses atomic snapshot swapping for hot-reload without inconsistency windows.</p>
 *
 * <h3>Performance:</h3>
 * <ul>
 *     <li>get() lookup: O(1) - direct map access</li>
 *     <li>Thread-safe via ConcurrentHashMap</li>
 *     <li>Atomic reload via AtomicReference snapshot swap</li>
 * </ul>
 */
public class TranslationRegistry {

    /**
     * Snapshot of the registry.
     * Immutable structure: language -> namespace -> key -> Translation
     */
    private final AtomicReference<Map<String, Map<String, Map<String, Translation>>>> snapshot;

    private final boolean debug;

    /**
     * Creates an empty registry.
     *
     * @param debug Enable debug logging
     */
    public TranslationRegistry(boolean debug) {
        this.snapshot = new AtomicReference<>(new ConcurrentHashMap<>());
        this.debug = debug;
    }

    /**
     * Gets a translation by language, namespace, and key.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     * @return Optional translation
     */
    @NotNull
    public Optional<Translation> get(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        Map<String, Map<String, Map<String, Translation>>> current = snapshot.get();

        Map<String, Map<String, Translation>> langMap = current.get(language);
        if (langMap == null) {
            return Optional.empty();
        }

        Map<String, Translation> nsMap = langMap.get(namespace);
        if (nsMap == null) {
            return Optional.empty();
        }

        Translation translation = nsMap.get(key);
        return Optional.ofNullable(translation);
    }

    /**
     * Puts a translation into the registry.
     *
     * @param translation Translation to add
     */
    public void put(@NotNull Translation translation) {
        snapshot.updateAndGet(current -> {
            // Create mutable copy
            Map<String, Map<String, Map<String, Translation>>> newSnapshot = new ConcurrentHashMap<>(current);

            // Get or create language map
            Map<String, Map<String, Translation>> langMap = newSnapshot.computeIfAbsent(
                    translation.language(),
                    k -> new ConcurrentHashMap<>()
            );

            // Get or create namespace map
            Map<String, Translation> nsMap = langMap.computeIfAbsent(
                    translation.namespace(),
                    k -> new ConcurrentHashMap<>()
            );

            // Put translation
            nsMap.put(translation.key(), translation);

            return newSnapshot;
        });
    }

    /**
     * Puts multiple translations atomically.
     *
     * @param translations Translations to add
     */
    public void putAll(@NotNull List<Translation> translations) {
        if (translations.isEmpty()) {
            return;
        }

        snapshot.updateAndGet(current -> {
            Map<String, Map<String, Map<String, Translation>>> newSnapshot = new ConcurrentHashMap<>(current);

            for (Translation translation : translations) {
                Map<String, Map<String, Translation>> langMap = newSnapshot.computeIfAbsent(
                        translation.language(),
                        k -> new ConcurrentHashMap<>()
                );

                Map<String, Translation> nsMap = langMap.computeIfAbsent(
                        translation.namespace(),
                        k -> new ConcurrentHashMap<>()
                );

                nsMap.put(translation.key(), translation);
            }

            return newSnapshot;
        });
    }

    /**
     * Removes a translation from the registry.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     * @return true if translation was removed
     */
    public boolean remove(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        AtomicReference<Boolean> removed = new AtomicReference<>(false);

        snapshot.updateAndGet(current -> {
            Map<String, Map<String, Map<String, Translation>>> newSnapshot = new ConcurrentHashMap<>(current);

            Map<String, Map<String, Translation>> langMap = newSnapshot.get(language);
            if (langMap != null) {
                Map<String, Translation> nsMap = langMap.get(namespace);
                if (nsMap != null) {
                    Translation old = nsMap.remove(key);
                    removed.set(old != null);

                    // Cleanup empty maps
                    if (nsMap.isEmpty()) {
                        langMap.remove(namespace);
                    }
                    if (langMap.isEmpty()) {
                        newSnapshot.remove(language);
                    }
                }
            }

            return newSnapshot;
        });

        return removed.get();
    }

    /**
     * Gets all translations for a namespace across all languages.
     *
     * @param namespace Namespace
     * @return List of translations
     */
    @NotNull
    public List<Translation> getNamespace(@NotNull String namespace) {
        Map<String, Map<String, Map<String, Translation>>> current = snapshot.get();

        return current.values().stream()
                .map(langMap -> langMap.get(namespace))
                .filter(nsMap -> nsMap != null)
                .flatMap(nsMap -> nsMap.values().stream())
                .collect(Collectors.toList());
    }

    /**
     * Gets all translations for a language.
     *
     * @param language Language code
     * @return List of translations
     */
    @NotNull
    public List<Translation> getLanguage(@NotNull String language) {
        Map<String, Map<String, Map<String, Translation>>> current = snapshot.get();

        Map<String, Map<String, Translation>> langMap = current.get(language);
        if (langMap == null) {
            return List.of();
        }

        return langMap.values().stream()
                .flatMap(nsMap -> nsMap.values().stream())
                .collect(Collectors.toList());
    }

    /**
     * Reloads a namespace by replacing all its translations atomically.
     *
     * <p>This is used for hot-reload: load new translations from disk/DB,
     * then swap them in atomically.</p>
     *
     * @param namespace Namespace to reload
     * @param newTranslations New translations for the namespace
     */
    public void reloadNamespace(@NotNull String namespace, @NotNull List<Translation> newTranslations) {
        snapshot.updateAndGet(current -> {
            Map<String, Map<String, Map<String, Translation>>> newSnapshot = new ConcurrentHashMap<>(current);

            // Remove old translations for this namespace
            for (Map<String, Map<String, Translation>> langMap : newSnapshot.values()) {
                langMap.remove(namespace);
            }

            // Add new translations
            for (Translation translation : newTranslations) {
                if (!translation.namespace().equals(namespace)) {
                    continue; // Skip translations from other namespaces
                }

                Map<String, Map<String, Translation>> langMap = newSnapshot.computeIfAbsent(
                        translation.language(),
                        k -> new ConcurrentHashMap<>()
                );

                Map<String, Translation> nsMap = langMap.computeIfAbsent(
                        translation.namespace(),
                        k -> new ConcurrentHashMap<>()
                );

                nsMap.put(translation.key(), translation);
            }

            return newSnapshot;
        });
    }

    /**
     * Clears all translations from the registry.
     */
    public void clear() {
        snapshot.set(new ConcurrentHashMap<>());
    }

    /**
     * Gets total number of translations loaded.
     *
     * @return Total translation count
     */
    public int size() {
        Map<String, Map<String, Map<String, Translation>>> current = snapshot.get();

        return current.values().stream()
                .mapToInt(langMap -> langMap.values().stream()
                        .mapToInt(Map::size)
                        .sum())
                .sum();
    }

    /**
     * Gets all translations for a namespace in a specific language.
     *
     * @param namespace Namespace identifier
     * @param language Language code
     * @return List of translations
     */
    @NotNull
    public List<Translation> getAllForNamespace(@NotNull String namespace, @NotNull String language) {
        Map<String, Map<String, Map<String, Translation>>> current = snapshot.get();

        Map<String, Map<String, Translation>> langMap = current.get(language);
        if (langMap == null) {
            return List.of();
        }

        Map<String, Translation> nsMap = langMap.get(namespace);
        if (nsMap == null) {
            return List.of();
        }

        return new ArrayList<>(nsMap.values());
    }

    /**
     * Gets number of translations for a language.
     *
     * @param language Language code
     * @return Translation count for language
     */
    public int size(@NotNull String language) {
        Map<String, Map<String, Map<String, Translation>>> current = snapshot.get();

        Map<String, Map<String, Translation>> langMap = current.get(language);
        if (langMap == null) {
            return 0;
        }

        return langMap.values().stream()
                .mapToInt(Map::size)
                .sum();
    }

    /**
     * Gets statistics about loaded translations.
     *
     * @return Statistics string
     */
    @NotNull
    public String getStats() {
        Map<String, Map<String, Map<String, Translation>>> current = snapshot.get();

        int totalLanguages = current.size();
        int totalNamespaces = current.values().stream()
                .mapToInt(Map::size)
                .sum();
        int totalTranslations = size();

        return String.format(
                "Languages: %d, Namespaces: %d, Translations: %d",
                totalLanguages, totalNamespaces, totalTranslations
        );
    }
}
