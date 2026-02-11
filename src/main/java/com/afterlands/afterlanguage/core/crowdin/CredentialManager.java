package com.afterlands.afterlanguage.core.crowdin;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages Crowdin API credentials with environment variable resolution.
 *
 * <p>Resolves placeholders in configuration values:</p>
 * <ul>
 *     <li>{@code ${VAR_NAME}} - Resolves to environment variable value</li>
 *     <li>{@code ${VAR_NAME:-default}} - Resolves to env var or default if not set</li>
 * </ul>
 *
 * <h3>Security Best Practices:</h3>
 * <ul>
 *     <li>Never commit API tokens to version control</li>
 *     <li>Use environment variables in production</li>
 *     <li>Rotate tokens every 90 days</li>
 *     <li>Use Crowdin scoped tokens (read + write, not full access)</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // crowdin.yml content:
 * // project-id: "${CROWDIN_PROJECT_ID}"
 * // api-token: "${CROWDIN_API_TOKEN}"
 *
 * YamlConfiguration config = YamlConfiguration.loadConfiguration(crowdinFile);
 * CredentialManager creds = new CredentialManager(config);
 *
 * String projectId = creds.getProjectId();  // Resolved from env var
 * String token = creds.getApiToken();       // Resolved from env var
 * }</pre>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class CredentialManager {

    /**
     * Pattern to match environment variable placeholders.
     * Supports: ${VAR}, ${VAR:-default}
     */
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::-([^}]*))?}");

    private final String projectId;
    private final String apiToken;
    private final String webhookSecret;

    /**
     * Creates a new CredentialManager from configuration.
     *
     * @param config Configuration section containing credentials
     * @throws IllegalStateException if required credentials are missing
     */
    public CredentialManager(@NotNull ConfigurationSection config) {
        Objects.requireNonNull(config, "config cannot be null");

        // Resolve credentials from config (with env var support)
        String rawProjectId = config.getString("project-id", "");
        String rawApiToken = config.getString("api-token", "");
        String rawWebhookSecret = config.getString("webhook-secret", "");

        this.projectId = resolveEnvVar(rawProjectId);
        this.apiToken = resolveEnvVar(rawApiToken);
        this.webhookSecret = resolveEnvVar(rawWebhookSecret);
    }

    /**
     * Creates a new CredentialManager with explicit values.
     *
     * @param projectId Crowdin project ID
     * @param apiToken Crowdin API token
     * @param webhookSecret Webhook secret for signature verification
     */
    public CredentialManager(
            @NotNull String projectId,
            @NotNull String apiToken,
            @Nullable String webhookSecret
    ) {
        this.projectId = resolveEnvVar(projectId);
        this.apiToken = resolveEnvVar(apiToken);
        this.webhookSecret = webhookSecret != null ? resolveEnvVar(webhookSecret) : null;
    }

    /**
     * Validates that required credentials are present.
     *
     * @throws IllegalStateException if projectId or apiToken is missing/empty
     */
    public void validate() {
        if (projectId == null || projectId.isEmpty() || projectId.startsWith("${")) {
            throw new IllegalStateException(
                    "Crowdin project-id not configured. Set CROWDIN_PROJECT_ID environment variable " +
                    "or provide the value directly in crowdin.yml"
            );
        }

        if (apiToken == null || apiToken.isEmpty() || apiToken.startsWith("${")) {
            throw new IllegalStateException(
                    "Crowdin api-token not configured. Set CROWDIN_API_TOKEN environment variable " +
                    "or provide the value directly in crowdin.yml"
            );
        }
    }

    /**
     * Checks if credentials are valid (non-empty and resolved).
     *
     * @return true if both projectId and apiToken are present and resolved
     */
    public boolean isValid() {
        return projectId != null && !projectId.isEmpty() && !projectId.startsWith("${")
                && apiToken != null && !apiToken.isEmpty() && !apiToken.startsWith("${");
    }

    /**
     * Resolves environment variable placeholders in a string.
     *
     * <p>Supports two formats:</p>
     * <ul>
     *     <li>{@code ${VAR_NAME}} - Returns env var value or original string if not found</li>
     *     <li>{@code ${VAR_NAME:-default}} - Returns env var value or "default" if not found</li>
     * </ul>
     *
     * @param value String potentially containing placeholders
     * @return Resolved string
     */
    @NotNull
    public static String resolveEnvVar(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String defaultValue = matcher.group(2);

            String envValue = System.getenv(varName);

            String replacement;
            if (envValue != null && !envValue.isEmpty()) {
                replacement = envValue;
            } else if (defaultValue != null) {
                replacement = defaultValue;
            } else {
                // Keep original placeholder if env var not found and no default
                replacement = matcher.group(0);
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Gets the Crowdin project ID.
     *
     * @return Project ID (resolved from env var if applicable)
     */
    @NotNull
    public String getProjectId() {
        return projectId != null ? projectId : "";
    }

    /**
     * Gets the Crowdin API token.
     *
     * @return API token (resolved from env var if applicable)
     */
    @NotNull
    public String getApiToken() {
        return apiToken != null ? apiToken : "";
    }

    /**
     * Gets the webhook secret for signature verification.
     *
     * @return Webhook secret, or null if not configured
     */
    @Nullable
    public String getWebhookSecret() {
        return webhookSecret;
    }

    /**
     * Checks if webhook secret is configured.
     *
     * @return true if webhook secret is non-null and non-empty
     */
    public boolean hasWebhookSecret() {
        return webhookSecret != null && !webhookSecret.isEmpty() && !webhookSecret.equals("change-me-in-production");
    }

    /**
     * Gets a masked version of the API token for logging.
     *
     * @return Token with most characters replaced by asterisks
     */
    @NotNull
    public String getMaskedToken() {
        if (apiToken == null || apiToken.isEmpty()) {
            return "(not set)";
        }
        if (apiToken.startsWith("${")) {
            return "(unresolved: " + apiToken + ")";
        }
        if (apiToken.length() <= 8) {
            return "****";
        }
        return apiToken.substring(0, 4) + "****" + apiToken.substring(apiToken.length() - 4);
    }

    @Override
    public String toString() {
        return "CredentialManager[projectId=" + projectId +
               ", token=" + getMaskedToken() +
               ", webhookSecret=" + (hasWebhookSecret() ? "(set)" : "(not set)") + "]";
    }
}
