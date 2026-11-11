package me.sevj6.funCore.tycoon.core.template;

import me.sevj6.funCore.tycoon.core.TestTemplates;
import me.sevj6.funCore.tycoon.core.data.PlayerData;
import me.sevj6.funCore.tycoon.core.data.PlayerDataCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarShowerTest {

    @Test
    void sampleHasFiveLevels() {
        StarShower stars = TestTemplates.sample().template().starShower();
        assertEquals(new Offset(17, 1, 38), stars.pad());
        assertEquals(500, stars.cap());
        assertEquals(5, stars.levels().size());
        assertNull(stars.at(0), "no stars before the first level");
        assertEquals(stars.at(5), stars.at(9), "past the top is the top");
        for (int level = 2; level <= 5; level++) {
            assertTrue(stars.at(level).value() > stars.at(level - 1).value(), "each level's stars are worth more");
            assertTrue(stars.at(level).every() < stars.at(level - 1).every(), "and fall more often");
        }
        assertEquals(20, stars.at(1).every(), "seconds in the file (1), ticks in the template");
        assertEquals(stars.at(1).value(), stars.ladder().next(0).amount(), "the pad shows the first level next");
    }

    @Test
    void theLevelIsSaved() {
        PlayerData data = new PlayerData();
        data.setStarLevel(3);
        assertEquals(3, PlayerDataCodec.fromMap(PlayerDataCodec.toMap(data)).starLevel());
        assertEquals(0, PlayerDataCodec.fromMap(Map.of()).starLevel());
    }

    @Test
    void needsSomewhereToLand() {
        var result = TestTemplates.loadYaml(TestTemplates.simple("").replace("  collectors: { main: [10, 1, 0] }",
                "  collectors: { main: [10, 1, 0] }\n  star-shower:\n    pad: [7, 1, 7]\n    levels:\n      - { price: 1, value: 1, every: 5 }"));
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("needs at least one area")), result.errors()::toString);
    }

    @Test
    void itsPadCantShareABlock() {
        var result = TestTemplates.loadYaml(TestTemplates.simple("""
                wall:
                  type: wall
                  pad: [7, 1, 7]
                """).replace("  collectors: { main: [10, 1, 0] }", "  collectors: { main: [10, 1, 0] }\n  star-shower:\n"
                + "    pad: [7, 1, 7]\n    areas: [{ from: [1, 1, 20], to: [5, 1, 25] }]\n    levels:\n      - { price: 1, value: 1, every: 5 }"));
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Star Shower pad") && e.contains("same block")), result.errors()::toString);
        assertTrue(List.of(result.errors()).size() > 0);
    }
}
