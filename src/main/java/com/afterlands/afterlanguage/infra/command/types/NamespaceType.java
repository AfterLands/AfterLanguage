package com.afterlands.afterlanguage.infra.command.types;

import com.afterlands.afterlanguage.core.resolver.NamespaceManager;
import com.afterlands.core.commands.parser.ArgumentType;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Argument type for namespace tab-completion.
 *
 * <p>
 * Suggests registered namespaces from the NamespaceManager.
 * Parse is permissive (accepts any string) since validation
 * happens in the command handler.
 * </p>
 */
public class NamespaceType implements ArgumentType<String> {

    private final NamespaceManager namespaceManager;

    public NamespaceType(@NotNull NamespaceManager namespaceManager) {
        this.namespaceManager = namespaceManager;
    }

    @Override
    @NotNull
    public String parse(@NotNull ParseContext ctx, @NotNull String input) throws ParseException {
        return input;
    }

    @Override
    @NotNull
    public List<String> suggest(@NotNull CommandSender sender, @NotNull String partial) {
        Set<String> namespaces = namespaceManager.getRegisteredNamespaces();
        String lowerPartial = partial.toLowerCase();
        List<String> result = new ArrayList<>();

        for (String ns : namespaces) {
            if (ns.toLowerCase().startsWith(lowerPartial)) {
                result.add(ns);
            }
        }
        return result;
    }

    @Override
    @NotNull
    public String typeName() {
        return "namespace";
    }
}
