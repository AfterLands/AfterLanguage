package com.afterlands.afterlanguage.infra.service;

import com.afterlands.afterlanguage.core.resolver.MessageResolver;
import com.afterlands.afterlanguage.infra.persistence.PlayerLanguageRepository;
import com.afterlands.core.api.messages.MessageKey;
import com.afterlands.core.api.messages.Placeholder;
import com.afterlands.core.config.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Implementation of AfterCore MessageService using AfterLanguage engine.
 *
 * <p>Provides i18n capabilities via MessageResolver and player language persistence.</p>
 *
 * <h3>Architecture:</h3>
 * <ul>
 *     <li>Delegates i18n methods to MessageResolver</li>
 *     <li>Falls back to literal key for missing translations</li>
 *     <li>Legacy methods return formatted error message</li>
 * </ul>
 */
public class MessageServiceImpl implements MessageService {

    private final MessageResolver messageResolver;
    private final PlayerLanguageRepository languageRepo;
    private final String defaultLanguage;
    private final Logger logger;
    private final boolean debug;

    /**
     * Creates message service implementation.
     *
     * @param messageResolver Translation resolver
     * @param languageRepo Player language repository
     * @param defaultLanguage Default language code
     * @param logger Logger
     * @param debug Enable debug logging
     */
    public MessageServiceImpl(
            @NotNull MessageResolver messageResolver,
            @NotNull PlayerLanguageRepository languageRepo,
            @NotNull String defaultLanguage,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
        this.languageRepo = Objects.requireNonNull(languageRepo, "languageRepo");
        this.defaultLanguage = Objects.requireNonNull(defaultLanguage, "defaultLanguage");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.debug = debug;
    }

    // ══════════════════════════════════════════════
    // I18N METHODS (Primary Implementation)
    // ══════════════════════════════════════════════

    @Override
    public void send(@NotNull Player player, @NotNull MessageKey key, @NotNull Placeholder... placeholders) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");

        String language = getPlayerLanguage(player.getUniqueId());
        String message = messageResolver.resolve(language, key.namespace(), key.path(), placeholders);

        if (!message.isEmpty()) {
            player.sendMessage(format(message));
        }
    }

    @Override
    public void send(@NotNull Player player, @NotNull MessageKey key, int count, @NotNull Placeholder... placeholders) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");

        String language = getPlayerLanguage(player.getUniqueId());

        // Add count as placeholder
        Placeholder[] allPlaceholders = new Placeholder[placeholders.length + 1];
        allPlaceholders[0] = Placeholder.of("count", String.valueOf(count));
        System.arraycopy(placeholders, 0, allPlaceholders, 1, placeholders.length);

        // Use plural key variant based on count
        String path = key.path() + (count == 1 ? ".one" : ".other");
        String message = messageResolver.resolve(language, key.namespace(), path, allPlaceholders);

        if (!message.isEmpty()) {
            player.sendMessage(format(message));
        }
    }

    @Override
    @NotNull
    public String get(@NotNull Player player, @NotNull MessageKey key, @NotNull Placeholder... placeholders) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");

        String language = getPlayerLanguage(player.getUniqueId());
        return messageResolver.resolve(language, key.namespace(), key.path(), placeholders);
    }

    @Override
    @NotNull
    public String getOrDefault(@NotNull Player player, @NotNull MessageKey key, @NotNull String defaultValue, @NotNull Placeholder... placeholders) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(defaultValue, "defaultValue");

        String language = getPlayerLanguage(player.getUniqueId());
        String result = messageResolver.resolve(language, key.namespace(), key.path(), placeholders);

        // If result is missing format, return default
        if (result.startsWith("&c[Missing:")) {
            return defaultValue;
        }

        return result;
    }

    @Override
    public void broadcast(@NotNull MessageKey key, @NotNull Placeholder... placeholders) {
        Objects.requireNonNull(key, "key");

        // Send to each player in their language
        for (Player player : Bukkit.getOnlinePlayers()) {
            send(player, key, placeholders);
        }
    }

    @Override
    public void broadcast(@NotNull MessageKey key, @NotNull String permission, @NotNull Placeholder... placeholders) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(permission, "permission");

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(permission)) {
                send(player, key, placeholders);
            }
        }
    }

    @Override
    public void sendBatch(@NotNull Player player, @NotNull List<MessageKey> keys, @NotNull Map<String, Object> sharedPlaceholders) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(sharedPlaceholders, "sharedPlaceholders");

        // Convert map to Placeholder array
        Placeholder[] placeholders = sharedPlaceholders.entrySet().stream()
                .map(e -> Placeholder.of(e.getKey(), e.getValue()))
                .toArray(Placeholder[]::new);

        // Send each message
        for (MessageKey key : keys) {
            send(player, key, placeholders);
        }
    }

    // ══════════════════════════════════════════════
    // PLAYER LANGUAGE MANAGEMENT
    // ══════════════════════════════════════════════

    @Override
    @NotNull
    public String getPlayerLanguage(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");

        // Check cache first (synchronous)
        return languageRepo.getCachedLanguage(playerId)
                .orElse(defaultLanguage);
    }

    @Override
    public void setPlayerLanguage(@NotNull UUID playerId, @NotNull String language) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(language, "language");

        // Async save (fire and forget)
        languageRepo.setLanguage(playerId, language, false)
                .exceptionally(ex -> {
                    logger.warning("[MessageService] Failed to save language for " + playerId + ": " + ex.getMessage());
                    return null;
                });
    }

    @Override
    @NotNull
    public List<String> getAvailableLanguages() {
        // TODO: Return all available languages from registry
        // For MVP, return default
        return List.of(defaultLanguage);
    }

    @Override
    @NotNull
    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    // ══════════════════════════════════════════════
    // LEGACY METHODS (Simple Fallback)
    // ══════════════════════════════════════════════

    @Override
    @Deprecated
    public void send(@NotNull CommandSender sender, @NotNull String path) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(path, "path");

        // For MVP, just send the path as-is
        sender.sendMessage(format("&c[Legacy path-based messages not supported: " + path + "]"));
    }

    @Override
    @Deprecated
    public void send(@NotNull CommandSender sender, @NotNull String path, @NotNull String... replacements) {
        send(sender, path);
    }

    @Override
    @Deprecated
    public void sendRaw(@NotNull CommandSender sender, @NotNull String raw) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(raw, "raw");

        sender.sendMessage(format(raw));
    }

    @Override
    @Deprecated
    @NotNull
    public String get(@NotNull String path) {
        return "&c[Legacy path-based messages not supported: " + path + "]";
    }

    @Override
    @Deprecated
    @NotNull
    public String get(@NotNull String path, @NotNull String... replacements) {
        return get(path);
    }

    @Override
    @Deprecated
    @NotNull
    public List<String> getList(@NotNull String path) {
        return List.of(get(path));
    }

    @Override
    @NotNull
    public String format(@NotNull String raw) {
        Objects.requireNonNull(raw, "raw");
        return raw.replace("&", "§");
    }
}
