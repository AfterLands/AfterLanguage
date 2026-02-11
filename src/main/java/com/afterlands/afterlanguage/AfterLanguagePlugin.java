package com.afterlands.afterlanguage;

import com.afterlands.afterlanguage.bootstrap.PluginLifecycle;
import com.afterlands.afterlanguage.bootstrap.PluginRegistry;
import com.afterlands.core.util.PluginBanner;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Main plugin class for AfterLanguage.
 *
 * <p>Enterprise-grade i18n plugin for the AfterLands ecosystem.
 * Provides per-player language support with hot-reload, Crowdin integration,
 * and config scanning capabilities.</p>
 *
 * <h3>Architecture:</h3>
 * <ul>
 *     <li>api/ - Public interfaces and models</li>
 *     <li>core/ - Business logic (pure, no framework dependencies)</li>
 *     <li>infra/ - External adapters (DB, Crowdin, PAPI, etc.)</li>
 *     <li>bootstrap/ - DI container, lifecycle management, commands</li>
 * </ul>
 *
 * <h3>Provider Pattern:</h3>
 * <p>AfterLanguage registers as MessageService provider in AfterCore.
 * Other plugins depend only on AfterCore's MessageService API and automatically
 * gain i18n capabilities when AfterLanguage is installed.</p>
 *
 * @author AfterLands Team
 * @version 1.0.0
 */
public final class AfterLanguagePlugin extends JavaPlugin {

    private PluginRegistry registry;
    private PluginLifecycle lifecycle;

    @Override
    public void onLoad() {
        // Reserved for pre-initialization tasks
        getLogger().info("AfterLanguage loading...");
    }

    @Override
    public void onEnable() {
        PluginBanner.printBanner(this);
        long startTime = System.currentTimeMillis();

        try {
            // Initialize DI container
            this.registry = new PluginRegistry(this);

            // Initialize and execute lifecycle
            this.lifecycle = new PluginLifecycle(this, registry);
            lifecycle.startup();

            PluginBanner.printLoadTime(this, startTime);

        } catch (Exception e) {
            getLogger().severe("Failed to enable AfterLanguage: " + e.getMessage());
            e.printStackTrace();
            setEnabled(false);
        }
    }

    @Override
    public void onDisable() {
        if (lifecycle != null) {
            try {
                lifecycle.shutdown();
            } catch (Exception e) {
                getLogger().severe("Error during shutdown: " + e.getMessage());
                e.printStackTrace();
            }
        }

        getLogger().info("AfterLanguage disabled.");
    }

    /**
     * Gets the plugin registry (DI container).
     *
     * @return PluginRegistry instance
     */
    @NotNull
    public PluginRegistry getRegistry() {
        if (registry == null) {
            throw new IllegalStateException("Plugin not initialized");
        }
        return registry;
    }

    /**
     * Gets the lifecycle manager.
     *
     * @return PluginLifecycle instance
     */
    @NotNull
    public PluginLifecycle getLifecycle() {
        if (lifecycle == null) {
            throw new IllegalStateException("Plugin not initialized");
        }
        return lifecycle;
    }
}
