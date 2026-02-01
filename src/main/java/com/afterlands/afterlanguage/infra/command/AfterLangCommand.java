package com.afterlands.afterlanguage.infra.command;

import com.afterlands.afterlanguage.bootstrap.PluginRegistry;
import com.afterlands.core.commands.annotations.Command;
import com.afterlands.core.commands.annotations.SubCommand;
import com.afterlands.core.commands.annotations.Arg;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Administration command for AfterLanguage.
 *
 * <h3>Usage:</h3>
 * <ul>
 *     <li>/afterlang reload [namespace] - Reload translations</li>
 *     <li>/afterlang stats - Show plugin statistics</li>
 *     <li>/afterlang cache - Show cache statistics</li>
 * </ul>
 */
@Command(name = "afterlang", permission = "afterlanguage.admin", aliases = {"alang"})
public class AfterLangCommand {

    private final PluginRegistry registry;

    public AfterLangCommand(@NotNull PluginRegistry registry) {
        this.registry = registry;
    }

    /**
     * Reloads translations.
     *
     * @param sender Command sender
     * @param namespace Optional namespace to reload (null = all)
     */
    @SubCommand(name = "reload")
    public void reload(
            @NotNull CommandSender sender,
            @Arg(name = "namespace", optional = true) String namespace
    ) {
        sender.sendMessage("§7Reloading translations...");

        long startTime = System.currentTimeMillis();

        try {
            if (namespace != null) {
                // Reload specific namespace
                if (!registry.getNamespaceManager().isRegistered(namespace)) {
                    sender.sendMessage("§cNamespace not found: " + namespace);
                    return;
                }

                registry.getNamespaceManager().reloadNamespace(namespace).join();
                sender.sendMessage("§aReloaded namespace: " + namespace);

            } else {
                // Reload all namespaces
                registry.getNamespaceManager().reloadAll().join();
                sender.sendMessage("§aReloaded all namespaces!");
            }

            long elapsed = System.currentTimeMillis() - startTime;
            sender.sendMessage("§7Reload completed in " + elapsed + "ms");

            registry.getLogger().info("[AfterLangCommand] Translations reloaded by " +
                    sender.getName() + " in " + elapsed + "ms");

        } catch (Exception e) {
            sender.sendMessage("§cFailed to reload translations: " + e.getMessage());
            registry.getLogger().warning("[AfterLangCommand] Reload failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Shows plugin statistics.
     */
    @SubCommand(name = "stats")
    public void stats(@NotNull CommandSender sender) {
        sender.sendMessage("§7§m                                    ");
        sender.sendMessage("§6§lAfterLanguage Statistics");
        sender.sendMessage("");

        // Registered namespaces
        var namespaces = registry.getNamespaceManager().getRegisteredNamespaces();
        sender.sendMessage("§7Registered Namespaces: §e" + namespaces.size());

        for (String namespace : namespaces) {
            Map<String, Object> nsStats = registry.getNamespaceManager()
                    .getNamespaceStats(namespace);
            int translationCount = (int) nsStats.getOrDefault("translation_count", 0);
            sender.sendMessage("  §7- §e" + namespace + " §7(" + translationCount + " translations)");
        }

        sender.sendMessage("");

        // Registry stats
        int totalTranslations = registry.getRegistry().size();
        sender.sendMessage("§7Total Translations: §e" + totalTranslations);

        sender.sendMessage("");

        // Player stats
        int cachedPlayers = registry.getPlayerLanguageRepo().getCachedPlayerCount();
        sender.sendMessage("§7Cached Players: §e" + cachedPlayers);

        // Cache hit rates
        CacheStats hotStats = registry.getCache().getHotStats();
        sender.sendMessage("§7L1 Cache Hit Rate: §e" + formatPercent(hotStats.hitRate()));

        sender.sendMessage("§7§m                                    ");
    }

    /**
     * Shows cache statistics.
     */
    @SubCommand(name = "cache")
    public void cacheStats(@NotNull CommandSender sender) {
        sender.sendMessage("§7§m                                    ");
        sender.sendMessage("§6§lCache Statistics");
        sender.sendMessage("");

        // L1 Cache (Hot Cache)
        CacheStats l1Stats = registry.getCache().getHotStats();
        sender.sendMessage("§eL1 Cache (Hot):");
        sender.sendMessage("  §7Requests: §e" + l1Stats.requestCount());
        sender.sendMessage("  §7Hits: §e" + l1Stats.hitCount() + " §7(" + formatPercent(l1Stats.hitRate()) + ")");
        sender.sendMessage("  §7Misses: §e" + l1Stats.missCount() + " §7(" + formatPercent(l1Stats.missRate()) + ")");
        sender.sendMessage("  §7Evictions: §e" + l1Stats.evictionCount());
        sender.sendMessage("  §7Load Time: §e" + formatNanos(l1Stats.averageLoadPenalty()));

        sender.sendMessage("");

        // L3 Cache (Template Cache)
        CacheStats l3Stats = registry.getCache().getTemplateStats();
        sender.sendMessage("§eL3 Cache (Templates):");
        sender.sendMessage("  §7Requests: §e" + l3Stats.requestCount());
        sender.sendMessage("  §7Hits: §e" + l3Stats.hitCount() + " §7(" + formatPercent(l3Stats.hitRate()) + ")");
        sender.sendMessage("  §7Misses: §e" + l3Stats.missCount() + " §7(" + formatPercent(l3Stats.missRate()) + ")");
        sender.sendMessage("  §7Evictions: §e" + l3Stats.evictionCount());
        sender.sendMessage("  §7Load Time: §e" + formatNanos(l3Stats.averageLoadPenalty()));

        sender.sendMessage("");

        // Cache sizes
        sender.sendMessage("§7L1 Size: §e" + registry.getCache().getHotSize());
        sender.sendMessage("§7L3 Size: §e" + registry.getCache().getTemplateSize());

        sender.sendMessage("");

        // Actions
        sender.sendMessage("§7Use §e/afterlang reload §7to clear caches");

        sender.sendMessage("§7§m                                    ");
    }

    /**
     * Formats percentage (0.0 - 1.0 -> "XX.X%").
     */
    @NotNull
    private String formatPercent(double rate) {
        return String.format("%.1f%%", rate * 100);
    }

    /**
     * Formats nanoseconds to milliseconds.
     */
    @NotNull
    private String formatNanos(double nanos) {
        if (nanos < 1000) {
            return String.format("%.0fns", nanos);
        } else if (nanos < 1_000_000) {
            return String.format("%.1fμs", nanos / 1000);
        } else {
            return String.format("%.2fms", nanos / 1_000_000);
        }
    }
}
