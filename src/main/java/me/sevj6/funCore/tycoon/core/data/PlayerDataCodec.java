package me.sevj6.funCore.tycoon.core.data;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@link PlayerData} into plain maps and lists (for YAML) and back. Bukkit-free, so it's unit-tested.
 *
 * <pre>
 * schema: 1
 * balance: 123456           # cents
 * rebirths: 0
 * lifetime-earnings: 999999 # cents
 * manual-level: 2           # pickaxe upgrades bought this run
 * speed-level: 1            # belt speed upgrades bought this run
 * star-level: 1             # star shower upgrades bought this run
 * gate-locked: true         # gate lasers on (if the plot has them)
 * last-seen: 1762800000000  # when they last left (epoch milliseconds)
 * away-rate: 1234.5         # cents a second their plot earned on its own then
 * owned:
 *   main: [copper-belt, copper-dropper]
 *   vault: []
 * trophies:
 *   first-rebirth: 1762800000000   # when, epoch milliseconds
 * </pre>
 * Anything unreadable falls back to empty; nothing in a player's file can crash loading.
 */
public final class PlayerDataCodec {

    public static final int SCHEMA = 1;

    private PlayerDataCodec() {
    }

    /** A snapshot of {@code data} as plain values (independent of it afterwards). */
    public static Map<String, Object> toMap(PlayerData data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema", SCHEMA);
        map.put("balance", data.balance());
        map.put("rebirths", data.rebirths());
        map.put("lifetime-earnings", data.lifetimeEarnings());
        map.put("manual-level", data.manualLevel());
        map.put("speed-level", data.speedLevel());
        map.put("star-level", data.starLevel());
        map.put("gate-locked", data.gateLocked());
        map.put("last-seen", data.lastSeen());
        map.put("away-rate", data.awayRate());
        Map<String, Object> owned = new LinkedHashMap<>();
        owned.put("main", new ArrayList<>(data.ownedMain()));
        owned.put("vault", new ArrayList<>(data.ownedVault()));
        map.put("owned", owned);
        map.put("trophies", new LinkedHashMap<>(data.trophies()));
        return map;
    }

    public static PlayerData fromMap(@Nullable Object value) {
        PlayerData data = new PlayerData();
        if (!(value instanceof Map<?, ?> map)) return data;
        data.setBalance(number(map.get("balance")));
        data.setRebirths((int) Math.min(Integer.MAX_VALUE, number(map.get("rebirths"))));
        data.setLifetimeEarnings(number(map.get("lifetime-earnings")));
        data.setManualLevel((int) Math.min(1000, number(map.get("manual-level"))));
        data.setSpeedLevel((int) Math.min(1000, number(map.get("speed-level"))));
        data.setStarLevel((int) Math.min(1000, number(map.get("star-level"))));
        data.leftAt(number(map.get("last-seen")), map.get("away-rate") instanceof Number rate ? rate.doubleValue() : 0);
        data.setGateLocked(!(map.get("gate-locked") instanceof Boolean locked) || locked);
        if (map.get("owned") instanceof Map<?, ?> owned) {
            strings(owned.get("main")).forEach(data::ownMain);
            strings(owned.get("vault")).forEach(data::ownVault);
        }
        if (map.get("trophies") instanceof Map<?, ?> trophies) {
            trophies.forEach((id, at) -> data.unlockTrophy(String.valueOf(id), number(at)));
        }
        return data;
    }

    private static long number(@Nullable Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static List<String> strings(@Nullable Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) if (entry != null) result.add(String.valueOf(entry));
        }
        return result;
    }
}
