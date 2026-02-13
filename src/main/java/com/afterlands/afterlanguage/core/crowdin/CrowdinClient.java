package com.afterlands.afterlanguage.core.crowdin;

import com.google.gson.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.zip.*;

/**
 * HTTP client for Crowdin API v2.
 *
 * <p>Provides methods for interacting with the Crowdin REST API.</p>
 *
 * <h3>Features:</h3>
 * <ul>
 *     <li>Async operations via CompletableFuture</li>
 *     <li>Retry logic with exponential backoff</li>
 *     <li>Rate limiting (20 requests/second)</li>
 *     <li>Proper error handling for all HTTP status codes</li>
 * </ul>
 *
 * <h3>API Reference:</h3>
 * <a href="https://support.crowdin.com/api/v2/">Crowdin API v2 Documentation</a>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class CrowdinClient {

    private static final String BASE_URL = "https://api.crowdin.com/api/v2";
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000; // 1 second

    private final HttpClient httpClient;
    private final String apiToken;
    private final String projectId;
    private final Logger logger;
    private final boolean debug;
    private final Gson gson;
    private final Semaphore rateLimiter;
    private final ScheduledExecutorService scheduler;

    /**
     * Creates a new CrowdinClient.
     *
     * @param httpClient HTTP client to use
     * @param apiToken Crowdin API token
     * @param projectId Crowdin project ID
     * @param logger Logger for debug output
     * @param debug Whether debug logging is enabled
     */
    public CrowdinClient(
            @NotNull HttpClient httpClient,
            @NotNull String apiToken,
            @NotNull String projectId,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.apiToken = Objects.requireNonNull(apiToken, "apiToken cannot be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.debug = debug;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.rateLimiter = new Semaphore(20); // 20 requests per second
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CrowdinClient-RateLimiter");
            t.setDaemon(true);
            return t;
        });

        // Release permits every second (rate limit reset)
        scheduler.scheduleAtFixedRate(() -> {
            int permitsToRelease = 20 - rateLimiter.availablePermits();
            if (permitsToRelease > 0) {
                rateLimiter.release(permitsToRelease);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Shuts down the client and releases resources.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ══════════════════════════════════════════════
    // PROJECT OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Gets project details.
     *
     * @return CompletableFuture with project JSON
     */
    @NotNull
    public CompletableFuture<JsonObject> getProject() {
        return request("GET", "/projects/" + projectId, null)
                .thenApply(response -> getDataObject(response));
    }

    /**
     * Tests the connection by fetching project details.
     *
     * @return CompletableFuture with true if successful
     */
    @NotNull
    public CompletableFuture<Boolean> testConnection() {
        return getProject()
                .thenApply(project -> project != null && project.has("id"))
                .exceptionally(ex -> {
                    logger.warning("[CrowdinClient] Connection test failed: " + ex.getMessage());
                    return false;
                });
    }

    // ══════════════════════════════════════════════
    // DIRECTORY OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Gets or creates a nested directory path on Crowdin.
     *
     * <p>Creates each segment sequentially, caching IDs.
     * Example: ["quests-pve", "afterquests"] creates:
     *   1. /quests-pve/ (parentId=0)
     *   2. /quests-pve/afterquests/ (parentId=id_of_quests-pve)</p>
     *
     * <p>All directories are created at root level (no branchId) since
     * branches are not supported on Crowdin Free plan.</p>
     *
     * @param pathSegments Ordered list of directory names
     * @return CompletableFuture with ID of the deepest directory
     */
    @NotNull
    public CompletableFuture<Long> getOrCreateDirectoryPath(@NotNull List<String> pathSegments) {
        if (pathSegments.isEmpty()) {
            return CompletableFuture.completedFuture(0L); // Root
        }

        // Build path sequentially
        CompletableFuture<Long> future = CompletableFuture.completedFuture(0L);

        for (String segment : pathSegments) {
            future = future.thenCompose(parentId ->
                getOrCreateDirectory(segment, parentId)
            );
        }

        return future;
    }

    /**
     * Gets or creates a single directory.
     *
     * @param name Directory name
     * @param parentId Parent directory ID (0 for root)
     * @return CompletableFuture with directory ID
     */
    @NotNull
    private CompletableFuture<Long> getOrCreateDirectory(@NotNull String name, long parentId) {
        // Try to find existing directory
        return listDirectories(0, parentId).thenCompose(directories -> {
            for (JsonObject dir : directories) {
                if (dir.get("name").getAsString().equals(name)) {
                    return CompletableFuture.completedFuture(dir.get("id").getAsLong());
                }
            }
            // Directory doesn't exist, create it
            return createDirectory(name, parentId);
        });
    }

    /**
     * Creates a directory.
     *
     * @param name Directory name
     * @param parentId Parent directory ID (0 for root)
     * @return CompletableFuture with directory ID
     */
    @NotNull
    private CompletableFuture<Long> createDirectory(@NotNull String name, long parentId) {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        if (parentId > 0) {
            body.addProperty("directoryId", parentId);
        }

        return request("POST", "/projects/" + projectId + "/directories", body)
                .thenApply(response -> {
                    JsonObject data = getDataObject(response);
                    return data.get("id").getAsLong();
                })
                .exceptionally(throwable -> {
                    // Handle 409 Conflict (directory already exists due to race condition)
                    if (throwable.getCause() instanceof CrowdinApiException e) {
                        if (e.statusCode == 409) {
                            // Directory was created by another request, fetch it
                            try {
                                return listDirectories(0, parentId).thenCompose(directories -> {
                                    for (JsonObject dir : directories) {
                                        if (dir.get("name").getAsString().equals(name)) {
                                            return CompletableFuture.completedFuture(dir.get("id").getAsLong());
                                        }
                                    }
                                    throw new RuntimeException("Directory not found after 409 Conflict");
                                }).join();
                            } catch (Exception ex) {
                                throw new RuntimeException("Failed to recover from 409 Conflict", ex);
                            }
                        }
                    }
                    throw new RuntimeException(throwable);
                });
    }

    // ══════════════════════════════════════════════
    // FILE OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Lists all files in the project.
     *
     * @return CompletableFuture with list of file objects
     */
    @NotNull
    public CompletableFuture<List<JsonObject>> listFiles() {
        String url = "/projects/" + projectId + "/files?limit=500";
        return request("GET", url, null)
                .thenApply(this::getDataArray);
    }

    /**
     * Gets a file by its path.
     *
     * @param filePath File path in Crowdin (e.g., "/afterjournal/messages.yml")
     * @return CompletableFuture with file object or null if not found
     */
    @NotNull
    public CompletableFuture<JsonObject> getFileByPath(@NotNull String filePath) {
        return listFiles().thenApply(files -> {
            for (JsonObject file : files) {
                String path = file.get("path").getAsString();
                if (path.equals(filePath) || path.equals("/" + filePath)) {
                    return file;
                }
            }
            return null;
        });
    }

    /**
     * Adds a new file to the project.
     *
     * @param storageId Storage ID of uploaded content
     * @param name File name
     * @param directoryId Directory ID (optional, 0 for root)
     * @return CompletableFuture with created file object
     */
    @NotNull
    public CompletableFuture<JsonObject> addFile(long storageId, @NotNull String name, long directoryId) {
        JsonObject body = new JsonObject();
        body.addProperty("storageId", storageId);
        body.addProperty("name", name);
        if (directoryId > 0) {
            body.addProperty("directoryId", directoryId);
        }

        return request("POST", "/projects/" + projectId + "/files", body)
                .thenApply(response -> getDataObject(response));
    }

    /**
     * Updates an existing file.
     *
     * @param fileId File ID
     * @param storageId Storage ID of new content
     * @return CompletableFuture with updated file object
     */
    @NotNull
    public CompletableFuture<JsonObject> updateFile(long fileId, long storageId) {
        JsonObject body = new JsonObject();
        body.addProperty("storageId", storageId);

        return request("PUT", "/projects/" + projectId + "/files/" + fileId, body)
                .thenApply(response -> getDataObject(response));
    }

    /**
     * Lists all directories in the project, optionally filtered by parent.
     *
     * @param branchId Unused (for backward compatibility, always 0)
     * @param parentId Parent directory ID filter (0 for all)
     * @return CompletableFuture with list of directory objects
     */
    @NotNull
    public CompletableFuture<List<JsonObject>> listDirectories(long branchId, long parentId) {
        String url = "/projects/" + projectId + "/directories?limit=500";
        if (parentId > 0) {
            url += "&directoryId=" + parentId;
        }
        return request("GET", url, null)
                .thenApply(this::getDataArray);
    }

    /**
     * Lists all directories in the project.
     *
     * @return CompletableFuture with list of directory objects
     */
    @NotNull
    public CompletableFuture<List<JsonObject>> listDirectories() {
        return listDirectories(0, 0);
    }

    /**
     * Deletes a file from the project.
     *
     * @param fileId File ID to delete
     * @return CompletableFuture that completes when deletion is done
     */
    @NotNull
    public CompletableFuture<Void> deleteFile(long fileId) {
        return request("DELETE", "/projects/" + projectId + "/files/" + fileId, null)
                .thenApply(response -> null);
    }

    /**
     * Deletes a directory from the project.
     *
     * <p>Note: The directory must be empty before deletion. Delete all files
     * and subdirectories first.</p>
     *
     * @param directoryId Directory ID to delete
     * @return CompletableFuture that completes when deletion is done
     */
    @NotNull
    public CompletableFuture<Void> deleteDirectory(long directoryId) {
        return request("DELETE", "/projects/" + projectId + "/directories/" + directoryId, null)
                .thenApply(response -> null);
    }

    // ══════════════════════════════════════════════
    // STORAGE OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Uploads content to storage.
     *
     * <p>Uses the Crowdin API v2 storage endpoint which expects raw file content
     * with the filename specified in the {@code Crowdin-API-FileName} header.</p>
     *
     * @param fileName File name for the upload
     * @param content File content as string
     * @return CompletableFuture with storage ID
     */
    @NotNull
    public CompletableFuture<Long> uploadToStorage(@NotNull String fileName, @NotNull String content) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/storages"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/octet-stream")
                .header("Crowdin-API-FileName", fileName)
                .POST(HttpRequest.BodyPublishers.ofByteArray(content.getBytes(StandardCharsets.UTF_8)))
                .build();

        if (debug) {
            logger.info("[CrowdinClient] POST /storages (file: " + fileName + ", size: " + content.length() + " bytes)");
        }

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        JsonObject json = new JsonParser().parse(response.body()).getAsJsonObject();
                        JsonObject data = getDataObject(json);
                        return data.get("id").getAsLong();
                    }
                    String errorMessage = parseErrorMessage(response.body(), response.statusCode());
                    throw new CrowdinApiException(response.statusCode(), errorMessage);
                });
    }

    // ══════════════════════════════════════════════
    // STRING OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Lists source strings in a file.
     *
     * @param fileId File ID
     * @return CompletableFuture with list of string objects
     */
    @NotNull
    public CompletableFuture<List<JsonObject>> listStrings(long fileId) {
        return request("GET", "/projects/" + projectId + "/strings?fileId=" + fileId + "&limit=500", null)
                .thenApply(response -> getDataArray(response));
    }

    /**
     * Adds a source string.
     *
     * @param fileId File ID
     * @param identifier String identifier (key)
     * @param text String text
     * @return CompletableFuture with created string object
     */
    @NotNull
    public CompletableFuture<JsonObject> addString(long fileId, @NotNull String identifier, @NotNull String text) {
        JsonObject body = new JsonObject();
        body.addProperty("fileId", fileId);
        body.addProperty("identifier", identifier);
        body.addProperty("text", text);

        return request("POST", "/projects/" + projectId + "/strings", body)
                .thenApply(response -> getDataObject(response));
    }

    /**
     * Updates a source string.
     *
     * @param stringId String ID
     * @param text New text
     * @return CompletableFuture with updated string object
     */
    @NotNull
    public CompletableFuture<JsonObject> updateString(long stringId, @NotNull String text) {
        JsonArray patches = new JsonArray();
        JsonObject patch = new JsonObject();
        patch.addProperty("op", "replace");
        patch.addProperty("path", "/text");
        patch.addProperty("value", text);
        patches.add(patch);

        return request("PATCH", "/projects/" + projectId + "/strings/" + stringId, patches)
                .thenApply(response -> getDataObject(response));
    }

    // ══════════════════════════════════════════════
    // TRANSLATION OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Uploads a translation file for a specific language.
     *
     * <p>Uses the Crowdin API v2 endpoint:
     * {@code POST /projects/{projectId}/translations/{languageId}}</p>
     *
     * @param fileId Crowdin file ID
     * @param languageId Crowdin language ID (e.g., "en", "es-ES")
     * @param storageId Storage ID of the uploaded translation file
     * @return CompletableFuture with the response object
     */
    @NotNull
    public CompletableFuture<JsonObject> uploadTranslation(
            long fileId, @NotNull String languageId, long storageId,
            boolean autoApproveImported) {
        JsonObject body = new JsonObject();
        body.addProperty("storageId", storageId);
        body.addProperty("fileId", fileId);
        body.addProperty("importEqSuggestions", true);
        body.addProperty("autoApproveImported", autoApproveImported);

        return request("POST", "/projects/" + projectId + "/translations/" + languageId, body)
                .thenApply(response -> getDataObject(response));
    }

    /**
     * Lists translations for a string.
     *
     * @param stringId String ID
     * @param languageId Language ID
     * @return CompletableFuture with list of translation objects
     */
    @NotNull
    public CompletableFuture<List<JsonObject>> listTranslations(long stringId, @NotNull String languageId) {
        String url = "/projects/" + projectId + "/translations?stringId=" + stringId +
                     "&languageId=" + languageId + "&limit=100";
        return request("GET", url, null)
                .thenApply(response -> getDataArray(response));
    }

    // ══════════════════════════════════════════════
    // EXPORT OPERATIONS
    // ══════════════════════════════════════════════

    /**
     * Builds a project translation export (all branches).
     *
     * @param targetLanguageIds List of language IDs to export (empty for all)
     * @param skipUntranslatedStrings Whether to skip untranslated strings
     * @param exportApprovedOnly Whether to export only approved translations
     * @return CompletableFuture with build ID
     */
    @NotNull
    public CompletableFuture<Long> buildTranslations(
            @NotNull List<String> targetLanguageIds,
            boolean skipUntranslatedStrings,
            boolean exportApprovedOnly
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("skipUntranslatedStrings", skipUntranslatedStrings);
        body.addProperty("exportApprovedOnly", exportApprovedOnly);

        // Note: branchId is not used - builds are always global in directory-based isolation

        if (!targetLanguageIds.isEmpty()) {
            JsonArray langs = new JsonArray();
            targetLanguageIds.forEach(langs::add);
            body.add("targetLanguageIds", langs);
        }

        return request("POST", "/projects/" + projectId + "/translations/builds", body)
                .thenApply(response -> {
                    JsonObject data = getDataObject(response);
                    return data.get("id").getAsLong();
                });
    }

    /**
     * Gets the status of a translation build.
     *
     * @param buildId Build ID
     * @return CompletableFuture with build status object
     */
    @NotNull
    public CompletableFuture<JsonObject> getBuildStatus(long buildId) {
        return request("GET", "/projects/" + projectId + "/translations/builds/" + buildId, null)
                .thenApply(response -> getDataObject(response));
    }

    /**
     * Downloads a completed build.
     *
     * @param buildId Build ID
     * @return CompletableFuture with download URL
     */
    @NotNull
    public CompletableFuture<String> getDownloadUrl(long buildId) {
        return request("GET", "/projects/" + projectId + "/translations/builds/" + buildId + "/download", null)
                .thenApply(response -> {
                    JsonObject data = getDataObject(response);
                    return data.get("url").getAsString();
                });
    }

    /**
     * Downloads the translation export ZIP file.
     *
     * @param downloadUrl URL to download from
     * @return CompletableFuture with ZIP file contents as byte array
     */
    @NotNull
    public CompletableFuture<byte[]> downloadExport(@NotNull String downloadUrl) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(HttpResponse::body);
    }

    /**
     * Extracts YAML files from a ZIP archive.
     *
     * @param zipData ZIP file contents
     * @return Map of file path to file contents
     */
    @NotNull
    public Map<String, String> extractYamlFromZip(@NotNull byte[] zipData) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".yml")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                    files.put(entry.getName(), baos.toString(StandardCharsets.UTF_8));
                }
                zis.closeEntry();
            }
        }

        if (debug) {
            logger.info("[CrowdinClient] Extracted " + files.size() + " YAML files from ZIP");
        }

        return files;
    }

    /**
     * Waits for a build to complete, polling status every 2 seconds.
     *
     * @param buildId Build ID
     * @param maxWaitSeconds Maximum time to wait
     * @return CompletableFuture with final build status
     */
    @NotNull
    public CompletableFuture<JsonObject> waitForBuild(long buildId, int maxWaitSeconds) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
        long startTime = System.currentTimeMillis();

        poller.scheduleAtFixedRate(() -> {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (elapsed > maxWaitSeconds) {
                poller.shutdown();
                future.completeExceptionally(new TimeoutException("Build timed out after " + maxWaitSeconds + "s"));
                return;
            }

            getBuildStatus(buildId).thenAccept(status -> {
                String statusStr = status.get("status").getAsString();
                if (debug) {
                    logger.info("[CrowdinClient] Build " + buildId + " status: " + statusStr);
                }

                if ("finished".equals(statusStr)) {
                    poller.shutdown();
                    future.complete(status);
                } else if ("failed".equals(statusStr) || "canceled".equals(statusStr)) {
                    poller.shutdown();
                    future.completeExceptionally(new RuntimeException("Build " + statusStr));
                }
            }).exceptionally(ex -> {
                poller.shutdown();
                future.completeExceptionally(ex);
                return null;
            });
        }, 0, 2, TimeUnit.SECONDS);

        return future;
    }

    // ══════════════════════════════════════════════
    // HTTP HELPERS
    // ══════════════════════════════════════════════

    /**
     * Makes an API request with retry logic.
     */
    @NotNull
    private CompletableFuture<JsonObject> request(
            @NotNull String method,
            @NotNull String path,
            @Nullable JsonElement body
    ) {
        return requestWithRetry(method, path, body, 0);
    }

    /**
     * Makes an API request with retry logic.
     */
    @NotNull
    private CompletableFuture<JsonObject> requestWithRetry(
            @NotNull String method,
            @NotNull String path,
            @Nullable JsonElement body,
            int attempt
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
            return null;
        }).thenCompose(ignored -> {
            String url = BASE_URL + path;

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json");

            HttpRequest.BodyPublisher bodyPublisher = body != null
                    ? HttpRequest.BodyPublishers.ofString(gson.toJson(body))
                    : HttpRequest.BodyPublishers.noBody();

            HttpRequest request = switch (method) {
                case "GET" -> builder.GET().build();
                case "POST" -> builder.POST(bodyPublisher).build();
                case "PUT" -> builder.PUT(bodyPublisher).build();
                case "PATCH" -> builder.method("PATCH", bodyPublisher).build();
                case "DELETE" -> builder.DELETE().build();
                default -> throw new IllegalArgumentException("Unknown method: " + method);
            };

            if (debug) {
                logger.info("[CrowdinClient] " + method + " " + path);
            }

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        }).thenCompose(response -> {
            int status = response.statusCode();
            String responseBody = response.body();

            if (debug) {
                logger.info("[CrowdinClient] Response: " + status);
            }

            // Success
            if (status >= 200 && status < 300) {
                if (responseBody == null || responseBody.isEmpty()) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }
                return CompletableFuture.completedFuture(
                        new JsonParser().parse(responseBody).getAsJsonObject()
                );
            }

            // Rate limited - retry after delay
            if (status == 429 && attempt < MAX_RETRIES) {
                long delay = getRetryDelay(response, attempt);
                logger.warning("[CrowdinClient] Rate limited, retrying in " + delay + "ms");
                return CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                        .thenCompose(v -> requestWithRetry(method, path, body, attempt + 1));
            }

            // Server error - retry with backoff
            if (status >= 500 && attempt < MAX_RETRIES) {
                long delay = INITIAL_RETRY_DELAY_MS * (long) Math.pow(2, attempt);
                logger.warning("[CrowdinClient] Server error " + status + ", retrying in " + delay + "ms");
                return CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                        .thenCompose(v -> requestWithRetry(method, path, body, attempt + 1));
            }

            // Parse error response
            String errorMessage = parseErrorMessage(responseBody, status);
            return CompletableFuture.failedFuture(new CrowdinApiException(status, errorMessage));
        });
    }

    /**
     * Makes a multipart form upload request.
     */
    @NotNull
    private CompletableFuture<JsonObject> requestMultipart(
            @NotNull String path,
            @NotNull String fileName,
            @NotNull byte[] content
    ) {
        String boundary = "----CrowdinBoundary" + System.currentTimeMillis();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true);

        writer.append("--").append(boundary).append("\r\n");
        writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
              .append(fileName).append("\"\r\n");
        writer.append("Content-Type: application/octet-stream\r\n\r\n");
        writer.flush();

        try {
            baos.write(content);
            baos.flush();
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }

        writer.append("\r\n--").append(boundary).append("--\r\n");
        writer.flush();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return new JsonParser().parse(response.body()).getAsJsonObject();
                    }
                    throw new CrowdinApiException(response.statusCode(), parseErrorMessage(response.body(), response.statusCode()));
                });
    }

    /**
     * Gets retry delay from response or calculates exponential backoff.
     */
    private long getRetryDelay(@NotNull HttpResponse<?> response, int attempt) {
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                return Long.parseLong(retryAfter.get()) * 1000;
            } catch (NumberFormatException ignored) {
            }
        }
        return INITIAL_RETRY_DELAY_MS * (long) Math.pow(2, attempt);
    }

    /**
     * Parses error message from response body.
     */
    @NotNull
    private String parseErrorMessage(@Nullable String body, int status) {
        if (body != null && !body.isEmpty()) {
            try {
                JsonObject json = new JsonParser().parse(body).getAsJsonObject();
                // Format 1: {"error": {"message": "...", "code": 123}}
                if (json.has("error")) {
                    JsonObject error = json.getAsJsonObject("error");
                    if (error.has("message")) {
                        String msg = error.get("message").getAsString();
                        if (error.has("code")) {
                            msg += " (code: " + error.get("code").getAsInt() + ")";
                        }
                        return msg;
                    }
                }
                // Format 2: {"errors": [{...}]}
                if (json.has("errors")) {
                    JsonArray errors = json.getAsJsonArray("errors");
                    if (errors.size() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonElement err : errors) {
                            if (sb.length() > 0) sb.append("; ");
                            sb.append(err.toString());
                        }
                        return sb.toString();
                    }
                }
                // Fallback: return the whole body (truncated)
                String raw = body.length() > 500 ? body.substring(0, 500) + "..." : body;
                return "HTTP " + status + " - " + raw;
            } catch (Exception ignored) {
            }
        }
        return "HTTP " + status;
    }

    /**
     * Extracts data object from response.
     */
    @NotNull
    private JsonObject getDataObject(@NotNull JsonObject response) {
        if (response.has("data")) {
            return response.getAsJsonObject("data");
        }
        return response;
    }

    /**
     * Extracts data array from response.
     */
    @NotNull
    private List<JsonObject> getDataArray(@NotNull JsonObject response) {
        List<JsonObject> result = new ArrayList<>();
        if (response.has("data")) {
            JsonArray data = response.getAsJsonArray("data");
            for (JsonElement element : data) {
                if (element.isJsonObject()) {
                    JsonObject item = element.getAsJsonObject();
                    if (item.has("data")) {
                        result.add(item.getAsJsonObject("data"));
                    } else {
                        result.add(item);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Exception for Crowdin API errors.
     */
    public static class CrowdinApiException extends RuntimeException {
        private final int statusCode;

        public CrowdinApiException(int statusCode, String message) {
            super("Crowdin API error (" + statusCode + "): " + message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
