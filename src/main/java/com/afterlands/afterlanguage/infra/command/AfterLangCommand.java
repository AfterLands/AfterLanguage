package com.afterlands.afterlanguage.infra.command;

import com.afterlands.afterlanguage.bootstrap.PluginRegistry;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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
public class AfterLangCommand implements CommandExecutor, TabCompleter {

    private final PluginRegistry registry;

    public AfterLangCommand(@NotNull PluginRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("afterlanguage.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            // Default: show stats
            return handleStats(sender);
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            case "reload" -> handleReload(sender, args);
            case "stats" -> handleStats(sender);
            case "cache" -> handleCacheStats(sender);
            default -> {
                sender.sendMessage("§cUnknown subcommand. Usage: /afterlang <reload|stats|cache>");
                yield true;
            }
        };
    }

    /**
     * Handles /afterlang reload [namespace]
     */
    private boolean handleReload(@NotNull CommandSender sender, @NotNull String[] args) {
        sender.sendMessage("§7Reloading translations...");

        long startTime = System.currentTimeMillis();

        try {
            if (args.length > 1) {
                // Reload specific namespace
                String namespace = args[1];

                if (!registry.getNamespaceManager().isRegistered(namespace)) {
                    sender.sendMessage("§cNamespace not found: " + namespace);
                    return true;
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

        return true;
    }

    /**
     * Handles /afterlang stats
     */
    private boolean handleStats(@NotNull CommandSender sender) {
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

        return true;
    }

    /**
     * Handles /afterlang cache
     */
    private boolean handleCacheStats(@NotNull CommandSender sender) {
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
        sender.sendMessage("  §7Avg Load Time: §e" + formatNanos(l1Stats.averageLoadPenalty()));

        sender.sendMessage("");

        // L3 Cache (Template Cache)
        CacheStats l3Stats = registry.getCache().getTemplateStats();
        sender.sendMessage("§eL3 Cache (Templates):");
        sender.sendMessage("  §7Requests: §e" + l3Stats.requestCount());
        sender.sendMessage("  §7Hits: §e" + l3Stats.hitCount() + " §7(" + formatPercent(l3Stats.hitRate()) + ")");
        sender.sendMessage("  §7Misses: §e" + l3Stats.missCount() + " §7(" + formatPercent(l3Stats.missRate()) + ")");
        sender.sendMessage("  §7Evictions: §e" + l3Stats.evictionCount());
        sender.sendMessage("  §7Avg Load Time: §e" + formatNanos(l3Stats.averageLoadPenalty()));

        sender.sendMessage("");

        // Cache sizes
        sender.sendMessage("§7L1 Size: §e" + registry.getCache().getHotSize());
        sender.sendMessage("§7L3 Size: §e" + registry.getCache().getTemplateSize());

        sender.sendMessage("");

        // Actions
        sender.sendMessage("§7Use §e/afterlang reload §7to clear caches");

        sender.sendMessage("§7§m                                    ");

        return true;
    }

    /**
     * Formats percentage (0.0 - 1.0 -> "XX.X%").
     */
    @NotNull
    private String formatPercent(double rate) {
        return String.format("%.1f%%", rate * 100);
    }

    /**
     * Formats nanoseconds to readable time.
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

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("afterlanguage.admin")) {
            return List.of();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Subcommands
            completions.add("reload");
            completions.add("stats");
            completions.add("cache");

            String input = args[0].toLowerCase();
            completions.removeIf(s -> !s.startsWith(input));

        } else if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            // Namespace names
            String input = args[1].toLowerCase();
            for (String namespace : registry.getNamespaceManager().getRegisteredNamespaces()) {
                if (namespace.startsWith(input)) {
                    completions.add(namespace);
                }
            }
        }

        return completions;
    }
}
