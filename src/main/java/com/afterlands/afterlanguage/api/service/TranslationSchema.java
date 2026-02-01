package com.afterlands.afterlanguage.api.service;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Schema for config scanning rules.
 *
 * <p>Defines which config files to scan and what paths/actions to extract as translatable keys.</p>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * TranslationSchema schema = TranslationSchema.builder("myplugin")
 *     .scanFile("inventories/**\/*.yml")
 *     .atPath("title")
 *     .atPath("items.*.name")
 *     .atPath("items.*.lore.*")
 *     .atPath("items.*.actions")
 *     .withActionFilter("message", "title", "actionbar")
 *     .build();
 * }</pre>
 */
public class TranslationSchema {

    private final String namespace;
    private final List<ScanRule> rules;

    private TranslationSchema(String namespace, List<ScanRule> rules) {
        this.namespace = namespace;
        this.rules = List.copyOf(rules);
    }

    @NotNull
    public String getNamespace() {
        return namespace;
    }

    @NotNull
    public List<ScanRule> getRules() {
        return rules;
    }

    @NotNull
    public static Builder builder(@NotNull String namespace) {
        return new Builder(namespace);
    }

    /**
     * Builder for TranslationSchema.
     */
    public static class Builder {
        private final String namespace;
        private final List<ScanRule> rules = new ArrayList<>();

        private Builder(String namespace) {
            this.namespace = Objects.requireNonNull(namespace, "namespace");
        }

        /**
         * Scans files matching glob pattern.
         *
         * @param globPattern Glob pattern (e.g., "inventories/**\/*.yml")
         * @return This builder
         */
        @NotNull
        public Builder scanFile(@NotNull String globPattern) {
            ScanRule rule = new ScanRule(globPattern);
            rules.add(rule);
            return this;
        }

        /**
         * Adds YAML path to scan in previously added file pattern.
         *
         * @param yamlPath YAML path with wildcards (e.g., "items.*.name")
         * @return This builder
         */
        @NotNull
        public Builder atPath(@NotNull String yamlPath) {
            if (rules.isEmpty()) {
                throw new IllegalStateException("Must call scanFile() before atPath()");
            }

            ScanRule currentRule = rules.get(rules.size() - 1);
            currentRule.addPath(yamlPath);
            return this;
        }

        /**
         * Filters which action types to extract from action lists.
         *
         * @param actionTypes Action types to extract (e.g., "message", "title")
         * @return This builder
         */
        @NotNull
        public Builder withActionFilter(@NotNull String... actionTypes) {
            if (rules.isEmpty()) {
                throw new IllegalStateException("Must call scanFile() before withActionFilter()");
            }

            ScanRule currentRule = rules.get(rules.size() - 1);
            currentRule.setActionFilter(Arrays.asList(actionTypes));
            return this;
        }

        /**
         * Builds the schema.
         */
        @NotNull
        public TranslationSchema build() {
            if (rules.isEmpty()) {
                throw new IllegalStateException("Schema must have at least one scan rule");
            }

            return new TranslationSchema(namespace, rules);
        }
    }

    /**
     * Single scan rule (file pattern + paths + action filters).
     */
    public static class ScanRule {
        private final String fileGlobPattern;
        private final List<String> yamlPaths = new ArrayList<>();
        private List<String> actionFilter = List.of();

        private ScanRule(String fileGlobPattern) {
            this.fileGlobPattern = Objects.requireNonNull(fileGlobPattern);
        }

        @NotNull
        public String getFileGlobPattern() {
            return fileGlobPattern;
        }

        @NotNull
        public List<String> getYamlPaths() {
            return List.copyOf(yamlPaths);
        }

        @NotNull
        public List<String> getActionFilter() {
            return actionFilter;
        }

        void addPath(String path) {
            yamlPaths.add(path);
        }

        void setActionFilter(List<String> filter) {
            this.actionFilter = List.copyOf(filter);
        }
    }
}
