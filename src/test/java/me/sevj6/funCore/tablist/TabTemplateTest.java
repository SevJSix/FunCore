package me.sevj6.funCore.tablist;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TabTemplateTest {

    private static final Map<String, Component> VALUES = Map.of(
            "player", Component.text("Steve", NamedTextColor.RED),
            "ping", Component.text(42));

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void fillsInFunCorePlaceholders() {
        assertEquals("Steve · 42 ms, {unknown}", plain(TabTemplate.render("<gray>{player} · {ping} ms, {unknown}", VALUES, null)));
    }

    @Test
    void valuesAreNotReadAsMiniMessage() {
        Map<String, Component> values = Map.of("player", Component.text("<red>x</red>"));
        assertEquals("<red>x</red>", plain(TabTemplate.render("{player}", values, null)));
    }

    @Test
    void placeholderApiIsOptional() {
        assertEquals("HP %player_health%", plain(TabTemplate.render("HP %player_health%", VALUES, null)));
        assertEquals("HP 20 / 20, 100% sure",
                plain(TabTemplate.render("HP %player_health% / %player_health%, 100% sure", VALUES,
                        token -> token.equals("%player_health%") ? "20" : token)));
    }

    @Test
    void placeholderApiValuesKeepLegacyColours() {
        Component section = TabTemplate.legacy("§aGreen");
        assertEquals("Green", plain(section));
        assertEquals(NamedTextColor.GREEN, section.children().isEmpty() ? section.color() : section.children().getFirst().color());
        Component hex = TabTemplate.legacy("&#ff8800Orange");
        assertEquals("Orange", plain(hex));
        assertEquals(TextColor.fromHexString("#ff8800"), hex.children().isEmpty() ? hex.color() : hex.children().getFirst().color());
        assertEquals(Component.text("Tom & Jerry"), TabTemplate.legacy("Tom & Jerry"), "an & that isn't a code stays");
    }

    @Test
    void joinsLines() {
        assertEquals("a\n\nb", plain(TabTemplate.lines(List.of("a", "", "b"), VALUES, null)));
    }
}
