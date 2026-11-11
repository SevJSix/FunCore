package me.sevj6.funCore.tycoon.core.template;

import me.sevj6.funCore.tycoon.core.TestTemplates;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateValidatorTest {

    /** Asserts the template is refused with an error mentioning every one of {@code parts}. */
    private static void refused(String extraComponents, String... parts) {
        var result = TestTemplates.loadYaml(TestTemplates.simple(extraComponents));
        assertFalse(result.ok(), "expected errors");
        List<String> errors = result.errors();
        assertTrue(errors.stream().anyMatch(error -> {
            for (String part : parts) {
                if (!error.contains(part)) return false;
            }
            return true;
        }), () -> "no error mentions " + List.of(parts) + ": " + errors);
    }

    @Test
    void startWithMustIncludeItsPrerequisites() {
        var result = TestTemplates.loadYaml("""
                size: [32, 16, 32]
                base:
                  spawn: [1, 1, 1]
                  collectors: { main: [10, 1, 0] }
                start-with: [dropper]
                components:
                  belt:
                    type: conveyor
                    pad: [5, 1, 5]
                    speed: 2
                    path: [[0, 1, 0], [8, 1, 0]]
                    collector: main
                  dropper:
                    type: dropper
                    requires: [belt]
                    spout: [0, 3, 0]
                    interval: 20
                    value: 1
                    route: { belt: belt, waypoint: 0 }
                """);
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("start-with") && e.contains("'belt'")), result.errors()::toString);
    }

    @Test
    void unknownPrerequisite() {
        refused("""
                wall:
                  type: wall
                  pad: [5, 1, 5]
                  requires: [ghost]
                """, "wall 'wall'", "requires 'ghost'");
    }

    @Test
    void prerequisiteCycle() {
        refused("""
                a:
                  type: wall
                  pad: [5, 1, 5]
                  requires: [b]
                b:
                  type: wall
                  pad: [6, 1, 5]
                  requires: [a]
                """, "circle");
    }

    @Test
    void dropperOnABeltItDoesNotRequire() {
        refused("""
                second-belt:
                  type: conveyor
                  pad: [5, 1, 5]
                  speed: 1
                  path: [[1, 1, 4], [1, 1, 8]]
                  collector: main
                lonely:
                  type: dropper
                  pad: [6, 1, 5]
                  spout: [1, 3, 4]
                  interval: 20
                  value: 1
                  route: { belt: second-belt, waypoint: 0 }
                """, "dropper 'lonely'", "second-belt", "doesn't require");
    }

    @Test
    void dropperCoveredTransitivelyIsFine() {
        TestTemplates.valid(TestTemplates.simple("""
                second:
                  type: dropper
                  requires: [dropper]
                  pad: [6, 1, 5]
                  spout: [4, 3, 0]
                  interval: 20
                  value: 2
                  route: { belt: belt, waypoint: 1 }
                """));
    }

    @Test
    void dropperRouteThroughAMergedBeltMustBeCovered() {
        // 'feeder' merges into 'belt': a dropper on the feeder needs both, and 'feeder' only brings 'belt' if it
        // requires it.
        refused("""
                feeder:
                  type: conveyor
                  pad: [5, 1, 5]
                  speed: 1
                  path: [[4, 1, 6], [4, 1, 1]]
                  merge: { belt: belt, waypoint: 1 }
                fed:
                  type: dropper
                  requires: [feeder]
                  pad: [6, 1, 5]
                  spout: [4, 3, 6]
                  interval: 20
                  value: 1
                  route: { belt: feeder, waypoint: 0 }
                """, "dropper 'fed'", "belt 'belt'");
    }

    @Test
    void vaultComponentOnAMainBelt() {
        refused("""
                vault-thing:
                  type: dropper
                  zone: vault
                  requires: [belt]
                  pad: [6, 1, 5]
                  spout: [4, 3, 0]
                  interval: 20
                  value: 1
                  route: { belt: belt, waypoint: 1 }
                """, "dropper 'vault-thing'", "vault", "main-zone belt 'belt'");
    }

    @Test
    void overlappingPads() {
        refused("""
                a:
                  type: wall
                  pad: [5, 1, 5]
                b:
                  type: wall
                  pad: [5, 1, 5]
                """, "wall 'b'", "same block");
    }

    @Test
    void componentWithoutAPad() {
        refused("""
                padless:
                  type: wall
                """, "wall 'padless'", "no pad");
    }

    @Test
    void mergeLoop() {
        refused("""
                x:
                  type: conveyor
                  pad: [5, 1, 5]
                  speed: 1
                  path: [[1, 1, 9]]
                  merge: { belt: y, waypoint: 0 }
                y:
                  type: conveyor
                  pad: [6, 1, 5]
                  speed: 1
                  path: [[2, 1, 9]]
                  merge: { belt: x, waypoint: 0 }
                """, "loop");
    }

    @Test
    void mergeOntoAWaypointThatDoesNotExist() {
        refused("""
                short:
                  type: conveyor
                  requires: [belt]
                  pad: [5, 1, 5]
                  speed: 1
                  path: [[1, 1, 9]]
                  merge: { belt: belt, waypoint: 7 }
                """, "belt 'short'", "waypoint 7");
    }

    @Test
    void unknownCollector() {
        refused("""
                lost:
                  type: conveyor
                  pad: [5, 1, 5]
                  speed: 1
                  path: [[1, 1, 9]]
                  collector: nowhere
                """, "belt 'lost'", "collector 'nowhere'");
    }

    @Test
    void upgraderOnAMissingWaypoint() {
        refused("""
                up:
                  type: upgrader
                  requires: [belt]
                  pad: [5, 1, 5]
                  at: { belt: belt, waypoint: 9 }
                  multiplier: 2
                """, "upgrader 'up'", "waypoint 9");
    }

    // ------------------------------------------------------------------------------------------------------------
    // The map's plot: footprint, barriers, beds
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void padOutsideTheFootprint() {
        refused("""
                far:
                  type: wall
                  pad: [40, 1, 5]
                """, "wall 'far'", "pad", "outside the plot");
    }

    @Test
    void partOutsideTheFootprint() {
        refused("""
                big:
                  type: wall
                  pad: [5, 1, 5]
                  parts:
                    - { block: "minecraft:stone", at: [30, 1, 30], size: [4, 1, 1] }
                """, "wall 'big'", "part", "outside the plot");
    }

    @Test
    void outsideTrueAllowsIt() {
        TestTemplates.valid(TestTemplates.simple("""
                walkway:
                  type: wall
                  outside: true
                  pad: [5, 1, 5]
                  parts:
                    - { block: "minecraft:stone", at: [30, 1, 40], size: [1, 1, 1] }
                  barriers: [[30, 1, 40]]
                """));
    }

    @Test
    void barrierOnAPad() {
        refused("""
                a:
                  type: wall
                  pad: [5, 1, 5]
                b:
                  type: wall
                  pad: [6, 1, 5]
                  barriers: [[5, 1, 5]]
                """, "wall 'b'", "barrier", "the pad of wall 'a'");
    }

    @Test
    void barrierOnTheSpawn() {
        // the spawn is [1, 1, 1]: its block and the one above are where the player stands
        refused("""
                block:
                  type: wall
                  pad: [5, 1, 5]
                  barriers: [[1, 2, 1]]
                """, "wall 'block'", "the spawn");
    }

    @Test
    void twoComponentsShareABarrier() {
        refused("""
                a:
                  type: wall
                  pad: [5, 1, 5]
                  barriers: [[9, 1, 9]]
                b:
                  type: wall
                  pad: [6, 1, 5]
                  barriers: [[9, 1, 9]]
                """, "wall 'b'", "a barrier of wall 'a'");
    }

    @Test
    void beltsMustRunOnBeds() {
        // the simple template's belt runs x 0-8 at z 0, into the collector at x 10
        String yaml = TestTemplates.simple("").replace("  collectors: { main: [10, 1, 0] }",
                "  collectors: { main: [10, 1, 0] }\n  beds:\n    - { from: [0, 1, 0], to: [10, 1, 0] }");
        assertTrue(TestTemplates.loadYaml(yaml).ok(), () -> "on the bed: " + TestTemplates.loadYaml(yaml).errors());

        String shortBed = yaml.replace("to: [10, 1, 0]", "to: [6, 1, 0]");
        var result = TestTemplates.loadYaml(shortBed);
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("conveyor 'belt'") && e.contains("[7, 1, 0]")
                && e.contains("isn't on a belt bed")), result.errors()::toString);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("collector 'main'")), result.errors()::toString);
    }
}
