package com.afterlands.afterlanguage.infra.command.types;

import com.afterlands.core.commands.parser.ArgumentType;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Argument type for import file tab-completion.
 *
 * <p>Suggests YAML files found in the {@code plugins/AfterLanguage/imports/} directory.
 * Scans recursively so files in subdirectories are also listed.</p>
 */
public class ImportFileType implements ArgumentType<String> {

    private final Path importsDir;

    public ImportFileType(@NotNull Path importsDir) {
        this.importsDir = importsDir;
    }

    @Override
    @NotNull
    public String parse(@NotNull ParseContext ctx, @NotNull String input) throws ParseException {
        return input;
    }

    @Override
    @NotNull
    public List<String> suggest(@NotNull CommandSender sender, @NotNull String partial) {
        if (!Files.isDirectory(importsDir)) {
            return Collections.emptyList();
        }

        List<String> suggestions = new ArrayList<>();
        String lowerPartial = partial.toLowerCase();

        try (Stream<Path> files = Files.walk(importsDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml") ||
                                 p.getFileName().toString().endsWith(".yaml"))
                    .forEach(p -> {
                        // Relative path from imports dir (e.g. "afterlanguage_pt_br_2026-02-11.yml")
                        String relativePath = importsDir.relativize(p).toString().replace('\\', '/');
                        if (relativePath.toLowerCase().startsWith(lowerPartial)) {
                            suggestions.add(relativePath);
                        }
                    });
        } catch (IOException ignored) {
            // Directory not readable, return empty
        }

        return suggestions;
    }

    @Override
    @NotNull
    public String typeName() {
        return "importFile";
    }
}
