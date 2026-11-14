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

    @org.junit.jupiter.api.Test
    void rebirthStartsTheRunOverButKeepsTheVault() {
        PlayerData data = new PlayerData();
        data.earn(500_00);
        data.own("dropper", me.sevj6.funCore.tycoon.core.template.Component.Zone.MAIN);
        data.own("super", me.sevj6.funCore.tycoon.core.template.Component.Zone.VAULT);
        data.setManualLevel(3);
        data.setSpeedLevel(2);
        data.setStarLevel(4);
        data.rebirth();
        org.junit.jupiter.api.Assertions.assertEquals(1, data.rebirths());
        org.junit.jupiter.api.Assertions.assertEquals(0, data.balance());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("super"), data.owned());
        org.junit.jupiter.api.Assertions.assertEquals(0, data.manualLevel() + data.speedLevel() + data.starLevel());
        org.junit.jupiter.api.Assertions.assertEquals(500_00, data.lifetimeEarnings(), "lifetime earnings stay");
    }

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
    void earningGoesFarPastTheOldCap() {
        PlayerData data = new PlayerData();
        data.setBalance(9e18); // the old cap: $90 quadrillion
        data.earn(1e20);
        assertEquals(1.09e20, data.balance());
        assertTrue(data.spend(1e20));
        assertEquals(9e18, data.balance());
    }

    @Test
    void earningSaturatesAtTheCap() {
        PlayerData data = new PlayerData();
        data.setBalance(Cents.MAX);
        data.earn(Cents.MAX);
        assertEquals(Cents.MAX, data.balance(), "never infinity");
        assertEquals(Cents.MAX, data.lifetimeEarnings());
        data.earn(Double.POSITIVE_INFINITY);
        assertEquals(Cents.MAX, data.balance());
        assertTrue(data.spend(Cents.MAX));
        assertEquals(0, data.balance());
    }

    @Test
    void badAmountsChangeNothing() {
        PlayerData data = new PlayerData();
        data.earn(100);
        data.earn(Double.NaN);
        data.earn(-50);
        assertEquals(100, data.balance());
        assertEquals(100, data.lifetimeEarnings());
        assertFalse(data.spend(Double.NaN), "NaN is never taken");
        assertFalse(data.spend(-1));
        assertFalse(data.spend(Double.POSITIVE_INFINITY));
        assertEquals(100, data.balance());
        data.setBalance(Double.NaN);
        assertEquals(0, data.balance());
        data.setBalance(Double.POSITIVE_INFINITY);
        assertEquals(Cents.MAX, data.balance());
        data.setBalance(-5);
        assertEquals(0, data.balance());
    }

    /** Saves and loads the way the server does (PlayerStore's YAML settings). */
    private static PlayerData throughYaml(PlayerData data) {
        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        String text = "# Tycoon progress. Money is in cents.\n" + new Yaml(options).dump(PlayerDataCodec.toMap(data));
        return PlayerDataCodec.fromMap(new Yaml().load(text));
    }

    @Test
    void hugeBalancesSurviveASave() {
        for (double cents : new double[]{0, 1, 123_456, Cents.EXACT - 1, 9e18, 1e20, 1.234567890123e30, 1e300, Cents.MAX}) {
            PlayerData data = new PlayerData();
            data.setBalance(cents);
            data.earn(cents);
            PlayerData back = throughYaml(data);
            assertEquals(data.balance(), back.balance(), "balance " + cents);
            assertEquals(data.lifetimeEarnings(), back.lifetimeEarnings(), "lifetime earnings " + cents);
        }
    }

    @Test
    void smallBalancesAreSavedAsWholeNumbers() {
        PlayerData data = new PlayerData();
        data.earn(123_456);
        assertEquals(123_456L, PlayerDataCodec.toMap(data).get("balance"), "files read the way they always have");
        data.setBalance(1e30);
        assertEquals(1e30, PlayerDataCodec.toMap(data).get("balance"));
    }

    @Test
    void oldAndHandEditedFilesLoad() {
        // Saved by the old long-cents version, and a hand-edited big integer.
        assertEquals(9e18, PlayerDataCodec.fromMap(Map.of("balance", 9_000_000_000_000_000_000L)).balance());
        assertEquals(1e30, PlayerDataCodec.fromMap(new Yaml().load("balance: 1000000000000000000000000000000")).balance());
        assertEquals(1e30, PlayerDataCodec.fromMap(new Yaml().load("balance: 1.0E30")).balance());
        assertEquals(0, PlayerDataCodec.fromMap(new Yaml().load("balance: .nan")).balance());
        assertEquals(Cents.MAX, PlayerDataCodec.fromMap(new Yaml().load("balance: .inf")).balance());
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
