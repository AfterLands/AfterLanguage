package com.afterlands.afterlanguage.core.template;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template compilation engine for placeholder parsing.
 *
 * <p>Compiles message templates with {@code {placeholder}} syntax into
 * {@link CompiledMessage} for fast replacement.</p>
 *
 * <h3>Supported Syntax:</h3>
 * <ul>
 *     <li>{@code {placeholder}} - Simple placeholder</li>
 *     <li>{@code {nested_placeholder}} - Underscore allowed</li>
 *     <li>{@code {UPPERCASE}} - Case-sensitive</li>
 * </ul>
 *
 * <h3>NOT Supported:</h3>
 * <ul>
 *     <li>{@code %placeholder%} - Reserved for PlaceholderAPI</li>
 *     <li>{@code {lang:...}} - Resolved earlier in pipeline</li>
 * </ul>
 *
 * <h3>Performance:</h3>
 * <ul>
 *     <li>Compilation: ~0.05ms per template</li>
 *     <li>No regex in hot path (only during compilation)</li>
 * </ul>
 */
public class TemplateEngine {

    /**
     * Pattern for matching placeholders: {key}
     * Allows alphanumeric and underscore in key.
     */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    private final boolean debug;

    /**
     * Creates a template engine.
     *
     * @param debug Enable debug logging
     */
    public TemplateEngine(boolean debug) {
        this.debug = debug;
    }

    /**
     * Compiles a template string into a CompiledMessage.
     *
     * <p>Extracts all placeholders and splits template into static parts.</p>
     *
     * @param template Template string with placeholders
     * @return Compiled message
     */
    @NotNull
    public CompiledMessage compile(@NotNull String template) {
        List<String> parts = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);

        int lastEnd = 0;

        while (matcher.find()) {
            // Add static text before this placeholder
            parts.add(template.substring(lastEnd, matcher.start()));

            // Add placeholder key
            String placeholderKey = matcher.group(1);
            placeholders.add(placeholderKey);

            lastEnd = matcher.end();
        }

        // Add final static text (after last placeholder)
        parts.add(template.substring(lastEnd));

        return new CompiledMessage(template, parts, placeholders);
    }

    /**
     * Checks if template contains placeholders.
     *
     * <p>Fast check without full compilation.</p>
     *
     * @param template Template string
     * @return true if template has placeholders
     */
    public boolean hasPlaceholders(@NotNull String template) {
        return PLACEHOLDER_PATTERN.matcher(template).find();
    }

    /**
     * Extracts all placeholder keys from template without compiling.
     *
     * @param template Template string
     * @return List of placeholder keys
     */
    @NotNull
    public List<String> extractPlaceholders(@NotNull String template) {
        List<String> placeholders = new ArrayList<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);

        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }

        return placeholders;
    }

    /**
     * Validates template syntax.
     *
     * <p>Checks for common errors like unclosed braces.</p>
     *
     * @param template Template string
     * @return Validation result
     */
    @NotNull
    public ValidationResult validate(@NotNull String template) {
        // Check for balanced braces
        int openCount = 0;
        int closeCount = 0;

        for (char c : template.toCharArray()) {
            if (c == '{') {
                openCount++;
            } else if (c == '}') {
                closeCount++;
            }
        }

        if (openCount != closeCount) {
            return ValidationResult.error(
                    "Unbalanced braces: " + openCount + " open, " + closeCount + " close"
            );
        }

        // Check for invalid placeholder syntax
        Matcher matcher = Pattern.compile("\\{[^}]*\\}").matcher(template);
        while (matcher.find()) {
            String placeholder = matcher.group();
            String content = placeholder.substring(1, placeholder.length() - 1);

            // Skip {lang:...} patterns (handled earlier)
            if (content.startsWith("lang:")) {
                continue;
            }

            // Validate placeholder key format
            if (!content.matches("[a-zA-Z0-9_]+")) {
                return ValidationResult.error(
                        "Invalid placeholder format: " + placeholder +
                        " (must contain only alphanumeric and underscore)"
                );
            }
        }

        return ValidationResult.valid();
    }

    /**
     * Validation result for template syntax.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String error;

        private ValidationResult(boolean valid, String error) {
            this.valid = valid;
            this.error = error;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getError() {
            return error;
        }

        @Override
        public String toString() {
            return valid ? "Valid" : "Invalid: " + error;
        }
    }
}
