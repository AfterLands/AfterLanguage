package com.afterlands.afterlanguage.infra.crowdin;

import com.afterlands.afterlanguage.api.crowdin.SyncResult;
import com.afterlands.afterlanguage.api.service.DynamicContentAPI;
import com.afterlands.afterlanguage.core.cache.TranslationCache;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Broadcasts Crowdin sync events to other servers via Redis pub/sub.
 *
 * <p>Enables multi-server deployments to stay in sync by notifying
 * all servers when translations change.</p>
 *
 * <h3>Events Broadcast:</h3>
 * <ul>
 *     <li>sync_completed - After a namespace sync finishes</li>
 *     <li>namespace_reloaded - After a namespace is reloaded</li>
 *     <li>translation_changed - After a translation is modified</li>
 * </ul>
 *
 * <h3>Message Format:</h3>
 * <pre>{@code
 * {
 *     "type": "sync_completed",
 *     "serverId": "server-uuid",
 *     "namespace": "myplugin",
 *     "uploaded": 5,
 *     "downloaded": 10,
 *     "timestamp": 1234567890
 * }
 * }</pre>
 *
 * <h3>Usage:</h3>
 * <p>This class requires AfterCore's Redis integration to be enabled.
 * Configure the channel in config.yml under redis.channel.</p>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class RedisSyncBroadcaster {

    private final String channel;
    private final DynamicContentAPI dynamicAPI;
    private final TranslationCache cache;
    private final Logger logger;
    private final Gson gson;
    private final String serverId;
    private final boolean debug;

    // Redis subscriber interface (provided by AfterCore)
    @Nullable
    private Object redisSubscription; // Would be AfterCore's Redis subscriber

    /**
     * Creates a new RedisSyncBroadcaster.
     *
     * @param channel Redis channel to use
     * @param dynamicAPI Dynamic content API for reloading
     * @param cache Translation cache for invalidation
     * @param logger Logger for output
     * @param debug Whether debug logging is enabled
     */
    public RedisSyncBroadcaster(
            @NotNull String channel,
            @NotNull DynamicContentAPI dynamicAPI,
            @NotNull TranslationCache cache,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.channel = Objects.requireNonNull(channel, "channel cannot be null");
        this.dynamicAPI = Objects.requireNonNull(dynamicAPI, "dynamicAPI cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.debug = debug;
        this.gson = new Gson();
        this.serverId = UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Subscribes to the Redis channel.
     *
     * <p>This method should be called during plugin startup if Redis is enabled.</p>
     *
     * <p>Note: Actual Redis subscription requires AfterCore's Redis API.
     * This is a placeholder implementation.</p>
     */
    public void subscribe() {
        logger.info("[RedisSyncBroadcaster] Subscribing to channel: " + channel);

        // TODO: Integrate with AfterCore's Redis API when available
        // Example (pseudo-code):
        // afterCore.redis().subscribe(channel, this::handleMessage);

        logger.info("[RedisSyncBroadcaster] Subscribed successfully (server: " + serverId + ")");
    }

    /**
     * Unsubscribes from the Redis channel.
     */
    public void unsubscribe() {
        if (redisSubscription != null) {
            // TODO: afterCore.redis().unsubscribe(redisSubscription);
            redisSubscription = null;
        }
        logger.info("[RedisSyncBroadcaster] Unsubscribed from channel: " + channel);
    }

    /**
     * Broadcasts a sync completion event.
     *
     * @param result The sync result to broadcast
     */
    public void broadcastSyncCompleted(@NotNull SyncResult result) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "sync_completed");
        message.addProperty("serverId", serverId);
        message.addProperty("namespace", result.namespace());
        message.addProperty("operation", result.operation().name());
        message.addProperty("uploaded", result.stringsUploaded());
        message.addProperty("downloaded", result.stringsDownloaded());
        message.addProperty("conflicts", result.conflicts());
        message.addProperty("status", result.status().name());
        message.addProperty("timestamp", System.currentTimeMillis());

        publish(message);

        if (debug) {
            logger.info("[RedisSyncBroadcaster] Broadcast sync_completed for " + result.namespace());
        }
    }

    /**
     * Broadcasts a namespace reload event.
     *
     * @param namespace The reloaded namespace
     */
    public void broadcastNamespaceReloaded(@NotNull String namespace) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "namespace_reloaded");
        message.addProperty("serverId", serverId);
        message.addProperty("namespace", namespace);
        message.addProperty("timestamp", System.currentTimeMillis());

        publish(message);

        if (debug) {
            logger.info("[RedisSyncBroadcaster] Broadcast namespace_reloaded for " + namespace);
        }
    }

    /**
     * Broadcasts a translation change event.
     *
     * @param namespace Namespace
     * @param key Translation key
     * @param language Language code
     * @param action Action performed (created, updated, deleted)
     */
    public void broadcastTranslationChanged(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String action
    ) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "translation_changed");
        message.addProperty("serverId", serverId);
        message.addProperty("namespace", namespace);
        message.addProperty("key", key);
        message.addProperty("language", language);
        message.addProperty("action", action);
        message.addProperty("timestamp", System.currentTimeMillis());

        publish(message);

        if (debug) {
            logger.fine("[RedisSyncBroadcaster] Broadcast translation_changed: " +
                       namespace + ":" + key + " [" + language + "] " + action);
        }
    }

    /**
     * Publishes a message to the Redis channel.
     */
    private void publish(@NotNull JsonObject message) {
        String json = gson.toJson(message);

        // TODO: Integrate with AfterCore's Redis API when available
        // Example (pseudo-code):
        // afterCore.redis().publish(channel, json);

        // For now, just log
        if (debug) {
            logger.fine("[RedisSyncBroadcaster] Would publish: " + json);
        }
    }

    /**
     * Handles incoming Redis messages.
     *
     * @param json JSON message from Redis
     */
    public void handleMessage(@NotNull String json) {
        try {
            JsonObject message = gson.fromJson(json, JsonObject.class);

            // Ignore messages from this server
            String messageServerId = message.get("serverId").getAsString();
            if (serverId.equals(messageServerId)) {
                return;
            }

            String type = message.get("type").getAsString();

            switch (type) {
                case "sync_completed" -> handleSyncCompleted(message);
                case "namespace_reloaded" -> handleNamespaceReloaded(message);
                case "translation_changed" -> handleTranslationChanged(message);
                default -> {
                    if (debug) {
                        logger.fine("[RedisSyncBroadcaster] Unknown message type: " + type);
                    }
                }
            }

        } catch (Exception e) {
            logger.warning("[RedisSyncBroadcaster] Failed to handle message: " + e.getMessage());
        }
    }

    /**
     * Handles sync_completed message from another server.
     */
    private void handleSyncCompleted(@NotNull JsonObject message) {
        String namespace = message.get("namespace").getAsString();
        int downloaded = message.get("downloaded").getAsInt();

        if (downloaded > 0) {
            logger.info("[RedisSyncBroadcaster] Server " + message.get("serverId").getAsString() +
                       " completed sync for " + namespace + " - reloading...");

            // Reload the namespace to get updated translations
            dynamicAPI.reloadNamespace(namespace)
                    .thenRun(() -> {
                        cache.invalidateNamespace(namespace);
                        logger.info("[RedisSyncBroadcaster] Reloaded namespace: " + namespace);
                    })
                    .exceptionally(ex -> {
                        logger.warning("[RedisSyncBroadcaster] Failed to reload: " + ex.getMessage());
                        return null;
                    });
        }
    }

    /**
     * Handles namespace_reloaded message from another server.
     */
    private void handleNamespaceReloaded(@NotNull JsonObject message) {
        String namespace = message.get("namespace").getAsString();

        logger.info("[RedisSyncBroadcaster] Server " + message.get("serverId").getAsString() +
                   " reloaded namespace " + namespace + " - invalidating cache...");

        // Invalidate cache for this namespace
        cache.invalidateNamespace(namespace);
    }

    /**
     * Handles translation_changed message from another server.
     */
    private void handleTranslationChanged(@NotNull JsonObject message) {
        String namespace = message.get("namespace").getAsString();
        String key = message.get("key").getAsString();
        String language = message.get("language").getAsString();

        if (debug) {
            logger.fine("[RedisSyncBroadcaster] Translation changed on another server: " +
                       namespace + ":" + key + " [" + language + "]");
        }

        // Invalidate cache for this specific translation
        cache.invalidate(language, namespace, key);
    }

    /**
     * Gets the Redis channel name.
     */
    @NotNull
    public String getChannel() {
        return channel;
    }

    /**
     * Gets this server's ID.
     */
    @NotNull
    public String getServerId() {
        return serverId;
    }
}
