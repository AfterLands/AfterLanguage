package com.afterlands.afterlanguage.infra.service;

import com.afterlands.core.api.AfterCore;
import com.afterlands.core.api.AfterCoreAPI;
import com.afterlands.core.inventory.InventoryService;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Invalidates AfterCore inventory caches when player language changes.
 *
 * <p>Uses AfterCore player-scoped API to avoid global cache invalidation.</p>
 */
public final class InventoryCacheInvalidator {

    private InventoryCacheInvalidator() {
        // Utility class
    }

    public static void onLanguageChanged(
            @NotNull UUID playerId,
            @NotNull String oldLanguage,
            @NotNull String newLanguage,
            @NotNull Logger logger,
            boolean debug) {
        String normalizedOld = oldLanguage.toLowerCase(Locale.ROOT);
        String normalizedNew = newLanguage.toLowerCase(Locale.ROOT);
        if (normalizedOld.equals(normalizedNew)) {
            return;
        }

        AfterCoreAPI afterCore = AfterCore.get();
        if (afterCore == null) {
            if (debug) {
                logger.fine("[InventoryCacheInvalidator] AfterCore unavailable, skipping inventory cache invalidation");
            }
            return;
        }

        InventoryService inventoryService = afterCore.inventory();
        if (inventoryService == null) {
            if (debug) {
                logger.fine("[InventoryCacheInvalidator] InventoryService unavailable, skipping cache invalidation");
            }
            return;
        }

        try {
            inventoryService.clearPlayerCache(playerId);
            if (debug) {
                logger.fine("[InventoryCacheInvalidator] Cleared inventory cache for player: " + playerId);
            }
        } catch (Exception ex) {
            logger.warning("[InventoryCacheInvalidator] Failed to invalidate inventory cache after language change: " +
                    ex.getMessage());
        }
    }
}
