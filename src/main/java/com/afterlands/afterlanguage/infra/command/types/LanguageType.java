package com.afterlands.afterlanguage.infra.command.types;

import com.afterlands.core.commands.parser.ArgumentType;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Argument type for language code tab-completion.
 *
 * <p>
 * Suggests enabled language codes from the plugin config
 * (e.g., pt_br, en_us, es_es). Parse is permissive.
 * </p>
 */
public class LanguageType implements ArgumentType<String> {

    private final FileConfiguration config;

    public LanguageType(@NotNull FileConfiguration config) {
        this.config = config;
    }

    @Override
    @NotNull
    public String parse(@NotNull ParseContext ctx, @NotNull String input) throws ParseException {
        return input;
    }

    @Override
    @NotNull
    public List<String> suggest(@NotNull CommandSender sender, @NotNull String partial) {
        List<String> languages = config.getStringList("enabled-languages");
        String lowerPartial = partial.toLowerCase();
        List<String> result = new ArrayList<>();

        for (String lang : languages) {
            if (lang.toLowerCase().startsWith(lowerPartial)) {
                result.add(lang);
            }
        }
        return result;
    }

    @Override
    @NotNull
    public String typeName() {
        return "language";
    }
}
