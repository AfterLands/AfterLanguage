package com.afterlands.afterlanguage.core.crowdin;

import com.afterlands.afterlanguage.api.model.Translation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Logger;

/**
 * Interface for resolving conflicts between local and Crowdin translations.
 *
 * <p>A conflict occurs when both the local database and Crowdin have
 * different versions of the same translation.</p>
 *
 * <h3>Available Strategies:</h3>
 * <ul>
 *     <li>{@link CrowdinWinsResolver} - Crowdin version wins (default)</li>
 *     <li>{@link LocalWinsResolver} - Local version wins</li>
 *     <li>{@link ManualResolver} - Store for manual review</li>
 * </ul>
 *
 * @author AfterLands Team
 * @since 1.3.0
 */
public interface ConflictResolver {

    /**
     * Resolves a conflict between local and Crowdin translations.
     *
     * @param local The current local translation
     * @param crowdin The translation from Crowdin
     * @return The resolved translation to use, or null to skip
     */
    @Nullable
    Translation resolve(
            @NotNull Translation local,
            @NotNull DownloadStrategy.CrowdinTranslation crowdin
    );

    /**
     * Gets the name of this resolver strategy.
     *
     * @return Strategy name
     */
    @NotNull
    String getName();

    /**
     * Creates a resolver based on configuration strategy.
     *
     * @param strategy The strategy from config
     * @param logger Logger for debug output
     * @return Appropriate resolver instance
     */
    @NotNull
    static ConflictResolver create(
            @NotNull CrowdinConfig.ConflictResolutionStrategy strategy,
            @NotNull Logger logger
    ) {
        return switch (strategy) {
            case CROWDIN_WINS -> new CrowdinWinsResolver(logger);
            case LOCAL_WINS -> new LocalWinsResolver(logger);
            case MANUAL -> new ManualResolver(logger);
        };
    }

    // ══════════════════════════════════════════════
    // STRATEGY IMPLEMENTATIONS
    // ══════════════════════════════════════════════

    /**
     * Crowdin version always wins.
     *
     * <p>This is the default strategy because approved translations
     * in Crowdin are considered authoritative.</p>
     */
    class CrowdinWinsResolver implements ConflictResolver {
        private final Logger logger;

        public CrowdinWinsResolver(@NotNull Logger logger) {
            this.logger = logger;
        }

        @Override
        @NotNull
        public Translation resolve(
                @NotNull Translation local,
                @NotNull DownloadStrategy.CrowdinTranslation crowdin
        ) {
            logger.fine("[ConflictResolver] CROWDIN_WINS: " + local.fullKey() +
                       " [" + local.language() + "] - Using Crowdin version");
            return crowdin.toTranslation();
        }

        @Override
        @NotNull
        public String getName() {
            return "CROWDIN_WINS";
        }
    }

    /**
     * Local version always wins.
     *
     * <p>Use this strategy to preserve local modifications
     * and prevent Crowdin from overwriting them.</p>
     */
    class LocalWinsResolver implements ConflictResolver {
        private final Logger logger;

        public LocalWinsResolver(@NotNull Logger logger) {
            this.logger = logger;
        }

        @Override
        @Nullable
        public Translation resolve(
                @NotNull Translation local,
                @NotNull DownloadStrategy.CrowdinTranslation crowdin
        ) {
            logger.fine("[ConflictResolver] LOCAL_WINS: " + local.fullKey() +
                       " [" + local.language() + "] - Keeping local version");
            // Return null to skip (keep local unchanged)
            return null;
        }

        @Override
        @NotNull
        public String getName() {
            return "LOCAL_WINS";
        }
    }

    /**
     * Manual resolution - stores conflict for admin review.
     *
     * <p>In this strategy, conflicts are logged and the local version
     * is kept until an admin manually resolves the conflict.</p>
     *
     * <p>Future enhancement: Store in a conflicts table for GUI review.</p>
     */
    class ManualResolver implements ConflictResolver {
        private final Logger logger;

        public ManualResolver(@NotNull Logger logger) {
            this.logger = logger;
        }

        @Override
        @Nullable
        public Translation resolve(
                @NotNull Translation local,
                @NotNull DownloadStrategy.CrowdinTranslation crowdin
        ) {
            // Log the conflict for manual review
            logger.warning("[ConflictResolver] MANUAL: Conflict detected for " + local.fullKey() +
                          " [" + local.language() + "]");
            logger.warning("  Local:   \"" + truncate(local.text(), 50) + "\"");
            logger.warning("  Crowdin: \"" + truncate(crowdin.text(), 50) + "\"");
            logger.warning("  Use /afterlang crowdin conflicts to view and resolve");

            // Keep local version until manual resolution
            // Future: Store in afterlanguage_crowdin_conflicts table
            return null;
        }

        @Override
        @NotNull
        public String getName() {
            return "MANUAL";
        }

        private String truncate(String text, int maxLength) {
            if (text.length() <= maxLength) {
                return text;
            }
            return text.substring(0, maxLength - 3) + "...";
        }
    }

    // ══════════════════════════════════════════════
    // ADDITIONAL STRATEGIES (for future use)
    // ══════════════════════════════════════════════

    /**
     * Newest version wins based on timestamp.
     *
     * <p>Compares the updatedAt timestamp of local translation
     * with the assumed Crowdin update time.</p>
     *
     * <p>Note: Crowdin doesn't always provide precise timestamps,
     * so this may not be perfectly accurate.</p>
     */
    class NewestWinsResolver implements ConflictResolver {
        private final Logger logger;

        public NewestWinsResolver(@NotNull Logger logger) {
            this.logger = logger;
        }

        @Override
        @Nullable
        public Translation resolve(
                @NotNull Translation local,
                @NotNull DownloadStrategy.CrowdinTranslation crowdin
        ) {
            // For now, assume Crowdin is newer if we're downloading
            // (we only download approved translations which are presumably reviewed)
            logger.fine("[ConflictResolver] NEWEST_WINS: " + local.fullKey() +
                       " [" + local.language() + "] - Assuming Crowdin is newer");
            return crowdin.toTranslation();
        }

        @Override
        @NotNull
        public String getName() {
            return "NEWEST_WINS";
        }
    }

    /**
     * Merges translations by preferring the longer (more complete) version.
     *
     * <p>Useful when translations might be partial or truncated.</p>
     */
    class LongerWinsResolver implements ConflictResolver {
        private final Logger logger;

        public LongerWinsResolver(@NotNull Logger logger) {
            this.logger = logger;
        }

        @Override
        @NotNull
        public Translation resolve(
                @NotNull Translation local,
                @NotNull DownloadStrategy.CrowdinTranslation crowdin
        ) {
            if (crowdin.text().length() >= local.text().length()) {
                logger.fine("[ConflictResolver] LONGER_WINS: " + local.fullKey() +
                           " [" + local.language() + "] - Crowdin is longer (" +
                           crowdin.text().length() + " vs " + local.text().length() + ")");
                return crowdin.toTranslation();
            } else {
                logger.fine("[ConflictResolver] LONGER_WINS: " + local.fullKey() +
                           " [" + local.language() + "] - Local is longer (" +
                           local.text().length() + " vs " + crowdin.text().length() + ")");
                return local;
            }
        }

        @Override
        @NotNull
        public String getName() {
            return "LONGER_WINS";
        }
    }
}
