package com.afterlands.afterlanguage.api.service;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Result of a config scan operation.
 *
 * <p>Contains new/changed/removed keys detected during scan.</p>
 */
public record ScanResult(
        @NotNull String namespace,
        @NotNull List<String> newKeys,
        @NotNull List<String> changedKeys,
        @NotNull List<String> removedKeys,
        @NotNull Map<String, String> extractedValues
) {
    public ScanResult {
        newKeys = List.copyOf(newKeys);
        changedKeys = List.copyOf(changedKeys);
        removedKeys = List.copyOf(removedKeys);
        extractedValues = Map.copyOf(extractedValues);
    }

    /**
     * Gets total number of changed keys.
     */
    public int totalChanges() {
        return newKeys.size() + changedKeys.size() + removedKeys.size();
    }

    /**
     * Checks if scan detected any changes.
     */
    public boolean hasChanges() {
        return !newKeys.isEmpty() || !changedKeys.isEmpty() || !removedKeys.isEmpty();
    }
}
