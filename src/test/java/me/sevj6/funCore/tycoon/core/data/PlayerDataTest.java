package me.sevj6.funCore.tycoon.core.data;

import me.sevj6.funCore.tycoon.core.sim.Cents;
import me.sevj6.funCore.tycoon.core.template.Component;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataTest {

    @Test
    void roundTripsThroughYaml() {
        PlayerData data = new PlayerData();
        data.earn(123_456);
        data.spend(456);
        data.setRebirths(3);
        data.own("copper-belt", Component.Zone.MAIN);
        data.own("super-dropper", Component.Zone.VAULT);
        data.unlockTrophy("first-rebirth", 1_762_800_000_000L);

        Yaml yaml = new Yaml();
        PlayerData back = PlayerDataCodec.fromMap(yaml.load(yaml.dump(PlayerDataCodec.toMap(data))));
        assertEquals(123_000, back.balance());
        assertEquals(123_456, back.lifetimeEarnings(), "spending doesn't lower lifetime earnings");
        assertEquals(3, back.rebirths());
        assertEquals(Set.of("copper-belt"), back.ownedMain());
        assertEquals(Set.of("super-dropper"), back.ownedVault());
        assertEquals(Map.of("first-rebirth", 1_762_800_000_000L), back.trophies());
    }

    @Test
    void unknownIdsAreKeptThroughLoadAndSave() {
        // A save made before a component was renamed: the old id must survive, so renaming it back restores it.
        Map<String, Object> saved = Map.of("schema", 1, "balance", 5, "owned", Map.of("main", List.of("old-name", "copper-belt")));
        PlayerData data = PlayerDataCodec.fromMap(saved);
        assertTrue(data.owned().contains("old-name"));
        PlayerData again = PlayerDataCodec.fromMap(PlayerDataCodec.toMap(data));
        assertEquals(List.of("old-name", "copper-belt"), List.copyOf(again.ownedMain()));
    }

    @Test
    void garbageLoadsAsEmptyInsteadOfCrashing() {
        PlayerData data = PlayerDataCodec.fromMap(Map.of("balance", "lots", "owned", "everything", "rebirths", List.of()));
        assertEquals(0, data.balance());
        assertTrue(data.owned().isEmpty());
        assertEquals(0, PlayerDataCodec.fromMap(null).balance());
    }

    @Test
    void spendingIsAllOrNothing() {
        PlayerData data = new PlayerData();
        data.earn(100);
        assertFalse(data.spend(101));
        assertEquals(100, data.balance());
        assertTrue(data.spend(100));
        assertEquals(0, data.balance());
    }

    @Test
    void earningSaturatesAtTheCap() {
        PlayerData data = new PlayerData();
        data.setBalance(Cents.MAX - 5);
        data.earn(1_000);
        assertEquals(Cents.MAX, data.balance());
    }

    @Test
    void aCopyIsSeparate() {
        PlayerData data = new PlayerData();
        data.earn(50);
        PlayerData copy = data.copy();
        data.earn(50);
        data.own("x", Component.Zone.MAIN);
        assertNotSame(data, copy);
        assertEquals(50, copy.balance());
        assertTrue(copy.owned().isEmpty());
    }
}
