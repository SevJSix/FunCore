package me.sevj6.funCore.arena;

import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobSpawnRulesTest {

    @Test
    void playersMobsAreAllowedAndTheWorldsAreNot() {
        for (String made : new String[]{"SPAWNER_EGG", "EGG", "DISPENSE_EGG", "BUILD_IRONGOLEM", "BREEDING", "BUCKET"}) {
            assertTrue(MobSpawnRules.isPlayerMade(made), made);
        }
        for (String world : new String[]{"NATURAL", "CHUNK_GEN", "SPAWNER", "TRIAL_SPAWNER", "PATROL", "RAID",
                "VILLAGE_INVASION", "JOCKEY", "TRAP", "LIGHTNING", "NETHER_PORTAL", "BEEHIVE", "DEFAULT"}) {
            assertFalse(MobSpawnRules.isPlayerMade(world), world);
        }
    }

    @Test
    void everyListedReasonExists() {
        Set<String> reasons = Arrays.stream(SpawnReason.values()).map(Enum::name).collect(Collectors.toSet());
        Set<String> missing = MobSpawnRules.PLAYER_MADE.stream().filter(name -> !reasons.contains(name)).collect(Collectors.toSet());
        assertEquals(Set.of(), missing, "names in MobSpawnRules that this server version doesn't have");
    }
}
