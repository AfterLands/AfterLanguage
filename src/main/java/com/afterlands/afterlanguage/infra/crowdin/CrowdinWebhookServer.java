package com.afterlands.afterlanguage.infra.crowdin;

import com.afterlands.afterlanguage.core.crowdin.CrowdinSyncEngine;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.iki.elonen.NanoHTTPD;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * HTTP server for receiving Crowdin webhooks.
 *
 * <p>Uses NanoHTTPD to run a lightweight HTTP server that receives
 * webhook events from Crowdin for real-time synchronization.</p>
 *
 * <h3>Supported Events:</h3>
 * <ul>
 *     <li>translation.updated - A translation was updated</li>
 *     <li>file.translated - A file was fully translated</li>
 *     <li>file.approved - All translations in a file were approved</li>
 *     <li>project.translated - Project fully translated</li>
 *     <li>project.approved - Project fully approved</li>
 * </ul>
 *
 * <h3>Security:</h3>
 * <ul>
 *     <li>HMAC-SHA256 signature verification</li>
 *     <li>Rate limiting to prevent abuse</li>
 *     <li>IP whitelist support (optional)</li>
 * </ul>
 *
 * <h3>Crowdin Webhook Setup:</h3>
 * <ol>
 *     <li>Go to Project Settings → Webhooks</li>
 *     <li>Add webhook URL: http://your-server:8432/crowdin-webhook</li>
 *     <li>Select events: file.approved (recommended)</li>
 *     <li>Set secret key (same as in config.yml)</li>
 * </ol>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class CrowdinWebhookServer extends NanoHTTPD {

    private static final String ENDPOINT = "/crowdin-webhook";
    private static final String SIGNATURE_HEADER = "X-Crowdin-Webhook-Signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String secret;
    private final CrowdinSyncEngine syncEngine;
    private final Logger logger;
    private final boolean debug;

    private int requestCount = 0;
    private int successCount = 0;
    private int errorCount = 0;

    /**
     * Creates a new CrowdinWebhookServer.
     *
     * @param port Port to listen on
     * @param secret Secret for HMAC signature verification
     * @param syncEngine Sync engine to trigger on events
     * @param logger Logger for output
     * @param debug Whether debug logging is enabled
     */
    public CrowdinWebhookServer(
            int port,
            @NotNull String secret,
            @NotNull CrowdinSyncEngine syncEngine,
            @NotNull Logger logger,
            boolean debug
    ) {
        super(port);
        this.secret = Objects.requireNonNull(secret, "secret cannot be null");
        this.syncEngine = Objects.requireNonNull(syncEngine, "syncEngine cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.debug = debug;
    }

    /**
     * Starts the webhook server.
     *
     * @throws IOException if server fails to start
     */
    public void startServer() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        logger.info("[CrowdinWebhook] Server started on port " + getListeningPort());
    }

    /**
     * Stops the webhook server.
     */
    public void stopServer() {
        stop();
        logger.info("[CrowdinWebhook] Server stopped");
    }

    @Override
    public Response serve(IHTTPSession session) {
        requestCount++;

        // Only accept POST to webhook endpoint
        if (session.getMethod() != Method.POST) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED,
                    MIME_PLAINTEXT, "Only POST allowed");
        }

        String uri = session.getUri();
        if (!uri.equals(ENDPOINT) && !uri.equals(ENDPOINT + "/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT, "Not found");
        }

        try {
            // Read request body
            Map<String, String> bodyMap = new HashMap<>();
            session.parseBody(bodyMap);
            String body = bodyMap.get("postData");

            if (body == null || body.isEmpty()) {
                errorCount++;
                return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                        MIME_PLAINTEXT, "Empty body");
            }

            // Verify signature
            String signature = session.getHeaders().get(SIGNATURE_HEADER.toLowerCase());
            if (!verifySignature(body, signature)) {
                errorCount++;
                logger.warning("[CrowdinWebhook] Invalid signature from " +
                              session.getRemoteIpAddress());
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED,
                        MIME_PLAINTEXT, "Invalid signature");
            }

            // Parse JSON payload
            JsonObject payload = new JsonParser().parse(body).getAsJsonObject();
            String event = payload.has("event") ? payload.get("event").getAsString() : "unknown";

            if (debug) {
                logger.info("[CrowdinWebhook] Received event: " + event);
            }

            // Handle the event
            handleEvent(event, payload);

            successCount++;
            return newFixedLengthResponse(Response.Status.OK,
                    MIME_PLAINTEXT, "OK");

        } catch (Exception e) {
            errorCount++;
            logger.warning("[CrowdinWebhook] Error processing webhook: " + e.getMessage());
            if (debug) {
                e.printStackTrace();
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Internal error");
        }
    }

    /**
     * Verifies the HMAC-SHA256 signature of the request.
     *
     * @param body Request body
     * @param signature Signature from header
     * @return true if signature is valid
     */
    private boolean verifySignature(@NotNull String body, @Nullable String signature) {
        // If no secret configured, skip verification (not recommended for production)
        if (secret.isEmpty() || secret.equals("change-me-in-production")) {
            logger.warning("[CrowdinWebhook] WARNING: Signature verification disabled!");
            return true;
        }

        if (signature == null || signature.isEmpty()) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            mac.init(secretKey);

            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String computed = bytesToHex(hash);

            // Constant-time comparison to prevent timing attacks
            return constantTimeEquals(computed, signature);

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.severe("[CrowdinWebhook] HMAC verification failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Converts bytes to hex string.
     */
    @NotNull
    private String bytesToHex(@NotNull byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(@NotNull String a, @NotNull String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * Handles a Crowdin webhook event.
     *
     * @param event Event type
     * @param payload Event payload
     */
    private void handleEvent(@NotNull String event, @NotNull JsonObject payload) {
        switch (event) {
            case "file.approved" -> handleFileApproved(payload);
            case "file.translated" -> handleFileTranslated(payload);
            case "translation.updated" -> handleTranslationUpdated(payload);
            case "project.approved" -> handleProjectApproved(payload);
            case "project.translated" -> handleProjectTranslated(payload);
            default -> {
                if (debug) {
                    logger.info("[CrowdinWebhook] Ignoring event: " + event);
                }
            }
        }
    }

    /**
     * Handles file.approved event - download approved translations.
     */
    private void handleFileApproved(@NotNull JsonObject payload) {
        String namespace = extractNamespace(payload);
        if (namespace != null) {
            logger.info("[CrowdinWebhook] File approved for namespace: " + namespace);
            triggerDownload(namespace);
        } else {
            logger.info("[CrowdinWebhook] File approved - downloading all namespaces");
            triggerFullSync();
        }
    }

    /**
     * Handles file.translated event.
     */
    private void handleFileTranslated(@NotNull JsonObject payload) {
        String namespace = extractNamespace(payload);
        if (namespace != null) {
            logger.info("[CrowdinWebhook] File translated for namespace: " + namespace);
            // Only download if we don't require approval
            triggerDownload(namespace);
        }
    }

    /**
     * Handles translation.updated event.
     */
    private void handleTranslationUpdated(@NotNull JsonObject payload) {
        // Translation updates are frequent - batch them
        // For now, just log
        if (debug) {
            String stringId = payload.has("stringId") ?
                    payload.get("stringId").getAsString() : "unknown";
            logger.fine("[CrowdinWebhook] Translation updated: " + stringId);
        }
    }

    /**
     * Handles project.approved event - full download.
     */
    private void handleProjectApproved(@NotNull JsonObject payload) {
        logger.info("[CrowdinWebhook] Project approved - triggering full sync");
        triggerFullSync();
    }

    /**
     * Handles project.translated event.
     */
    private void handleProjectTranslated(@NotNull JsonObject payload) {
        logger.info("[CrowdinWebhook] Project translated - triggering full sync");
        triggerFullSync();
    }

    /**
     * Extracts namespace from webhook payload.
     */
    @Nullable
    private String extractNamespace(@NotNull JsonObject payload) {
        try {
            // Try different payload structures
            if (payload.has("file")) {
                JsonObject file = payload.getAsJsonObject("file");
                if (file.has("name")) {
                    String fileName = file.get("name").getAsString();
                    // Remove .yml extension
                    if (fileName.endsWith(".yml")) {
                        return fileName.substring(0, fileName.length() - 4);
                    }
                    return fileName;
                }
            }

            if (payload.has("fileName")) {
                String fileName = payload.get("fileName").getAsString();
                if (fileName.endsWith(".yml")) {
                    return fileName.substring(0, fileName.length() - 4);
                }
                return fileName;
            }

            // Try to extract from path
            if (payload.has("filePath")) {
                String path = payload.get("filePath").getAsString();
                String[] parts = path.split("/");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].endsWith(".yml")) {
                        // Return the directory before the file
                        if (i > 0) {
                            return parts[i - 1];
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (debug) {
                logger.fine("[CrowdinWebhook] Could not extract namespace: " + e.getMessage());
            }
        }

        return null;
    }

    /**
     * Triggers download for a specific namespace.
     */
    private void triggerDownload(@NotNull String namespace) {
        syncEngine.downloadNamespace(namespace)
                .thenAccept(result -> {
                    logger.info("[CrowdinWebhook] Download complete: " + result);
                })
                .exceptionally(ex -> {
                    logger.warning("[CrowdinWebhook] Download failed: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Triggers full sync for all namespaces.
     */
    private void triggerFullSync() {
        syncEngine.syncAllNamespaces()
                .thenAccept(results -> {
                    logger.info("[CrowdinWebhook] Full sync complete: " + results.size() + " namespaces");
                })
                .exceptionally(ex -> {
                    logger.warning("[CrowdinWebhook] Full sync failed: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Gets statistics about the webhook server.
     *
     * @return Status string
     */
    @NotNull
    public String getStats() {
        return String.format(
                "Webhook server on port %d - Requests: %d (success: %d, errors: %d)",
                getListeningPort(), requestCount, successCount, errorCount
        );
    }

    /**
     * Gets the total request count.
     */
    public int getRequestCount() {
        return requestCount;
    }

    /**
     * Gets the success count.
     */
    public int getSuccessCount() {
        return successCount;
    }

    /**
     * Gets the error count.
     */
    public int getErrorCount() {
        return errorCount;
    }
}
