package com.afterlands.afterlanguage.core.crowdin;

import com.afterlands.afterlanguage.api.model.Translation;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Strategy for uploading translations to Crowdin.
 *
 * <p>Uses MD5 hashing to detect changes and avoid uploading unchanged strings.</p>
 *
 * <h3>Algorithm:</h3>
 * <ol>
 *     <li>Calculate MD5 hash of each translation text</li>
 *     <li>Compare with stored crowdin_hash in database</li>
 *     <li>Upload only changed strings in batches of 100</li>
 *     <li>Update crowdin_hash after successful upload</li>
 * </ol>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class UploadStrategy {

    private final CrowdinClient client;
    private final CrowdinConfig config;
    private final Logger logger;
    private final boolean debug;
    private final Yaml yaml;

    /**
     * Creates a new UploadStrategy.
     *
     * @param client Crowdin API client
     * @param config Crowdin configuration
     * @param logger Logger for debug output
     * @param debug Whether debug logging is enabled
     */
    public UploadStrategy(
            @NotNull CrowdinClient client,
            @NotNull CrowdinConfig config,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.debug = debug;

        // Configure YAML writer
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        this.yaml = new Yaml(options);
    }

    /**
     * Result of an upload operation.
     *
     * @param uploaded Number of strings uploaded
     * @param skipped Number of strings skipped (unchanged)
     * @param failed Number of strings that failed to upload
     * @param errors List of error messages
     * @param uploadedKeys Keys that were uploaded
     */
    public record UploadResult(
            int uploaded,
            int skipped,
            int failed,
            @NotNull List<String> errors,
            @NotNull List<String> uploadedKeys
    ) {
        public static UploadResult empty() {
            return new UploadResult(0, 0, 0, List.of(), List.of());
        }

        public UploadResult merge(UploadResult other) {
            List<String> allErrors = new ArrayList<>(errors);
            allErrors.addAll(other.errors);
            List<String> allKeys = new ArrayList<>(uploadedKeys);
            allKeys.addAll(other.uploadedKeys);
            return new UploadResult(
                    uploaded + other.uploaded,
                    skipped + other.skipped,
                    failed + other.failed,
                    allErrors,
                    allKeys
            );
        }
    }

    /**
     * Translation with its current hash for change detection.
     */
    public record TranslationWithHash(
            @NotNull Translation translation,
            @NotNull String currentHash,
            @org.jetbrains.annotations.Nullable String storedHash
    ) {
        public boolean hasChanged() {
            return storedHash == null || !storedHash.equals(currentHash);
        }
    }

    /**
     * Detects which translations have changed and need uploading.
     *
     * @param translations List of translations to check
     * @param storedHashes Map of key -> stored crowdin_hash
     * @return List of translations with change information
     */
    @NotNull
    public List<TranslationWithHash> detectChanges(
            @NotNull List<Translation> translations,
            @NotNull Map<String, String> storedHashes
    ) {
        List<TranslationWithHash> result = new ArrayList<>();

        for (Translation t : translations) {
            String fullKey = t.fullKey();
            String currentHash = calculateMd5(t.text());
            String storedHash = storedHashes.get(fullKey);

            result.add(new TranslationWithHash(t, currentHash, storedHash));
        }

        return result;
    }

    /**
     * Filters translations that have changed.
     *
     * @param translations List of translations with hashes
     * @return List of changed translations only
     */
    @NotNull
    public List<TranslationWithHash> filterChanged(@NotNull List<TranslationWithHash> translations) {
        return translations.stream()
                .filter(TranslationWithHash::hasChanged)
                .toList();
    }

    /**
     * Uploads translations to Crowdin for a namespace (root, no branch).
     *
     * @param namespace Namespace to upload
     * @param translations Translations to upload (pt_br source strings)
     * @param storedHashes Current stored hashes from database
     * @return CompletableFuture with upload result
     */
    @NotNull
    public CompletableFuture<UploadResult> uploadNamespace(
            @NotNull String namespace,
            @NotNull List<Translation> translations,
            @NotNull Map<String, String> storedHashes
    ) {
        return uploadNamespace(namespace, translations, storedHashes, 0);
    }

    /**
     * Uploads translations to Crowdin for a namespace within a directory.
     *
     * @param namespace Namespace to upload
     * @param translations Translations to upload (pt_br source strings)
     * @param storedHashes Current stored hashes from database
     * @param directoryId Directory ID (0 for root)
     * @return CompletableFuture with upload result
     */
    @NotNull
    public CompletableFuture<UploadResult> uploadNamespace(
            @NotNull String namespace,
            @NotNull List<Translation> translations,
            @NotNull Map<String, String> storedHashes,
            long directoryId
    ) {
        if (translations.isEmpty()) {
            if (debug) {
                logger.info("[UploadStrategy] No translations to upload for namespace: " + namespace);
            }
            return CompletableFuture.completedFuture(UploadResult.empty());
        }

        // Detect changes
        List<TranslationWithHash> allTranslations = detectChanges(translations, storedHashes);
        List<TranslationWithHash> changedTranslations = filterChanged(allTranslations);

        int skipped = allTranslations.size() - changedTranslations.size();

        if (changedTranslations.isEmpty()) {
            if (debug) {
                logger.info("[UploadStrategy] No changes detected for namespace: " + namespace +
                           " (skipped " + skipped + " unchanged)");
            }
            return CompletableFuture.completedFuture(
                    new UploadResult(0, skipped, 0, List.of(), List.of())
            );
        }

        logger.info("[UploadStrategy] Uploading " + changedTranslations.size() + " changed strings for namespace: " +
                   namespace + " (skipped " + skipped + " unchanged)");

        // Convert ALL translations to YAML (not just changed ones) because
        // updateFile replaces the entire file on Crowdin. Sending only changed
        // translations would delete all unchanged ones from the project.
        String yamlContent = convertToYaml(allTranslations);
        String fileName = namespace + ".yml";

        // Upload flow: storage -> file (create or update)
        return uploadYamlFile(namespace, fileName, yamlContent, directoryId)
                .thenApply(fileId -> {
                    List<String> uploadedKeys = changedTranslations.stream()
                            .map(t -> t.translation().fullKey())
                            .toList();

                    return new UploadResult(
                            changedTranslations.size(),
                            skipped,
                            0,
                            List.of(),
                            uploadedKeys
                    );
                })
                .exceptionally(ex -> {
                    logger.warning("[UploadStrategy] Upload failed for namespace " + namespace + ": " + ex.getMessage());
                    return new UploadResult(
                            0,
                            skipped,
                            changedTranslations.size(),
                            List.of(ex.getMessage()),
                            List.of()
                    );
                });
    }

    /**
     * Uploads a YAML file to Crowdin (creates or updates).
     *
     * @param namespace Namespace (used in file path)
     * @param fileName File name
     * @param content YAML content
     * @param directoryId Directory ID where the file should be placed
     * @return CompletableFuture with file ID
     */
    @NotNull
    private CompletableFuture<Long> uploadYamlFile(
            @NotNull String namespace,
            @NotNull String fileName,
            @NotNull String content,
            long directoryId
    ) {
        // 1. Upload content to storage
        return client.uploadToStorage(fileName, content)
                .thenCompose(storageId -> {
                    if (debug) {
                        logger.info("[UploadStrategy] Uploaded to storage: " + storageId);
                    }

                    // 2. The directoryId is already resolved by caller (CrowdinSyncEngine.resolveDirectoryId)
                    // Build file path based on directory structure
                    String filePath = (directoryId == 0)
                            ? "/" + namespace + "/" + fileName
                            : config.getCrowdinFilePathForNamespace(namespace);

                    // 3. Check if file already exists
                    return client.getFileByPath(filePath)
                            .thenCompose(existingFile -> {
                                if (existingFile != null) {
                                    // Update existing file
                                    long fileId = existingFile.get("id").getAsLong();
                                    if (debug) {
                                        logger.info("[UploadStrategy] Updating existing file: " + fileId);
                                    }
                                    return client.updateFile(fileId, storageId)
                                            .thenApply(f -> f.get("id").getAsLong());
                                } else {
                                    // Create new file
                                    if (debug) {
                                        logger.info("[UploadStrategy] Creating new file in directory: " + directoryId);
                                    }
                                    return client.addFile(storageId, fileName, directoryId)
                                            .thenApply(f -> f.get("id").getAsLong());
                                }
                            });
                });
    }

    /**
     * Converts translations to YAML format for Crowdin.
     *
     * @param translations Translations to convert
     * @return YAML content as string
     */
    @NotNull
    public String convertToYaml(@NotNull List<TranslationWithHash> translations) {
        // Build nested map from dotted keys
        Map<String, Object> root = new LinkedHashMap<>();

        for (TranslationWithHash twh : translations) {
            Translation t = twh.translation();
            String key = t.key();
            String text = t.text();

            // Handle nested keys (e.g., "quest.started" -> {quest: {started: value}})
            setNestedValue(root, key, text);
        }

        return yaml.dump(root);
    }

    /**
     * Sets a nested value in a map using dot notation.
     */
    @SuppressWarnings("unchecked")
    private void setNestedValue(@NotNull Map<String, Object> root, @NotNull String key, @NotNull Object value) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = root;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object existing = current.get(part);
            if (existing instanceof Map) {
                current = (Map<String, Object>) existing;
            } else {
                Map<String, Object> newMap = new LinkedHashMap<>();
                current.put(part, newMap);
                current = newMap;
            }
        }

        current.put(parts[parts.length - 1], value);
    }

    /**
     * Uploads translations for a specific (non-source) language to Crowdin.
     *
     * <p>Converts translations to YAML, uploads to storage, then uploads as
     * a translation file for the specified language.</p>
     *
     * @param namespace Namespace
     * @param language AfterLanguage language code (e.g., "en_us")
     * @param translations Translations for this language
     * @param fileId Crowdin file ID for the namespace
     * @return CompletableFuture with upload result
     */
    @NotNull
    public CompletableFuture<UploadResult> uploadTranslationsForLanguage(
            @NotNull String namespace,
            @NotNull String language,
            @NotNull List<Translation> translations,
            long fileId
    ) {
        // Note: fileId is already branch-scoped (resolved by caller)
        if (translations.isEmpty()) {
            return CompletableFuture.completedFuture(UploadResult.empty());
        }

        // Convert translations to YAML
        List<TranslationWithHash> withHashes = translations.stream()
                .map(t -> new TranslationWithHash(t, calculateMd5(t.text()), null))
                .toList();
        String yamlContent = convertToYaml(withHashes);
        String fileName = namespace + ".yml";

        // Resolve Crowdin language ID from AfterLanguage code
        String crowdinLangId = resolveCrowdinLanguageId(language);

        logger.info("[UploadStrategy] Uploading " + translations.size() + " translations for " +
                   namespace + " [" + language + " -> " + crowdinLangId + "]");

        // Upload to storage, then upload as translation
        return client.uploadToStorage(fileName, yamlContent)
                .thenCompose(storageId -> client.uploadTranslation(fileId, crowdinLangId, storageId))
                .thenApply(response -> {
                    List<String> keys = translations.stream()
                            .map(Translation::fullKey)
                            .toList();
                    return new UploadResult(translations.size(), 0, 0, List.of(), keys);
                })
                .exceptionally(ex -> {
                    logger.warning("[UploadStrategy] Translation upload failed for " +
                                 namespace + " [" + language + "]: " + ex.getMessage());
                    return new UploadResult(0, 0, translations.size(), List.of(ex.getMessage()), List.of());
                });
    }

    /**
     * Resolves a Crowdin language ID from an AfterLanguage language code.
     *
     * <p>Uses the locale mappings from config, falling back to standard conversion.</p>
     *
     * @param languageCode AfterLanguage language code (e.g., "en_us", "es_es")
     * @return Crowdin language ID (e.g., "en", "es-ES")
     */
    @NotNull
    private String resolveCrowdinLanguageId(@NotNull String languageCode) {
        // Check reverse locale mappings (afterlang -> crowdin)
        Map<String, String> mappings = config.getLocaleMappings();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (entry.getValue().equals(languageCode)) {
                return entry.getKey();
            }
        }
        // Fallback: convert "en_us" -> "en", "es_es" -> "es-ES"
        String[] parts = languageCode.split("_");
        if (parts.length == 2) {
            return parts[0] + "-" + parts[1].toUpperCase();
        }
        return languageCode;
    }

    /**
     * Calculates MD5 hash of a string.
     *
     * @param text Text to hash
     * @return Hex-encoded MD5 hash
     */
    @NotNull
    public static String calculateMd5(@NotNull String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is always available
            throw new RuntimeException("MD5 not available", e);
        }
    }

    /**
     * Gets the new hashes for successfully uploaded translations.
     *
     * @param translations Uploaded translations
     * @return Map of fullKey -> hash
     */
    @NotNull
    public Map<String, String> getNewHashes(@NotNull List<TranslationWithHash> translations) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (TranslationWithHash t : translations) {
            hashes.put(t.translation().fullKey(), t.currentHash());
        }
        return hashes;
    }
}
