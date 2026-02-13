package com.afterlands.afterlanguage.core.extractor;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Extracts translatable fields from a plugin's inventories.yml
 * and generates gui.yml translation files.
 *
 * <p>Translatable fields: title, name, lore.
 * Ignored fields: material, data, type, actions, view-conditions,
 * skull-owner, duplicate, size, config-version.</p>
 *
 * <p>Key generation uses {@code type} when available, falls back to
 * {@code slot-{slot}}.</p>
 */
public class InventoryExtractor {

    private static final Set<String> IGNORED_SECTIONS = Set.of(
            "config-version"
    );

    private static final Set<String> NON_TRANSLATABLE_ITEM_KEYS = Set.of(
            "material", "data", "type", "actions", "view-conditions",
            "skull-owner", "duplicate", "size", "enabled", "enchanted",
            "hide-flags", "head-type", "head-value", "custom-model-data",
            "nbt", "cacheable", "variants", "on_left_click", "on_right_click",
            "on_shift_left_click", "on_shift_right_click", "amount",
            "item-placeholders"
    );

    private final Path languagesDir;
    private final String sourceLanguage;
    private final List<String> enabledLanguages;
    private final Logger logger;
    private final boolean debug;

    public InventoryExtractor(
            @NotNull Path languagesDir,
            @NotNull String sourceLanguage,
            @NotNull List<String> enabledLanguages,
            @NotNull Logger logger,
            boolean debug) {
        this.languagesDir = languagesDir;
        this.sourceLanguage = sourceLanguage;
        this.enabledLanguages = enabledLanguages;
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Extracts translatable fields from inventories.yml.
     *
     * @param sourceFile The plugin's inventories.yml
     * @param namespace  Namespace identifier
     * @param outputName Output file name without extension (e.g., "gui")
     */
    public void extract(@NotNull File sourceFile, @NotNull String namespace, @NotNull String outputName) {
        if (!sourceFile.exists()) {
            if (debug) {
                logger.fine("[InventoryExtractor] Source file not found: " + sourceFile);
            }
            return;
        }

        YamlConfiguration sourceYaml = YamlConfiguration.loadConfiguration(sourceFile);
        YamlConfiguration outputYaml = new YamlConfiguration();

        // Process each top-level section (each is an inventory or special section)
        for (String topKey : sourceYaml.getKeys(false)) {
            if (IGNORED_SECTIONS.contains(topKey)) {
                continue;
            }

            ConfigurationSection section = sourceYaml.getConfigurationSection(topKey);
            if (section == null) {
                continue;
            }

            if ("default-items".equals(topKey)) {
                extractDefaultItems(section, outputYaml);
            } else if ("variant-items".equals(topKey)) {
                // variant-items at root level (shouldn't be there usually, but handle it)
                continue;
            } else {
                // Regular inventory section
                extractInventory(topKey, section, outputYaml);
            }
        }

        int extractedKeys = outputYaml.getKeys(true).size();

        if (extractedKeys == 0) {
            logger.warning("[InventoryExtractor] No translatable keys extracted from " + sourceFile.getName()
                    + " for namespace '" + namespace + "'. File may contain only {lang:} placeholders or filler items.");
            return;
        }

        // Write to all languages
        for (String lang : enabledLanguages) {
            File targetFile = languagesDir.resolve(lang).resolve(namespace)
                    .resolve(outputName + ".yml").toFile();

            if (lang.equals(sourceLanguage)) {
                writeYaml(outputYaml, targetFile);
            } else {
                if (!targetFile.exists()) {
                    writeYaml(outputYaml, targetFile);
                }
            }
        }

        logger.info("[InventoryExtractor] Extracted " + extractedKeys + " keys for '" + outputName
                + "' namespace '" + namespace + "' from " + sourceFile.getName());
    }

    /**
     * Extracts translatable fields from a single inventory section.
     */
    private void extractInventory(@NotNull String inventoryId,
                                  @NotNull ConfigurationSection section,
                                  @NotNull YamlConfiguration output) {
        // Extract title
        String title = section.getString("title");
        if (title != null && !title.isBlank() && !isLangPlaceholder(title)) {
            output.set(inventoryId + ".title", title);
        }

        // Extract items
        ConfigurationSection itemsSection = section.getConfigurationSection("items");
        if (itemsSection != null) {
            extractItems(inventoryId, itemsSection, output);
        }

        // Extract variant-items (inventory-level)
        ConfigurationSection variantItems = section.getConfigurationSection("variant-items");
        if (variantItems != null) {
            extractVariantItems(inventoryId, variantItems, output);
        }
    }

    /**
     * Extracts translatable fields from inventory items.
     */
    private void extractItems(@NotNull String inventoryId,
                              @NotNull ConfigurationSection itemsSection,
                              @NotNull YamlConfiguration output) {
        for (String slot : itemsSection.getKeys(false)) {
            ConfigurationSection item = itemsSection.getConfigurationSection(slot);
            if (item == null) {
                continue;
            }

            // Skip template references (material: item:filler)
            String material = item.getString("material", "");
            if (material.startsWith("item:")) {
                continue;
            }

            // Determine item key: use type if available, fallback to slot-{slot}
            String type = item.getString("type");
            String itemKey = (type != null && !type.isBlank()) ? type : "slot-" + slot;

            // Skip filler items (name is empty or whitespace)
            String name = item.getString("name");
            if (isFillerName(name)) {
                continue;
            }

            extractItemFields(inventoryId + "." + itemKey, item, output);

            // Extract inline variants (variant0, variant1, ...)
            for (String key : item.getKeys(false)) {
                if (key.startsWith("variant") && !key.equals("variants")) {
                    ConfigurationSection variant = item.getConfigurationSection(key);
                    if (variant != null) {
                        String variantName = variant.getString("name");
                        if (!isFillerName(variantName)) {
                            extractItemFields(inventoryId + ".variants." + key + "." + itemKey, variant, output);
                        }
                    }
                }
            }
        }
    }

    /**
     * Extracts default-items section.
     */
    private void extractDefaultItems(@NotNull ConfigurationSection defaultItems,
                                     @NotNull YamlConfiguration output) {
        for (String itemId : defaultItems.getKeys(false)) {
            ConfigurationSection item = defaultItems.getConfigurationSection(itemId);
            if (item == null) {
                continue;
            }

            String name = item.getString("name");
            if (isFillerName(name)) {
                continue;
            }

            extractItemFields("default-items." + itemId, item, output);
        }
    }

    /**
     * Extracts variant-items section (inventory-level shared variants).
     */
    private void extractVariantItems(@NotNull String inventoryId,
                                     @NotNull ConfigurationSection variantItems,
                                     @NotNull YamlConfiguration output) {
        for (String variantId : variantItems.getKeys(false)) {
            ConfigurationSection variant = variantItems.getConfigurationSection(variantId);
            if (variant == null) {
                continue;
            }

            String name = variant.getString("name");
            if (isFillerName(name)) {
                continue;
            }

            extractItemFields(inventoryId + ".variants." + variantId, variant, output);
        }
    }

    /**
     * Extracts name and lore from an item section.
     */
    private void extractItemFields(@NotNull String basePath,
                                   @NotNull ConfigurationSection item,
                                   @NotNull YamlConfiguration output) {
        String name = item.getString("name");
        if (name != null && !name.isBlank() && !isLangPlaceholder(name)) {
            output.set(basePath + ".name", name);
        }

        List<String> lore = item.getStringList("lore");
        if (lore != null && !lore.isEmpty()) {
            // Only extract if lore has non-placeholder content
            boolean hasRealContent = lore.stream()
                    .anyMatch(line -> !line.isBlank() && !isLangPlaceholder(line));
            if (hasRealContent) {
                output.set(basePath + ".lore", lore);
            }
        }
    }

    /**
     * Checks if a name indicates a filler item (empty, whitespace, or single space).
     */
    private boolean isFillerName(String name) {
        return name == null || name.isBlank() || " ".equals(name);
    }

    /**
     * Checks if a string is already a {lang:...} placeholder (no need to extract).
     */
    private boolean isLangPlaceholder(String text) {
        return text != null && text.contains("{lang:");
    }

    private void writeYaml(@NotNull YamlConfiguration yaml, @NotNull File targetFile) {
        try {
            targetFile.getParentFile().mkdirs();
            yaml.save(targetFile);
        } catch (IOException e) {
            logger.warning("[InventoryExtractor] Failed to write " + targetFile + ": " + e.getMessage());
        }
    }
}
