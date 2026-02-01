package com.afterlands.afterlanguage.infra.persistence;

import com.afterlands.afterlanguage.api.model.Translation;
import com.afterlands.core.database.SqlDataSource;
import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Repository for dynamically created translations (stub for MVP).
 *
 * <p>For MVP, dynamic translations are optional. This is a minimal stub
 * that can be expanded later for full admin translation management.</p>
 */
public class DynamicTranslationRepository {

    private final SqlDataSource dataSource;
    private final Logger logger;
    private final String tableName;
    private final boolean debug;

    public DynamicTranslationRepository(
            @NotNull SqlDataSource dataSource,
            @NotNull String tableName,
            @NotNull Logger logger,
            boolean debug
    ) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Gets all dynamic translations for a namespace.
     */
    @NotNull
    public CompletableFuture<List<Translation>> getNamespace(@NotNull String namespace) {
        return dataSource.supplyAsync(conn -> {
            String sql = "SELECT namespace, key_path, language, text, updated_at " +
                        "FROM " + tableName + " WHERE namespace = ?";
            List<Translation> translations = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, namespace);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        translations.add(Translation.of(
                                rs.getString("namespace"),
                                rs.getString("key_path"),
                                rs.getString("language"),
                                rs.getString("text")
                        ));
                    }
                }
            }

            return translations;
        });
    }

    /**
     * Saves a dynamic translation (stub - not implemented for MVP).
     */
    @NotNull
    public CompletableFuture<Void> save(@NotNull Translation translation) {
        // TODO: Implement for full version
        return CompletableFuture.completedFuture(null);
    }
}
