package me.sevj6.funCore.tycoon.core.template;

import me.sevj6.funCore.tycoon.core.TestTemplates;
import me.sevj6.funCore.tycoon.core.data.PlayerData;
import me.sevj6.funCore.tycoon.core.data.PlayerDataCodec;
import me.sevj6.funCore.tycoon.core.sim.Routes;
import me.sevj6.funCore.tycoon.core.sim.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ore vein and its pickaxe, belt speed upgrades, and the running belt surface. */
class LadderTest {

    @Test
    void sampleHasTheOreVeinAndBeltSpeed() {
        Template template = TestTemplates.sample().template();
        Template.Manual vein = template.manual();
        assertEquals("Ore Vein", vein.name());
        assertEquals(15.0, vein.valueAt(0), 1e-9, "15 cents a click to start");
        assertEquals(9, vein.upgrades().maxLevel(), "nine pickaxe levels");
        for (int level = 1; level <= 9; level++) {
            assertTrue(vein.valueAt(level) > vein.valueAt(level - 1), "each pickaxe is worth more: level " + level);
        }
        assertEquals(5, template.beltSpeed().maxLevel());
        assertEquals(1.0, template.beltSpeed().amount(0, 1.0));
        assertEquals(4.5, template.beltSpeed().amount(5, 1.0));
        assertNull(template.beltSpeed().next(5), "maxed");
    }

    @Test
    void coalIsCheapAndCopperCostsAHundred() {
        Template template = TestTemplates.sample().template();
        Component.Dropper coal = (Component.Dropper) template.component("wooden-dropper");
        assertEquals(40, coal.interval(), "a coal every 2 s, as before");
        assertEquals(20.0, coal.value(), 1e-9, "worth 20 cents: 5x less than the original dollar");
        assertEquals(100_00, template.component("copper-belt").common().price());
        assertEquals(100_00, template.component("copper-dropper").common().price());
    }

    @Test
    void fasterBeltsMeanShorterRoutes() {
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        Component.Dropper dropper = (Component.Dropper) template.component("dropper");
        List<Segment> normal = Routes.of(template, dropper, 1.0);
        List<Segment> fast = Routes.of(template, dropper, 2.0);
        // the 4-block straight at 2 blocks a second: 40 ticks, then 20 at double speed (the drop isn't a belt)
        assertEquals(40, normal.get(1).ticks());
        assertEquals(20, fast.get(1).ticks());
        assertEquals(normal.get(0).ticks(), fast.get(0).ticks());
    }

    @Test
    void ladderLevelsMustClimb() {
        var result = TestTemplates.loadYaml(TestTemplates.simple("").replace("  collectors: { main: [10, 1, 0] }",
                "  collectors: { main: [10, 1, 0] }\n  belt-speed:\n    pad: [7, 1, 7]\n    levels:\n"
                        + "      - { price: 100, speed: 2 }\n      - { price: 50, speed: 1.5 }"));
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("costs less")), result.errors()::toString);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("isn't more")), result.errors()::toString);
    }

    @Test
    void ladderPadCantShareABlockWithAComponentPad() {
        var result = TestTemplates.loadYaml(TestTemplates.simple("""
                wall:
                  type: wall
                  pad: [7, 1, 7]
                """).replace("  collectors: { main: [10, 1, 0] }",
                "  collectors: { main: [10, 1, 0] }\n  belt-speed:\n    pad: [7, 1, 7]\n    levels:\n      - { price: 100, speed: 2 }"));
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("same block")), result.errors()::toString);
    }

    @Test
    void beltSurfaceSlidesTheWayItemsGo() {
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        List<Part> parts = Placeholders.belt(template, template.belt("belt"));
        assertFalse(parts.isEmpty());
        for (Part part : parts) {
            if (part.slide() != null) assertEquals(new Vec(2, 0, 0), part.slide(), "east, at the belt's 2 blocks a second");
        }
        assertTrue(Placeholders.of(template, template.component("belt"), 0).isEmpty(),
                "a conveyor's surface is laid out with the plot's other belts, not on its own");
    }

    @Test
    void levelsAreSaved() {
        PlayerData data = new PlayerData();
        data.setManualLevel(4);
        data.setSpeedLevel(2);
        PlayerData back = PlayerDataCodec.fromMap(PlayerDataCodec.toMap(data));
        assertEquals(4, back.manualLevel());
        assertEquals(2, back.speedLevel());
    }

    @Test
    void romanNumerals() {
        assertEquals("I", Ladder.roman(1));
        assertEquals("IV", Ladder.roman(4));
        assertEquals("IX", Ladder.roman(9));
        assertEquals("X", Ladder.roman(10));
    }
}
