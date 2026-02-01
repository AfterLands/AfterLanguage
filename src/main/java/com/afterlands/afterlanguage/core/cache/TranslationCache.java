package com.afterlands.afterlanguage.core.cache;

import com.afterlands.afterlanguage.core.template.CompiledMessage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Three-tier caching strategy for translations.
 *
 * <h3>Cache Tiers:</h3>
 * <ul>
 *     <li><b>L1 Hot Cache:</b> Most accessed translations (resolved strings)</li>
 *     <li><b>L2 Registry:</b> All loaded translations (in TranslationRegistry)</li>
 *     <li><b>L3 Template Cache:</b> Compiled message templates</li>
 * </ul>
 *
 * <h3>Performance Targets:</h3>
 * <ul>
 *     <li>L1 hit: &lt; 0.01ms</li>
 *     <li>L1 miss / L2 hit: &lt; 0.1ms</li>
 *     <li>Template compilation: &lt; 0.05ms</li>
 * </ul>
 */
public class TranslationCache {

    /**
     * L1: Hot cache for frequently accessed resolved translations.
     * Key format: "lang:namespace:key" -> resolved text
     */
    private final Cache<String, String> hotCache;

    /**
     * L3: Template cache for pre-compiled messages with placeholders.
     * Key format: "lang:namespace:key" -> CompiledMessage
     */
    private final Cache<String, CompiledMessage> templateCache;

    private final int maxHotCacheSize;
    private final int maxTemplateCacheSize;
    private final boolean debug;

    /**
     * Creates a translation cache with specified sizes and TTLs.
     *
     * @param maxHotCacheSize Maximum L1 cache size
     * @param hotCacheTtlMinutes L1 TTL after access (minutes)
     * @param maxTemplateCacheSize Maximum L3 cache size
     * @param templateCacheTtlMinutes L3 TTL after write (minutes)
     * @param debug Enable debug logging
     */
    public TranslationCache(
            int maxHotCacheSize,
            int hotCacheTtlMinutes,
            int maxTemplateCacheSize,
            int templateCacheTtlMinutes,
            boolean debug
    ) {
        this.maxHotCacheSize = maxHotCacheSize;
        this.maxTemplateCacheSize = maxTemplateCacheSize;
        this.debug = debug;

        // L1: Hot cache with access-based expiration
        this.hotCache = Caffeine.newBuilder()
                .maximumSize(maxHotCacheSize)
                .expireAfterAccess(hotCacheTtlMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build();

        // L3: Template cache with write-based expiration
        this.templateCache = Caffeine.newBuilder()
                .maximumSize(maxTemplateCacheSize)
                .expireAfterWrite(templateCacheTtlMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    // ==================== L1 Hot Cache Operations ====================

    /**
     * Gets resolved translation from L1 hot cache.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     * @return Cached translation or null if not cached
     */
    @Nullable
    public String getHot(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        String cacheKey = buildHotCacheKey(language, namespace, key);
        return hotCache.getIfPresent(cacheKey);
    }

    /**
     * Puts resolved translation into L1 hot cache.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     * @param resolvedText Resolved translation text
     */
    public void putHot(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String resolvedText
    ) {
        String cacheKey = buildHotCacheKey(language, namespace, key);
        hotCache.put(cacheKey, resolvedText);
    }

    /**
     * Invalidates L1 entry for a specific translation.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     */
    public void invalidateHot(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        String cacheKey = buildHotCacheKey(language, namespace, key);
        hotCache.invalidate(cacheKey);
    }

    /**
     * Invalidates all L1 entries for a namespace across all languages.
     *
     * @param namespace Namespace
     */
    public void invalidateHotNamespace(@NotNull String namespace) {
        hotCache.asMap().keySet().removeIf(key -> key.contains(":" + namespace + ":"));
    }

    /**
     * Builds L1 cache key.
     */
    @NotNull
    private String buildHotCacheKey(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        return language + ":" + namespace + ":" + key;
    }

    // ==================== L3 Template Cache Operations ====================

    /**
     * Gets compiled message template from L3 cache.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     * @return Cached compiled message or null if not cached
     */
    @Nullable
    public CompiledMessage getTemplate(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        String cacheKey = buildTemplateCacheKey(language, namespace, key);
        return templateCache.getIfPresent(cacheKey);
    }

    /**
     * Puts compiled message template into L3 cache.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     * @param compiledMessage Compiled message
     */
    public void putTemplate(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key,
            @NotNull CompiledMessage compiledMessage
    ) {
        String cacheKey = buildTemplateCacheKey(language, namespace, key);
        templateCache.put(cacheKey, compiledMessage);
    }

    /**
     * Invalidates L3 entry for a specific translation.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     */
    public void invalidateTemplate(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        String cacheKey = buildTemplateCacheKey(language, namespace, key);
        templateCache.invalidate(cacheKey);
    }

    /**
     * Invalidates all L3 entries for a namespace across all languages.
     *
     * @param namespace Namespace
     */
    public void invalidateTemplateNamespace(@NotNull String namespace) {
        templateCache.asMap().keySet().removeIf(key -> key.contains(":" + namespace + ":"));
    }

    /**
     * Builds L3 cache key.
     */
    @NotNull
    private String buildTemplateCacheKey(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        return language + ":" + namespace + ":" + key;
    }

    // ==================== Atomic Invalidation ====================

    /**
     * Invalidates all cache entries for a namespace atomically.
     *
     * <p>Used during hot-reload to prevent inconsistency windows.</p>
     *
     * @param namespace Namespace to invalidate
     */
    public void invalidateNamespace(@NotNull String namespace) {
        invalidateHotNamespace(namespace);
        invalidateTemplateNamespace(namespace);
    }

    /**
     * Invalidates specific translation across all cache tiers.
     *
     * @param language Language code
     * @param namespace Namespace
     * @param key Key
     */
    public void invalidate(
            @NotNull String language,
            @NotNull String namespace,
            @NotNull String key
    ) {
        invalidateHot(language, namespace, key);
        invalidateTemplate(language, namespace, key);
    }

    /**
     * Clears all caches.
     */
    public void invalidateAll() {
        hotCache.invalidateAll();
        templateCache.invalidateAll();
    }

    // ==================== Statistics ====================

    /**
     * Gets L1 hot cache statistics.
     *
     * @return Cache statistics
     */
    @NotNull
    public CacheStats getHotStats() {
        return hotCache.stats();
    }

    /**
     * Gets L3 template cache statistics.
     *
     * @return Cache statistics
     */
    @NotNull
    public CacheStats getTemplateStats() {
        return templateCache.stats();
    }

    /**
     * Gets L1 hot cache size.
     *
     * @return Number of entries in L1
     */
    public long getHotSize() {
        return hotCache.estimatedSize();
    }

    /**
     * Gets L3 template cache size.
     *
     * @return Number of entries in L3
     */
    public long getTemplateSize() {
        return templateCache.estimatedSize();
    }

    /**
     * Gets formatted statistics string.
     *
     * @return Statistics summary
     */
    @NotNull
    public String formatStats() {
        CacheStats hotStats = getHotStats();
        CacheStats templateStats = getTemplateStats();

        return String.format(
                "L1 Hot: %d/%d entries (%.2f%% hit rate) | " +
                "L3 Template: %d/%d entries (%.2f%% hit rate)",
                getHotSize(), maxHotCacheSize, hotStats.hitRate() * 100,
                getTemplateSize(), maxTemplateCacheSize, templateStats.hitRate() * 100
        );
    }
}
