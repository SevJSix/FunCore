package me.sevj6.funCore.games.pillars;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PillarsLogicTest {

    @Test
    void parsesTheBannedItemsOption() {
        assertEquals(Set.of("elytra", "ender_pearl", "totem_of_undying"),
                ItemPool.parseBans(" Elytra, minecraft:ender_pearl  totem_of_undying,,"));
        assertTrue(ItemPool.parseBans("").isEmpty());
    }

    @Test
    void thePoolLeavesOutTheBuiltInAndExtraBans() {
        List<String> unknown = new ArrayList<>();
        List<String> pool = ItemPool.build(
                List.of("stone", "diamond_sword", "command_block", "wither_spawn_egg", "ender_chest", "red_bed", "elytra", "zombie_spawn_egg"),
                Set.of("elytra", "not_an_item"), unknown);
        assertEquals(List.of("stone", "diamond_sword", "zombie_spawn_egg"), pool);
        assertEquals(List.of("not_an_item"), unknown, "a typo in banned-items is reported");
    }

    @Test
    void deathMessagesNameTheKillerWhenThereIsOne() {
        assertEquals("<name> was knocked into the void by <killer>.", PillarsGame.deathMessage(null, true, true));
        assertEquals("<name> fell into the void.", PillarsGame.deathMessage(null, true, false));
        assertEquals("<name> was knocked off by <killer> and hit the ground.", PillarsGame.deathMessage(DamageCause.FALL, false, true));
        assertEquals("<name> burned to death.", PillarsGame.deathMessage(DamageCause.LAVA, false, false));
        assertEquals("<name> was blown up by <killer>.", PillarsGame.deathMessage(DamageCause.ENTITY_EXPLOSION, false, true));
        assertFalse(PillarsGame.deathMessage(DamageCause.CRAMMING, false, false).isBlank());
    }
}
