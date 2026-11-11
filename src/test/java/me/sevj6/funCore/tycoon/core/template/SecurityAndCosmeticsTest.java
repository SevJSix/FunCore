package me.sevj6.funCore.tycoon.core.template;

import me.sevj6.funCore.tycoon.core.TestTemplates;
import me.sevj6.funCore.tycoon.core.data.PlayerData;
import me.sevj6.funCore.tycoon.core.data.PlayerDataCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plot walls, gate lasers and earning cosmetics. */
class SecurityAndCosmeticsTest {

    @Test
    void sampleHasWallsLasersAndEarningCosmetics() {
        Template template = TestTemplates.sample().template();
        // three wall layers, each on top of the one before
        assertTrue(template.component("wall-2").common().requires().contains("wall-1"));
        assertTrue(template.component("wall-3").common().requires().contains("wall-2"));
        // gate lasers: across the 7-wide gate, with the lever just inside
        Template.GateLasers lasers = template.gateLasers();
        assertEquals(new Box(new Offset(17, 1, 44), new Offset(23, 3, 44)), lasers.zone());
        assertEquals(new Offset(16, 1, 43), lasers.lever());
        assertEquals("gate-lasers", template.withFeature("gate-lasers").id());
        // every decoration earns a little on its own
        for (String id : new String[]{"garden-bushes", "vault-shrubs", "hedge-border", "lantern-path", "beehives", "gold-statue"}) {
            assertTrue(template.component(id).common().income() > 0, id + " earns");
        }
        assertEquals(0.0, template.component("wall-1").common().income(), "walls don't");
    }

    @Test
    void incomeIsDollarsASecondKeptAsCents() {
        Template template = TestTemplates.valid(TestTemplates.simple("""
                fountain:
                  type: wall
                  price: 10
                  pad: [5, 1, 5]
                  income: 2.5
                """));
        assertEquals(250.0, template.component("fountain").common().income(), 1e-9);
    }

    @Test
    void lasersNeedTheirPlaceInTheBase() {
        var result = TestTemplates.loadYaml(TestTemplates.simple("""
                lasers:
                  type: wall
                  pad: [5, 1, 5]
                  feature: gate-lasers
                """));
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("no gate-lasers section")), result.errors()::toString);
    }

    @Test
    void unknownFeaturesAreRefused() {
        var result = TestTemplates.loadYaml(TestTemplates.simple("""
                odd:
                  type: wall
                  pad: [5, 1, 5]
                  feature: teleporter
                """));
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("teleporter")), result.errors()::toString);
    }

    @Test
    void theLeverPositionIsSaved() {
        PlayerData data = new PlayerData();
        assertTrue(data.gateLocked(), "lasers start on");
        data.setGateLocked(false);
        assertFalse(PlayerDataCodec.fromMap(PlayerDataCodec.toMap(data)).gateLocked());
        assertTrue(PlayerDataCodec.fromMap(java.util.Map.of()).gateLocked(), "old saves: on");
    }
}
