package me.sevj6.funCore.spawn;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.game.Minigame;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the spawn area ({@link ServerSpawn#getArea()}, the lobby) safe. Inside it, a player:
 * <ul>
 *     <li>takes no damage at all (falls, fire, mobs, other players, ...), except {@code /kill} and the void, so nobody
 *     can get stuck falling forever;</li>
 *     <li>can't hurt other players, wherever they are (no hitting or shooting out of spawn);</li>
 *     <li>never gets hungry: food and saturation stay full, so health comes back quickly.</li>
 * </ul>
 * Players in a game that's under way are left to its own rules, even if its arena reaches into the spawn area; players
 * only waiting for a game to start are protected.
 */
public final class SpawnProtection implements Listener {

    private final FunCore plugin;
    private final ServerSpawn spawn;

    public SpawnProtection(FunCore plugin, ServerSpawn spawn) {
        this.plugin = plugin;
        this.spawn = spawn;
    }

    /** Starts keeping everyone in the spawn area fed (once a second). */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::feed, 20L, 20L);
    }

    /** Whether the spawn area protects this player right now. */
    public boolean protects(Player player) {
        if (!spawn.isInArea(player.getLocation())) return false;
        Minigame game = plugin.getGameManager() == null ? null : plugin.getGameManager().getGame(player);
        return game == null || game.getState().isJoinable();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.KILL || cause == EntityDamageEvent.DamageCause.VOID) return;
        if (event.getEntity() instanceof Player victim && protects(victim)) {
            event.setCancelled(true);
            if (cause == EntityDamageEvent.DamageCause.FIRE_TICK || cause == EntityDamageEvent.DamageCause.FIRE
                    || cause == EntityDamageEvent.DamageCause.LAVA) victim.setFireTicks(0);
            return;
        }
        // No fighting from spawn: someone in it can't hurt a player outside it either.
        if (event instanceof EntityDamageByEntityEvent byEntity && event.getEntity() instanceof Player) {
            Player attacker = attacker(byEntity.getDamager());
            if (attacker != null && protects(attacker)) event.setCancelled(true);
        }
    }

    /** The player behind a hit: the one who struck, or who fired the arrow, threw the potion, ... */
    private static @Nullable Player attacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && event.getFoodLevel() < player.getFoodLevel() && protects(player)) {
            event.setCancelled(true);
        }
    }

    /** Full food and saturation for everyone in the spawn area (and nothing used up toward losing it). */
    private void feed() {
        String world = spawn.getAreaWorld();
        if (world == null || spawn.getArea() == null) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.getWorld().getName().equals(world) || !protects(player)) continue;
            if (player.getFoodLevel() < 20) player.setFoodLevel(20);
            if (player.getSaturation() < 20f) player.setSaturation(20f);
            player.setExhaustion(0f);
        }
    }
}
