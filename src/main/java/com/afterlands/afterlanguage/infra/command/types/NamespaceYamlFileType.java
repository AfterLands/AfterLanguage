package com.afterlands.afterlanguage.infra.command.types;

import com.afterlands.afterlanguage.core.resolver.NamespaceManager;
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
 * Argument type for YAML files within a namespace directory.
 *
 * <p>Suggests YAML files (without extension) found in the namespace's language directories.
 * Requires namespace and language to be already parsed from previous arguments.</p>
 */
public class NamespaceYamlFileType implements ArgumentType<String> {

    private final NamespaceManager namespaceManager;

    public NamespaceYamlFileType(@NotNull NamespaceManager namespaceManager) {
        this.namespaceManager = namespaceManager;
    }

    @Override
    @NotNull
    public String parse(@NotNull ParseContext ctx, @NotNull String input) throws ParseException {
        // Just return the input, validation happens in command
        return input;
    }

    @Override
    @NotNull
    public List<String> suggest(@NotNull CommandSender sender, @NotNull String partial) {
        // No suggestions - user types the target filename freely
        return Collections.emptyList();
    }

    @Override
    @NotNull
    public String typeName() {
        return "yamlFile";
    }

    /**
     * Gets YAML files for a specific namespace and language.
     *
     * @param namespace Namespace
     * @param language Language code
     * @return List of file names (without .yml extension)
     */
    @NotNull
    public List<String> getFilesForNamespace(@NotNull String namespace, @NotNull String language) {
        Path nsDir = namespaceManager.getLanguagesDir().resolve(language).resolve(namespace);

        if (!Files.isDirectory(nsDir)) {
            return Collections.emptyList();
        }

        List<String> files = new ArrayList<>();
        try (Stream<Path> paths = Files.list(nsDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml") ||
                                 p.getFileName().toString().endsWith(".yaml"))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        // Remove extension
                        fileName = fileName.replaceFirst("\\.ya?ml$", "");
                        files.add(fileName);
                    });
        } catch (IOException ignored) {
        }

        return files;
    }
}
