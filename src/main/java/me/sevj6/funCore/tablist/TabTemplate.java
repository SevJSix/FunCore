package me.sevj6.funCore.tablist;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a line of tab list text into a component: MiniMessage, with FunCore's {@code {placeholders}} and
 * PlaceholderAPI's {@code %placeholders%} filled in. Filled-in values are never read as MiniMessage, so a value can't
 * change the formatting around it; a PlaceholderAPI value with legacy colour codes ({@code §a}, {@code &a},
 * {@code &#ff8800}) keeps its colours.
 */
final class TabTemplate {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    /** PlaceholderAPI's form: {@code %identifier_params%}, no spaces. */
    private static final Pattern PAPI = Pattern.compile("%[^%\\s]+%");
    private static final Pattern BUILT_IN = Pattern.compile("\\{([a-z_]+)}");
    private static final Pattern AMPERSAND_CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR]|#[0-9a-fA-F]{6})");
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR).hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.AMPERSAND_CHAR).hexColors().build();

    private TabTemplate() {
    }

    /**
     * @param values FunCore's placeholders, by name without braces ({@code ping} for {@code {ping}}); unknown ones stay
     *               as written
     * @param papi   fills in one {@code %placeholder%} (given with its percent signs), or {@code null} without
     *               PlaceholderAPI (they stay as written)
     */
    static Component render(String template, Map<String, Component> values, @Nullable UnaryOperator<String> papi) {
        List<TagResolver> resolvers = new ArrayList<>();
        String text = template;
        if (papi != null && text.indexOf('%') >= 0) {
            Map<String, String> tags = new LinkedHashMap<>();
            Matcher matcher = PAPI.matcher(text);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String token = matcher.group();
                String tag = tags.get(token);
                if (tag == null) {
                    tag = "fc_papi_" + tags.size();
                    tags.put(token, tag);
                    resolvers.add(Placeholder.component(tag, legacy(papi.apply(token))));
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement("<" + tag + ">"));
            }
            matcher.appendTail(out);
            text = out.toString();
        }
        if (text.indexOf('{') >= 0) {
            Matcher matcher = BUILT_IN.matcher(text);
            StringBuilder out = new StringBuilder();
            List<String> used = new ArrayList<>();
            while (matcher.find()) {
                String name = matcher.group(1);
                Component value = values.get(name);
                if (value == null) {
                    matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                    continue;
                }
                if (!used.contains(name)) {
                    used.add(name);
                    resolvers.add(Placeholder.component("fc_" + name, value));
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement("<fc_" + name + ">"));
            }
            matcher.appendTail(out);
            text = out.toString();
        }
        return MINI_MESSAGE.deserialize(text, TagResolver.resolver(resolvers));
    }

    /** Several lines, one under another. */
    static Component lines(List<String> templates, Map<String, Component> values, @Nullable UnaryOperator<String> papi) {
        Component result = Component.empty();
        for (int i = 0; i < templates.size(); i++) {
            if (i > 0) result = result.append(Component.newline());
            result = result.append(render(templates.get(i), values, papi));
        }
        return result;
    }

    /** A PlaceholderAPI value as a component, its legacy colour codes (if any) kept. */
    static Component legacy(@Nullable String value) {
        if (value == null || value.isEmpty()) return Component.empty();
        if (value.indexOf(LegacyComponentSerializer.SECTION_CHAR) >= 0) return SECTION.deserialize(value);
        if (AMPERSAND_CODE.matcher(value).find()) return AMPERSAND.deserialize(value);
        return Component.text(value);
    }
}
