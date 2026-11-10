package me.sevj6.funCore.game;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lag compensation for falls. A high-ping player's clutch reaches the server late: by the time the server learns of
 * it, it may already have dealt them fall damage. This holds a fall hit back for a tick or two instead, then deals it
 * only if nothing broke their fall in the meantime: water under them, a ladder, cobweb or powder snow, a vehicle, a
 * slime block, or a teleport (an ender pearl landing).
 * <p>
 * The owning game decides how long to hold each hit (see {@link #tierTicks} and {@link #roundTripTicks}), calls
 * {@link #hold} first thing for fall damage in its damage handler, {@link #tick} every tick, and {@link #cancel} when
 * the player's attempt ends some other way (a teleport, a reset, leaving). A held hit is dealt again as an ordinary
 * fall hit, so the damage handler sees it a second time (and lets it through: {@link #hold} doesn't hold it twice).
 * A totem in hand then saves them as usual.
 */
public final class FallGrace {

    private static final long TICK_NANOS = 50_000_000L;

    private record Held(double damage, long dueNanos) {
    }

    private final Map<UUID, Held> held = new HashMap<>();
    /** Set while a held hit is being dealt, so it goes through. */
    private boolean dealing;

    /**
     * The grace for a ping (in milliseconds): none below {@code oneTickPing}, 1 tick from there, 2 ticks from
     * {@code twoTicksPing}. A threshold of 0 turns that step off.
     */
    public static int tierTicks(int ping, int oneTickPing, int twoTicksPing) {
        if (twoTicksPing > 0 && ping >= twoTicksPing) return 2;
        if (oneTickPing > 0 && ping >= oneTickPing) return 1;
        return 0;
    }

    /** A whole round trip to the player, in ticks (rounded up): what something they wait on the server for costs them. */
    public static int roundTripTicks(int ping) {
        return (Math.max(0, ping) + 49) / 50;
    }

    /**
     * Holds a fall hit back for at least {@code ticks} ticks (the event is cancelled), unless it's a held hit being
     * dealt, or {@code ticks} is 0. A second hit while one is held is folded into it (the bigger one counts).
     *
     * @return whether the hit was held back
     */
    public boolean hold(Player player, EntityDamageEvent event, int ticks) {
        if (dealing || ticks <= 0 || event.getCause() != EntityDamageEvent.DamageCause.FALL) return false;
        event.setCancelled(true);
        Held hit = new Held(event.getDamage(), System.nanoTime() + ticks * TICK_NANOS);
        held.merge(player.getUniqueId(), hit, (old, added) -> new Held(Math.max(old.damage(), added.damage()), old.dueNanos()));
        return true;
    }

    /** Forgets a held hit: their attempt ended some other way first. */
    public void cancel(UUID player) {
        held.remove(player);
    }

    /** Whether one of their fall hits is being held back right now. */
    public boolean isHolding(UUID player) {
        return held.containsKey(player);
    }

    public void clear() {
        held.clear();
    }

    /** Every tick: deals each held hit that's due, unless something broke their fall while it was held. */
    public void tick() {
        if (held.isEmpty()) return;
        long now = System.nanoTime();
        List<Map.Entry<UUID, Held>> due = new ArrayList<>();
        for (Map.Entry<UUID, Held> entry : held.entrySet()) {
            if (now - entry.getValue().dueNanos() >= 0) due.add(entry);
        }
        // Dealing one can end their attempt (and so cancel others), so the due ones are taken out first.
        due.forEach(entry -> held.remove(entry.getKey()));
        for (Map.Entry<UUID, Held> entry : due) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isValid() || player.isDead() || brokeFall(player)) continue;
            dealing = true;
            try {
                player.damage(entry.getValue().damage(), DamageSource.builder(DamageType.FALL).build());
            } finally {
                dealing = false;
            }
        }
    }

    /**
     * Whether they're somewhere that stops fall damage, as the server sees it now: in water (or a bubble column), on
     * something climbable, in cobweb, sweet berries or powder snow, in a vehicle, or on a slime block (not sneaking).
     */
    public static boolean brokeFall(Player player) {
        if (player.isInWater() || player.isInsideVehicle() || player.isClimbing()) return true;
        Block feet = player.getLocation().getBlock();
        for (Block block : new Block[]{feet, feet.getRelative(0, 1, 0)}) {
            Material type = block.getType();
            if (Tag.FALL_DAMAGE_RESETTING.isTagged(type) || type == Material.POWDER_SNOW) return true;
        }
        Block below = player.getLocation().subtract(0, 0.05, 0).getBlock();
        return below.getType() == Material.SLIME_BLOCK && !player.isSneaking();
    }
}
