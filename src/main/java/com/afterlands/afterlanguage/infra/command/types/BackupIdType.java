package com.afterlands.afterlanguage.infra.command.types;

import com.afterlands.afterlanguage.core.io.TranslationBackupService;
import com.afterlands.afterlanguage.core.io.TranslationBackupService.BackupInfo;
import com.afterlands.core.commands.parser.ArgumentType;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Argument type for backup ID tab-completion.
 *
 * <p>
 * Suggests available backup IDs from the TranslationBackupService.
 * Uses a cached list (refreshed every 30 seconds) since backup listing
 * is asynchronous.
 * </p>
 */
public class BackupIdType implements ArgumentType<String> {

    private static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(30);

    private final TranslationBackupService backupService;

    private volatile List<String> cachedIds = Collections.emptyList();
    private volatile long lastRefresh = 0;

    public BackupIdType(@NotNull TranslationBackupService backupService) {
        this.backupService = backupService;
    }

    @Override
    @NotNull
    public String parse(@NotNull ParseContext ctx, @NotNull String input) throws ParseException {
        return input;
    }

    @Override
    @NotNull
    public List<String> suggest(@NotNull CommandSender sender, @NotNull String partial) {
        refreshCacheIfNeeded();

        String lowerPartial = partial.toLowerCase();
        List<String> result = new ArrayList<>();

        for (String id : cachedIds) {
            if (id.toLowerCase().startsWith(lowerPartial)) {
                result.add(id);
            }
        }
        return result;
    }

    @Override
    @NotNull
    public String typeName() {
        return "backupId";
    }

    private void refreshCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < CACHE_TTL_MS) {
            return;
        }

        lastRefresh = now;

        // Fire async and update cache when done
        backupService.listBackups().thenAccept(backups -> {
            List<String> ids = new ArrayList<>(backups.size());
            for (BackupInfo info : backups) {
                ids.add(info.backupId());
            }
            cachedIds = ids;
        });
    }
}
