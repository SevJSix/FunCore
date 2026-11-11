package me.sevj6.funCore.tycoon.core.template;

import me.sevj6.funCore.tycoon.core.TestTemplates;
import org.junit.jupiter.api.Test;

import java.util.List;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateLoaderTest {

    @Test
    void sampleTemplateIsValid() {
        var result = TestTemplates.sample();
        assertTrue(result.ok(), () -> "sample template errors: " + result.errors());
        Template template = result.template();
        // The map's plot (see config.yml): 41 x 45, spawn on the sea lantern facing north, items cashed in just
        // inside the collector house and at the end of the vault bed.
        assertEquals(new Offset(41, 22, 45), template.size());
        assertEquals(new Offset(20, 1, 40), template.spawn());
        assertEquals(180f, template.spawnYaw());
        assertEquals(new Vec(35.5, 1.0, 30.5), template.collectors().get("main").top());
        assertEquals(new Vec(24.5, 1.0, 8.5), template.collectors().get("vault").top());
        // Nine tiers on the nine footings, cheapest at the collector end, each pad on its socket.
        List<Component.Dropper> main = template.droppers().stream()
                .filter(d -> d.common().zone() == Component.Zone.MAIN).toList();
        assertEquals(9, main.size());
        int[] footings = {29, 26, 23, 20, 17, 14, 11, 8, 5};
        long lastPrice = -1;
        for (int i = 0; i < 9; i++) {
            Component.Dropper dropper = main.get(i);
            assertEquals(footings[i], dropper.spout().x(), dropper.id());
            assertEquals(30, dropper.spout().z(), dropper.id() + " drops onto the belt bed");
            assertTrue(dropper.common().price() > lastPrice, "tiers get pricier: " + dropper.id());
            lastPrice = dropper.common().price();
            if (i > 0) assertEquals(new Offset(footings[i], 1, 34), dropper.common().pad(), dropper.id());
            assertEquals(12, dropper.common().barriers().size(), "a 2x3x2 housing on the footing: " + dropper.id());
        }
        assertEquals(2, template.upgraders().size());
        assertEquals(5, template.trophySlots().size());
        assertEquals(new Offset(34, 1, 40), template.rebirthPad());
        assertEquals(Component.Zone.VAULT, template.component("super-dropper").common().zone());
    }

    @Test
    void moneyIsReadAsDollarsAndKeptAsCents() {
        Template template = TestTemplates.valid(TestTemplates.simple("""
                extra:
                  type: wall
                  price: 12.5
                  pad: [5, 1, 5]
                """));
        assertEquals(1250, template.component("extra").common().price());
        assertEquals(100.0, ((Component.Dropper) template.component("dropper")).value(), 1e-9);
    }

    @Test
    void readsBeltEnds() {
        Template template = TestTemplates.valid(TestTemplates.simple("""
                side:
                  type: conveyor
                  price: 1
                  pad: [5, 1, 5]
                  speed: 1
                  path: [[4, 1, 8], [4, 1, 1]]
                  merge: { belt: belt, waypoint: 1 }
                """));
        Belt side = template.belt("side");
        assertInstanceOf(Belt.Merge.class, side.end());
        assertEquals(new Belt.Merge("belt", 1), side.end());
        assertEquals(new Belt.ToCollector("main"), template.belt("belt").end());
    }

    @Test
    void reportsWhereEachProblemIs() {
        var result = TestTemplates.loadYaml("""
                size: [10, 10]
                base:
                  spawn: [1, 1, 1]
                  collectors: { main: [5, 1, 5] }
                components:
                  broken:
                    type: dropper
                    pad: [2, 1, 2]
                    interval: 0
                    value: -1
                    route: { belt: nowhere }
                  odd:
                    type: teleporter
                """);
        assertFalse(result.ok());
        List<String> errors = result.errors();
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("size")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("component 'broken'") && e.contains("interval")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("component 'broken'") && e.contains("value")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("component 'broken' spout")), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.contains("component 'odd'") && e.contains("teleporter")), errors::toString);
    }

    @Test
    void beltNeedsExactlyOneEnd() {
        var result = TestTemplates.loadYaml(TestTemplates.simple("""
                both:
                  type: conveyor
                  pad: [5, 1, 5]
                  speed: 1
                  path: [[1, 1, 1]]
                  collector: main
                  merge: { belt: belt, waypoint: 0 }
                """));
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("'both'") && e.contains("exactly one")), result.errors()::toString);
    }

    @Test
    void readsPartsAndBarriers() {
        Template template = TestTemplates.valid(TestTemplates.simple("""
                fancy:
                  type: wall
                  pad: [5, 1, 5]
                  parts:
                    - { block: "minecraft:stone", at: [1, 1, 1], size: [2, 0.5, 2] }
                    - { item: "minecraft:diamond", at: [1.5, 2.5, 1.5] }
                  barriers:
                    - [9, 1, 9]
                    - { from: [1, 1, 1], to: [2, 2, 2] }
                """));
        Component fancy = template.component("fancy");
        List<Part> parts = fancy.common().parts();
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).block());
        assertEquals(new Vec(2, 0.5, 2), parts.get(0).size());
        assertFalse(parts.get(1).block());
        assertEquals(new Vec(1, 1, 1), parts.get(1).size(), "size defaults to a full block");
        assertEquals(1 + 8, fancy.common().barriers().size(), "a single block and a 2x2x2 box");
        assertEquals(parts, Placeholders.of(template, fancy, 0), "listed parts are used instead of placeholders");
    }

    @Test
    void refusesBadParts() {
        var result = TestTemplates.loadYaml(TestTemplates.simple("""
                broken:
                  type: wall
                  pad: [5, 1, 5]
                  parts:
                    - { block: "minecraft:stone", item: "minecraft:stone", at: [1, 1, 1] }
                    - { block: "minecraft:stone", at: [1, 1, 1], size: [0, 1, 1] }
                """));
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("parts[0]") && e.contains("exactly one")), result.errors()::toString);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("parts[1]") && e.contains("size")), result.errors()::toString);
    }
}
