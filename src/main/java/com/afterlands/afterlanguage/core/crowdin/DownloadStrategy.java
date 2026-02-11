package com.afterlands.afterlanguage.core.crowdin;

import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.afterlanguage.api.service.DynamicContentAPI;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Strategy for downloading translations from Crowdin.
 *
 * <p>Downloads translation exports from Crowdin, parses YAML files,
 * and merges with local database using conflict resolution.</p>
 *
 * <h3>Algorithm:</h3>
 * <ol>
 *     <li>Request export build from Crowdin (approved translations only)</li>
 *     <li>Poll until build is ready (max 60s)</li>
 *     <li>Download export ZIP file</li>
 *     <li>Extract YAML files by language</li>
 *     <li>For each translation:
 *         <ul>
 *             <li>If not in DB: INSERT</li>
 *             <li>If in DB but hash differs: Apply ConflictResolver</li>
 *             <li>If in DB and matches: SKIP</li>
 *         </ul>
 *     </li>
 *     <li>Update registry and invalidate caches</li>
 * </ol>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public class DownloadStrategy {

    private final CrowdinClient client;
    private final DynamicContentAPI dynamicAPI;
    private final LocaleMapper localeMapper;
    private final CrowdinConfig config;
    private final Path languagesDir;
    private final Logger logger;
    private final boolean debug;
    private final Yaml yaml;

    /**
     * Creates a new DownloadStrategy.
     *
     * @param client Crowdin API client
     * @param dynamicAPI Dynamic content API for saving translations
     * @param localeMapper Locale mapper for converting language codes
     * @param config Crowdin configuration
     * @param languagesDir Path to the languages directory for YAML file updates
     * @param logger Logger for debug output
     * @param debug Whether debug logging is enabled
     */
    public DownloadStrategy(
            @NotNull CrowdinClient client,
            @NotNull DynamicContentAPI dynamicAPI,
            @NotNull LocaleMapper localeMapper,
            @NotNull CrowdinConfig config,
            @NotNull Path languagesDir,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.dynamicAPI = Objects.requireNonNull(dynamicAPI, "dynamicAPI cannot be null");
        this.localeMapper = Objects.requireNonNull(localeMapper, "localeMapper cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.languagesDir = Objects.requireNonNull(languagesDir, "languagesDir cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.debug = debug;
        this.yaml = new Yaml();
    }

    /**
     * Result of a download operation.
     *
     * @param downloaded Number of translations downloaded and saved
     * @param skipped Number of translations skipped (unchanged)
     * @param conflicts Number of conflicts resolved
     * @param failed Number of translations that failed to save
     * @param errors List of error messages
     * @param downloadedKeys Keys that were downloaded
     */
    public record DownloadResult(
            int downloaded,
            int skipped,
            int conflicts,
            int failed,
            @NotNull List<String> errors,
            @NotNull List<String> downloadedKeys
    ) {
        public static DownloadResult empty() {
            return new DownloadResult(0, 0, 0, 0, List.of(), List.of());
        }

        public DownloadResult merge(DownloadResult other) {
            List<String> allErrors = new ArrayList<>(errors);
            allErrors.addAll(other.errors);
            List<String> allKeys = new ArrayList<>(downloadedKeys);
            allKeys.addAll(other.downloadedKeys);
            return new DownloadResult(
                    downloaded + other.downloaded,
                    skipped + other.skipped,
                    conflicts + other.conflicts,
                    failed + other.failed,
                    allErrors,
                    allKeys
            );
        }
    }

    /**
     * A parsed translation from Crowdin export.
     */
    public record CrowdinTranslation(
            @NotNull String namespace,
            @NotNull String key,
            @NotNull String language,
            @NotNull String text,
            @NotNull String hash
    ) {
        public Translation toTranslation() {
            return Translation.of(namespace, key, language, text);
        }
    }

    /**
     * Downloads translations for a namespace from Crowdin (root, no branch).
     *
     * @param namespace Namespace to download
     * @param conflictResolver Resolver for handling conflicts
     * @return CompletableFuture with download result
     */
    @NotNull
    public CompletableFuture<DownloadResult> downloadNamespace(
            @NotNull String namespace,
            @NotNull ConflictResolver conflictResolver
    ) {
        logger.info("[DownloadStrategy] Starting download for namespace: " + namespace);

        // Let Crowdin export all target languages configured in the project
        // (passing empty list avoids errors from locale codes not in the project)
        List<String> targetLanguages = List.of();

        // Build expected Crowdin file path for filtering
        String expectedCrowdinPath = config.getCrowdinFilePathForNamespace(namespace);

        // 1. Build translation export (global, no branch filter)
        return client.buildTranslations(
                targetLanguages,
                config.isSkipUntranslated(),
                config.isExportApprovedOnly()
        ).thenCompose(buildId -> {
            if (debug) {
                logger.info("[DownloadStrategy] Build started: " + buildId);
            }

            // 2. Wait for build to complete
            return client.waitForBuild(buildId, 60);

        }).thenCompose(buildStatus -> {
            // 3. Get download URL
            long buildId = buildStatus.get("id").getAsLong();
            return client.getDownloadUrl(buildId);

        }).thenCompose(downloadUrl -> {
            if (debug) {
                logger.info("[DownloadStrategy] Downloading from: " + downloadUrl);
            }

            // 4. Download ZIP file
            return client.downloadExport(downloadUrl);

        }).thenCompose(zipData -> {
            try {
                // 5. Extract YAML files
                Map<String, String> files = client.extractYamlFromZip(zipData);

                if (debug) {
                    logger.info("[DownloadStrategy] Extracted " + files.size() + " files");
                }

                // 6. Parse and filter for namespace with directory path filtering
                List<CrowdinTranslation> allTranslations = parseTranslations(files, namespace, expectedCrowdinPath);

                // Filter out source language — we only download target languages.
                // Source YAML files are the authoritative originals.
                String sourceLanguage = config.getSourceLanguage().replace("-", "_").toLowerCase();
                List<CrowdinTranslation> translations = allTranslations.stream()
                        .filter(t -> !t.language().equalsIgnoreCase(sourceLanguage))
                        .toList();

                if (translations.isEmpty()) {
                    logger.info("[DownloadStrategy] No target-language translations found for namespace: " + namespace +
                            " (parsed " + allTranslations.size() + " total, filtered source language '" + sourceLanguage + "')");
                    return CompletableFuture.completedFuture(DownloadResult.empty());
                }

                logger.info("[DownloadStrategy] Parsed " + translations.size() + " target-language translations for namespace: " + namespace);

                // 7. Write to YAML files and merge with local database
                int yamlUpdated = writeToYamlFiles(namespace, translations);
                logger.info("[DownloadStrategy] Updated " + yamlUpdated + " translations in YAML files");

                return mergeTranslations(translations, conflictResolver);

            } catch (IOException e) {
                logger.warning("[DownloadStrategy] Failed to extract ZIP: " + e.getMessage());
                return CompletableFuture.completedFuture(
                        new DownloadResult(0, 0, 0, 0, List.of(e.getMessage()), List.of())
                );
            }

        }).exceptionally(ex -> {
            logger.warning("[DownloadStrategy] Download failed for namespace " + namespace + ": " + ex.getMessage());
            return new DownloadResult(0, 0, 0, 0, List.of(ex.getMessage()), List.of());
        });
    }

    /**
     * Downloads translations for a namespace using pre-extracted build files.
     *
     * <p>This method is used when multiple namespaces share the same Crowdin build
     * to avoid 409 conflicts.</p>
     *
     * @param namespace Namespace to download
     * @param conflictResolver Resolver for handling conflicts
     * @param extractedFiles Pre-extracted YAML files from Crowdin build ZIP
     * @return CompletableFuture with download result
     */
    @NotNull
    public CompletableFuture<DownloadResult> downloadNamespaceFromExtractedFiles(
            @NotNull String namespace,
            @NotNull ConflictResolver conflictResolver,
            @NotNull Map<String, String> extractedFiles
    ) {
        logger.info("[DownloadStrategy] Processing download for namespace: " + namespace + " (from shared build)");

        // Build expected Crowdin file path for filtering
        String expectedCrowdinPath = config.getCrowdinFilePathForNamespace(namespace);

        try {
            // Parse and filter for namespace with directory path filtering
            List<CrowdinTranslation> allTranslations = parseTranslations(extractedFiles, namespace, expectedCrowdinPath);

            // Filter out source language — we only download target languages.
            // Source YAML files are the authoritative originals.
            String sourceLanguage = config.getSourceLanguage().replace("-", "_").toLowerCase();
            List<CrowdinTranslation> translations = allTranslations.stream()
                    .filter(t -> !t.language().equalsIgnoreCase(sourceLanguage))
                    .toList();

            if (translations.isEmpty()) {
                logger.info("[DownloadStrategy] No target-language translations found for namespace: " + namespace +
                        " (parsed " + allTranslations.size() + " total, filtered source language '" + sourceLanguage + "')");
                return CompletableFuture.completedFuture(DownloadResult.empty());
            }

            logger.info("[DownloadStrategy] Parsed " + translations.size() + " target-language translations for namespace: " + namespace);

            // Write to YAML files and merge with local database
            int yamlUpdated = writeToYamlFiles(namespace, translations);
            logger.info("[DownloadStrategy] Updated " + yamlUpdated + " translations in YAML files");

            return mergeTranslations(translations, conflictResolver);

        } catch (Exception e) {
            logger.warning("[DownloadStrategy] Failed to process shared build for namespace " + namespace + ": " + e.getMessage());
            return CompletableFuture.completedFuture(
                    new DownloadResult(0, 0, 0, 0, List.of(e.getMessage()), List.of())
            );
        }
    }

    /**
     * Parses translations from YAML files.
     *
     * @param files Map of file path to content
     * @param targetNamespace Namespace to filter for (null = all)
     * @param expectedCrowdinPath Expected Crowdin file path for this namespace (for directory filtering)
     * @return List of parsed translations
     */
    @NotNull
    public List<CrowdinTranslation> parseTranslations(
            @NotNull Map<String, String> files,
            @Nullable String targetNamespace,
            @Nullable String expectedCrowdinPath
    ) {
        List<CrowdinTranslation> translations = new ArrayList<>();

        // Extract path prefix without language for filtering (e.g., "/tutorial/afterquests/")
        String expectedPathPrefix = null;
        if (expectedCrowdinPath != null) {
            // Remove leading slash and .yml extension, e.g., "/tutorial/afterquests/afterquests.yml" -> "tutorial/afterquests/"
            expectedPathPrefix = expectedCrowdinPath.substring(1).replaceAll("/[^/]+\\.yml$", "/");
        }

        for (Map.Entry<String, String> entry : files.entrySet()) {
            String filePath = entry.getKey();
            String content = entry.getValue();

            // Extract locale from path (e.g., "en/tutorial/afterquests/afterquests.yml" -> "en")
            String locale = localeMapper.extractLocaleFromPath(filePath);
            if (locale == null) {
                if (debug) {
                    logger.fine("[DownloadStrategy] Could not extract locale from: " + filePath);
                }
                continue;
            }

            // Remove locale prefix for directory matching (e.g., "en/tutorial/afterquests/afterquests.yml" -> "tutorial/afterquests/afterquests.yml")
            String pathWithoutLocale = filePath.replaceFirst("^[^/]+/", "");

            // Filter by expected directory path if specified
            if (expectedPathPrefix != null) {
                if (!pathWithoutLocale.startsWith(expectedPathPrefix)) {
                    if (debug) {
                        logger.fine("[DownloadStrategy] Skipping file (wrong directory): " + filePath +
                                " (expected prefix: " + expectedPathPrefix + ")");
                    }
                    continue;
                }
            }

            // Extract namespace from path (e.g., "tutorial/afterjournal/messages.yml" -> "afterjournal")
            String namespace = extractNamespaceFromPath(filePath);
            if (namespace == null) {
                if (debug) {
                    logger.fine("[DownloadStrategy] Could not extract namespace from: " + filePath);
                }
                continue;
            }

            // Filter by target namespace if specified
            if (targetNamespace != null && !targetNamespace.equals(namespace)) {
                continue;
            }

            // Parse YAML content
            try {
                Object yamlObj = yaml.load(new StringReader(content));
                if (yamlObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> yamlData = (Map<String, Object>) yamlObj;
                    flattenYaml(yamlData, "", namespace, locale, translations);
                }
            } catch (Exception e) {
                logger.warning("[DownloadStrategy] Failed to parse YAML: " + filePath + " - " + e.getMessage());
            }
        }

        return translations;
    }

    /**
     * Extracts namespace from file path.
     * Example: "pt-BR/afterjournal/messages.yml" -> "afterjournal"
     */
    @Nullable
    private String extractNamespaceFromPath(@NotNull String filePath) {
        String[] parts = filePath.replace("\\", "/").split("/");

        // Look for pattern: <locale>/<namespace>/<file>.yml
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            // Check if this looks like a locale
            if (localeMapper.hasCrowdinMapping(part) || part.contains("-") || part.length() <= 5) {
                // Next part should be namespace
                if (i + 1 < parts.length && !parts[i + 1].endsWith(".yml")) {
                    return parts[i + 1];
                }
            }
        }

        // Fallback: use filename without extension
        String fileName = parts[parts.length - 1];
        if (fileName.endsWith(".yml")) {
            return fileName.substring(0, fileName.length() - 4);
        }

        return null;
    }

    /**
     * Flattens a nested YAML structure into dot-notation keys.
     */
    @SuppressWarnings("unchecked")
    private void flattenYaml(
            @NotNull Map<String, Object> data,
            @NotNull String prefix,
            @NotNull String namespace,
            @NotNull String language,
            @NotNull List<CrowdinTranslation> output
    ) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                flattenYaml((Map<String, Object>) value, key, namespace, language, output);
            } else if (value != null) {
                String text = value.toString();
                String hash = UploadStrategy.calculateMd5(text);
                output.add(new CrowdinTranslation(namespace, key, language, text, hash));
            }
        }
    }

    /**
     * Merges downloaded translations with local database.
     *
     * @param translations Translations from Crowdin
     * @param conflictResolver Resolver for handling conflicts
     * @return CompletableFuture with merge result
     */
    @NotNull
    private CompletableFuture<DownloadResult> mergeTranslations(
            @NotNull List<CrowdinTranslation> translations,
            @NotNull ConflictResolver conflictResolver
    ) {
        AtomicInteger downloaded = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger conflicts = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        List<String> downloadedKeys = Collections.synchronizedList(new ArrayList<>());

        // Process translations sequentially to avoid overwhelming the database
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (CrowdinTranslation crowdinTranslation : translations) {
            chain = chain.thenCompose(v -> {
                String fullKey = crowdinTranslation.namespace() + ":" + crowdinTranslation.key();

                return dynamicAPI.getTranslation(
                        crowdinTranslation.namespace(),
                        crowdinTranslation.key(),
                        crowdinTranslation.language()
                ).thenCompose(existing -> {
                    if (existing.isEmpty()) {
                        // New translation - insert
                        return dynamicAPI.createTranslation(
                                crowdinTranslation.namespace(),
                                crowdinTranslation.key(),
                                crowdinTranslation.language(),
                                crowdinTranslation.text()
                        ).thenAccept(ignored -> {
                            downloaded.incrementAndGet();
                            downloadedKeys.add(fullKey + " [" + crowdinTranslation.language() + "]");
                            if (debug) {
                                logger.fine("[DownloadStrategy] Inserted: " + fullKey);
                            }
                        });
                    } else {
                        // Existing translation - check for conflict
                        Translation local = existing.get();
                        String localHash = UploadStrategy.calculateMd5(local.text());

                        if (localHash.equals(crowdinTranslation.hash())) {
                            // Same content - skip
                            skipped.incrementAndGet();
                            return CompletableFuture.completedFuture(null);
                        } else {
                            // Conflict - resolve
                            conflicts.incrementAndGet();
                            Translation resolved = conflictResolver.resolve(local, crowdinTranslation);

                            if (resolved != null && !resolved.text().equals(local.text())) {
                                return dynamicAPI.updateTranslation(
                                        resolved.namespace(),
                                        resolved.key(),
                                        resolved.language(),
                                        resolved.text()
                                ).thenAccept(ignored -> {
                                    downloaded.incrementAndGet();
                                    downloadedKeys.add(fullKey + " [" + crowdinTranslation.language() + "] (conflict resolved)");
                                    if (debug) {
                                        logger.fine("[DownloadStrategy] Updated (conflict resolved): " + fullKey);
                                    }
                                });
                            } else {
                                skipped.incrementAndGet();
                                return CompletableFuture.completedFuture(null);
                            }
                        }
                    }
                }).exceptionally(ex -> {
                    failed.incrementAndGet();
                    errors.add(fullKey + ": " + ex.getMessage());
                    logger.warning("[DownloadStrategy] Failed to process: " + fullKey + " - " + ex.getMessage());
                    return null;
                });
            });
        }

        return chain.thenApply(v -> new DownloadResult(
                downloaded.get(),
                skipped.get(),
                conflicts.get(),
                failed.get(),
                errors,
                downloadedKeys
        ));
    }

    // ══════════════════════════════════════════════
    // YAML FILE WRITING
    // ══════════════════════════════════════════════

    /**
     * Writes downloaded translations back to the YAML files on disk.
     *
     * <p>Maps each translation key back to its source YAML file by matching
     * the key prefix to the file path structure, then updates the file in place.</p>
     *
     * @param namespace Namespace being downloaded
     * @param translations Downloaded translations from Crowdin
     * @return Number of translations written to YAML files
     */
    private int writeToYamlFiles(
            @NotNull String namespace,
            @NotNull List<CrowdinTranslation> translations
    ) {
        int totalUpdated = 0;

        // Determine source language to skip it — source YAML files are the
        // authoritative originals and must never be overwritten by Crowdin exports.
        String sourceLanguage = config.getSourceLanguage().replace("-", "_").toLowerCase();

        // Group translations by language
        Map<String, List<CrowdinTranslation>> byLanguage = translations.stream()
                .collect(Collectors.groupingBy(CrowdinTranslation::language));

        for (Map.Entry<String, List<CrowdinTranslation>> langEntry : byLanguage.entrySet()) {
            String language = langEntry.getKey();
            List<CrowdinTranslation> langTranslations = langEntry.getValue();

            // Skip source language — never overwrite the originals
            if (language.equalsIgnoreCase(sourceLanguage)) {
                if (debug) {
                    logger.fine("[DownloadStrategy] Skipping YAML write for source language: " + language);
                }
                continue;
            }

            Path nsDir = languagesDir.resolve(language).resolve(namespace);
            if (!Files.exists(nsDir)) {
                // Create namespace directory and mirror YAML structure from source language
                try {
                    Files.createDirectories(nsDir);
                    copyYamlStructureFromSource(sourceLanguage, namespace, nsDir);
                    logger.info("[DownloadStrategy] Created namespace dir for " + language + "/" + namespace);
                } catch (IOException e) {
                    logger.warning("[DownloadStrategy] Failed to create namespace dir for " + language + ": " + e.getMessage());
                    continue;
                }
            }

            // Build map of file prefix -> YAML file path
            Map<String, Path> prefixToFile = buildPrefixMap(nsDir);

            if (prefixToFile.isEmpty()) {
                // No YAML files exist yet — create a default messages.yml
                Path defaultFile = nsDir.resolve("messages.yml");
                try {
                    Files.createFile(defaultFile);
                    prefixToFile.put("messages.", defaultFile);
                    if (debug) {
                        logger.fine("[DownloadStrategy] Created default messages.yml in: " + nsDir);
                    }
                } catch (IOException e) {
                    logger.warning("[DownloadStrategy] Failed to create default messages.yml: " + e.getMessage());
                    continue;
                }
            }

            // Group translations by target file
            Map<Path, Map<String, String>> fileUpdates = new LinkedHashMap<>();
            for (CrowdinTranslation t : langTranslations) {
                // Find the longest matching prefix
                String bestPrefix = "";
                Path bestFile = null;
                for (Map.Entry<String, Path> pe : prefixToFile.entrySet()) {
                    if (t.key().startsWith(pe.getKey()) && pe.getKey().length() > bestPrefix.length()) {
                        bestPrefix = pe.getKey();
                        bestFile = pe.getValue();
                    }
                }

                if (bestFile != null) {
                    String yamlKey = t.key().substring(bestPrefix.length());
                    fileUpdates.computeIfAbsent(bestFile, k -> new LinkedHashMap<>())
                            .put(yamlKey, t.text());
                }
            }

            // Write updates to each YAML file
            for (Map.Entry<Path, Map<String, String>> fileEntry : fileUpdates.entrySet()) {
                Path yamlFile = fileEntry.getKey();
                Map<String, String> updates = fileEntry.getValue();

                try {
                    YamlConfiguration yamlConfig = YamlConfiguration.loadConfiguration(yamlFile.toFile());

                    for (Map.Entry<String, String> update : updates.entrySet()) {
                        yamlConfig.set(update.getKey(), update.getValue());
                    }

                    yamlConfig.save(yamlFile.toFile());
                    totalUpdated += updates.size();

                    if (debug) {
                        logger.info("[DownloadStrategy] Updated " + updates.size() +
                                " keys in " + yamlFile.getFileName() + " [" + language + "]");
                    }
                } catch (Exception e) {
                    logger.warning("[DownloadStrategy] Failed to update YAML file " + yamlFile + ": " + e.getMessage());
                }
            }
        }

        return totalUpdated;
    }

    /**
     * Copies the YAML file structure (empty files) from the source language namespace
     * to a target namespace directory.
     *
     * <p>This ensures that when Crowdin translations are downloaded for a language
     * that doesn't have the namespace directory yet, the correct file structure
     * is created so translations can be written to the right files.</p>
     *
     * @param sourceLanguage Source language code
     * @param namespace Namespace identifier
     * @param targetNsDir Target namespace directory to populate
     * @throws IOException If file operations fail
     */
    private void copyYamlStructureFromSource(
            @NotNull String sourceLanguage,
            @NotNull String namespace,
            @NotNull Path targetNsDir
    ) throws IOException {
        Path sourceNsDir = languagesDir.resolve(sourceLanguage).resolve(namespace);
        if (!Files.exists(sourceNsDir)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(sourceNsDir)) {
            paths.forEach(sourcePath -> {
                try {
                    Path relativePath = sourceNsDir.relativize(sourcePath);
                    Path targetPath = targetNsDir.resolve(relativePath);

                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else if (sourcePath.toString().endsWith(".yml") || sourcePath.toString().endsWith(".yaml")) {
                        // Create empty YAML file with same name
                        if (!Files.exists(targetPath)) {
                            Files.createDirectories(targetPath.getParent());
                            Files.createFile(targetPath);
                        }
                    }
                } catch (IOException e) {
                    logger.warning("[DownloadStrategy] Failed to copy structure: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Builds a map of file prefix to YAML file path for a namespace directory.
     *
     * <p>Walks the namespace directory tree, finds all YAML files, and calculates
     * their key prefix based on relative path (matching the loading convention).</p>
     *
     * @param nsDir Namespace directory
     * @return Map of prefix string -> file path
     */
    @NotNull
    private Map<String, Path> buildPrefixMap(@NotNull Path nsDir) {
        Map<String, Path> prefixToFile = new LinkedHashMap<>();

        try (Stream<Path> paths = Files.walk(nsDir)) {
            paths.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .forEach(file -> {
                        String prefix = calculateFilePrefix(nsDir, file);
                        prefixToFile.put(prefix, file);
                    });
        } catch (IOException e) {
            logger.warning("[DownloadStrategy] Failed to walk namespace dir: " + e.getMessage());
        }

        return prefixToFile;
    }

    /**
     * Calculates the key prefix for a YAML file based on its relative path.
     *
     * <p>Mirrors the convention in {@code YamlTranslationLoader.calculatePrefix}:
     * {@code afterlanguage/messages.yml} → prefix {@code messages.}</p>
     *
     * @param nsDir Namespace directory
     * @param file YAML file
     * @return Prefix string (ends with dot if non-empty)
     */
    @NotNull
    private String calculateFilePrefix(@NotNull Path nsDir, @NotNull Path file) {
        Path relativePath = nsDir.relativize(file);
        String pathStr = relativePath.toString();

        // Remove file extension
        pathStr = pathStr.replaceFirst("\\.ya?ml$", "");

        // Convert path separators to dots
        pathStr = pathStr.replace(File.separator, ".");
        pathStr = pathStr.replace("/", ".");

        return pathStr.isEmpty() ? "" : pathStr + ".";
    }
}
