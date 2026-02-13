package com.afterlands.afterlanguage.infra.persistence;

import com.afterlands.core.database.SqlDataSource;
import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.HashMap;
import java.util.Map;

/**
 * Repository for player language preferences.
 *
 * <p>
 * Manages persistence of player language settings with in-memory cache.
 * </p>
 *
 * <h3>Database Schema:</h3>
 * 
 * <pre>
 * afterlanguage_players (
 *     uuid VARCHAR(36) PRIMARY KEY,
 *     language VARCHAR(10) NOT NULL,
 *     auto_detected BOOLEAN NOT NULL DEFAULT FALSE,
 *     first_join TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     INDEX idx_language (language)
 * )
 * </pre>
 *
 * <h3>Cache Strategy:</h3>
 * <ul>
 * <li>In-memory cache for fast lookups (no DB query per get())</li>
 * <li>Write-through cache (update DB async, cache sync)</li>
 * <li>Cleared on reload</li>
 * </ul>
 */
public class PlayerLanguageRepository {

    private final SqlDataSource dataSource;
    private final Logger logger;
    private final String tableName;
    private final boolean debug;

    // In-memory cache: UUID -> language data
    private final ConcurrentHashMap<UUID, PlayerLanguageData> cache = new ConcurrentHashMap<>();

    /**
     * Creates a player language repository.
     *
     * @param dataSource SQL datasource
     * @param tableName  Table name (with prefix)
     * @param logger     Logger
     * @param debug      Enable debug logging
     */
    public PlayerLanguageRepository(
            @NotNull SqlDataSource dataSource,
            @NotNull String tableName,
            @NotNull Logger logger,
            boolean debug) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Gets player's language preference.
     *
     * <p>
     * Checks cache first, then queries database if not cached.
     * </p>
     *
     * @param playerId Player UUID
     * @return CompletableFuture with Optional language data
     */
    @NotNull
    public CompletableFuture<Optional<PlayerLanguageData>> get(@NotNull UUID playerId) {
        // Check cache first
        PlayerLanguageData cached = cache.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }

        // Query database
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT uuid, language, auto_detected, first_join, updated_at " +
                    "FROM " + tableName + " WHERE uuid = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        PlayerLanguageData data = new PlayerLanguageData(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("language"),
                                rs.getBoolean("auto_detected"),
                                rs.getTimestamp("first_join").toInstant(),
                                rs.getTimestamp("updated_at").toInstant());

                        // Cache result
                        cache.put(playerId, data);

                        return Optional.of(data);
                    }

                    return Optional.empty();
                }
            }
        });
    }

    /**
     * Sets player's language preference.
     *
     * <p>
     * Updates cache immediately and database asynchronously.
     * </p>
     *
     * @param playerId     Player UUID
     * @param language     Language code
     * @param autoDetected Whether language was auto-detected
     * @return CompletableFuture that completes when database is updated
     */
    @NotNull
    public CompletableFuture<Void> set(
            @NotNull UUID playerId,
            @NotNull String language,
            boolean autoDetected) {
        // Update cache immediately
        Instant now = Instant.now();
        PlayerLanguageData data = new PlayerLanguageData(
                playerId,
                language,
                autoDetected,
                now,
                now);
        cache.put(playerId, data);

        // Update database async
        return dataSource.runAsync(conn -> {
            String sql = "INSERT INTO " + tableName +
                    " (uuid, language, auto_detected, first_join, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE language = VALUES(language), " +
                    "auto_detected = VALUES(auto_detected), updated_at = VALUES(updated_at)";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                ps.setString(2, language);
                ps.setBoolean(3, autoDetected);
                ps.setTimestamp(4, Timestamp.from(data.firstJoin()));
                ps.setTimestamp(5, Timestamp.from(data.updatedAt()));

                ps.executeUpdate();
            }
        });
    }

    /**
     * Gets all players using a specific language.
     *
     * @param language Language code
     * @return CompletableFuture with list of player UUIDs
     */
    @NotNull
    public CompletableFuture<List<UUID>> getPlayersByLanguage(@NotNull String language) {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT uuid FROM " + tableName + " WHERE language = ?";
            List<UUID> players = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, language);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        players.add(UUID.fromString(rs.getString("uuid")));
                    }
                }
            }

            return players;
        });
    }

    /**
     * Gets count of players per language.
     *
     * @return CompletableFuture with map of language -> count
     */
    @NotNull
    public CompletableFuture<Map<String, Integer>> getLanguageStats() {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT language, COUNT(*) as count FROM " + tableName + " GROUP BY language";
            Map<String, Integer> stats = new HashMap<>();

            try (PreparedStatement ps = conn.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    stats.put(rs.getString("language"), rs.getInt("count"));
                }
            }

            return stats;
        });
    }

    /**
     * Removes player from cache and database.
     *
     * @param playerId Player UUID
     * @return CompletableFuture with true if removed
     */
    @NotNull
    public CompletableFuture<Boolean> remove(@NotNull UUID playerId) {
        cache.remove(playerId);

        return dataSource.supplyAsync(conn -> {
            String sql = "DELETE FROM " + tableName + " WHERE uuid = ?";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                int affected = ps.executeUpdate();
                return affected > 0;
            }
        });
    }

    /**
     * Clears in-memory cache.
     *
     * <p>
     * Used during reload to force re-loading from database.
     * </p>
     */
    public void clearCache() {
        cache.clear();
        if (debug) {
            logger.info("[PlayerLanguageRepository] Cache cleared");
        }
    }

    /**
     * Gets cache size.
     *
     * @return Number of cached entries
     */
    public int getCacheSize() {
        return cache.size();
    }

    /**
     * Gets number of cached players.
     *
     * @return Number of players in cache
     */
    public int getCachedPlayerCount() {
        return cache.size();
    }

    /**
     * Gets cached language for a player (synchronous).
     *
     * @param playerId Player UUID
     * @return Optional with language code
     */
    @NotNull
    public Optional<String> getCachedLanguage(@NotNull UUID playerId) {
        PlayerLanguageData data = cache.get(playerId);
        if (data != null) {
            return Optional.of(data.language());
        }
        return Optional.empty();
    }

    /**
     * Sets player language (wrapper for set method).
     *
     * @param playerId     Player UUID
     * @param language     Language code
     * @param autoDetected Whether language was auto-detected
     * @return CompletableFuture that completes when saved
     */
    @NotNull
    public CompletableFuture<Void> setLanguage(
            @NotNull UUID playerId,
            @NotNull String language,
            boolean autoDetected) {
        return set(playerId, language, autoDetected);
    }

    /**
     * Saves all cached player data to database.
     *
     * @return CompletableFuture that completes when all saves are done
     */
    @NotNull
    public CompletableFuture<Void> saveAll() {
        if (cache.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Save each cached entry
        for (UUID playerId : new ArrayList<>(cache.keySet())) {
            PlayerLanguageData data = cache.get(playerId);
            if (data != null) {
                CompletableFuture<Void> future = set(playerId, data.language(), data.autoDetected());
                futures.add(future);
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Player language data record.
     */
    public record PlayerLanguageData(
            @NotNull UUID playerId,
            @NotNull String language,
            boolean autoDetected,
            @NotNull Instant firstJoin,
            @NotNull Instant updatedAt) {
    }
}
