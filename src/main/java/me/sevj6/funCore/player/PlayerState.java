package me.sevj6.funCore.player;

import me.sevj6.funCore.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A snapshot of everything a minigame might change about a player, so it can be put back exactly as it was afterward.
 * It can be saved to and loaded from a YAML file, so it survives disconnects and server restarts.
 *
 * @param location {@code null} if the saved world no longer exists (the player is sent to the main world's spawn instead)
 */
public record PlayerState(
        String playerName,
        long capturedAt,
        ItemStack[] inventory,
        int heldSlot,
        @Nullable Location location,
        GameMode gameMode,
        double health,
        int foodLevel,
        float saturation,
        float exhaustion,
        int level,
        float exp,
        int totalExperience,
        int fireTicks,
        int remainingAir,
        float fallDistance,
        boolean allowFlight,
        boolean flying,
        float walkSpeed,
        float flySpeed,
        List<PotionEffect> potionEffects
) {

    public static PlayerState capture(Player player) {
        return new PlayerState(
                player.getName(),
                System.currentTimeMillis(),
                // getContents() can return live views of the inventory, so take real copies.
                Utils.cloneItems(player.getInventory().getContents()),
                player.getInventory().getHeldItemSlot(),
                player.getLocation(),
                player.getGameMode(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getFireTicks(),
                player.getRemainingAir(),
                player.getFallDistance(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getWalkSpeed(),
                player.getFlySpeed(),
                List.copyOf(player.getActivePotionEffects())
        );
    }

    /** Puts this state back on the player. The player must be online and alive. */
    public void apply(Player player) {
        // Get rid of anything from the game they're holding on their cursor, so closing the inventory can't drop it.
        player.setItemOnCursor(null);
        player.closeInventory();
        player.leaveVehicle();

        Location target = location != null ? location : Bukkit.getWorlds().getFirst().getSpawnLocation();
        player.teleport(target);

        // Game mode goes first: changing it resets flight.
        player.setGameMode(gameMode);
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.setWalkSpeed(walkSpeed);
        player.setFlySpeed(flySpeed);

        player.getInventory().setContents(inventory);
        player.getInventory().setHeldItemSlot(heldSlot);

        Utils.clearPotionEffects(player);
        player.addPotionEffects(potionEffects);

        double maxHealth = Utils.getMaxHealth(player);
        player.setHealth(health <= 0 ? maxHealth : Math.min(health, maxHealth));
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);

        player.setTotalExperience(totalExperience);
        player.setLevel(level);
        player.setExp(exp);

        player.setFireTicks(fireTicks);
        player.setRemainingAir(remainingAir);
        player.setFallDistance(fallDistance);

        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------------------------------

    public void save(File file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("player-name", playerName);
        yaml.set("captured-at", capturedAt);
        yaml.set("inventory", Utils.itemsToBase64(inventory));
        yaml.set("held-slot", heldSlot);
        if (location != null) Utils.writeLocation(yaml, "location", location);
        yaml.set("game-mode", gameMode.name());
        yaml.set("health", health);
        yaml.set("food-level", foodLevel);
        yaml.set("saturation", (double) saturation);
        yaml.set("exhaustion", (double) exhaustion);
        yaml.set("level", level);
        yaml.set("exp", (double) exp);
        yaml.set("total-experience", totalExperience);
        yaml.set("fire-ticks", fireTicks);
        yaml.set("remaining-air", remainingAir);
        yaml.set("fall-distance", (double) fallDistance);
        yaml.set("allow-flight", allowFlight);
        yaml.set("flying", flying);
        yaml.set("walk-speed", (double) walkSpeed);
        yaml.set("fly-speed", (double) flySpeed);
        yaml.set("potion-effects", new ArrayList<>(potionEffects));

        // Write to a temp file and then rename it, so a crash mid-write can't corrupt an existing backup.
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        yaml.save(temp);
        Utils.moveReplacing(temp, file);
    }

    public static PlayerState load(File file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            throw new IOException("Could not read player state " + file.getName(), e);
        }

        String inventoryData = yaml.getString("inventory");
        if (inventoryData == null) throw new IOException("Player state " + file.getName() + " has no inventory data");

        List<PotionEffect> effects = new ArrayList<>();
        for (Object o : yaml.getList("potion-effects", List.of())) {
            if (o instanceof PotionEffect effect) effects.add(effect);
        }

        return new PlayerState(
                yaml.getString("player-name", "unknown"),
                yaml.getLong("captured-at"),
                Utils.itemsFromBase64(inventoryData),
                yaml.getInt("held-slot"),
                Utils.readLocation(yaml, "location"),
                GameMode.valueOf(yaml.getString("game-mode", GameMode.SURVIVAL.name())),
                yaml.getDouble("health", 20),
                yaml.getInt("food-level", 20),
                (float) yaml.getDouble("saturation", 5),
                (float) yaml.getDouble("exhaustion"),
                yaml.getInt("level"),
                (float) yaml.getDouble("exp"),
                yaml.getInt("total-experience"),
                yaml.getInt("fire-ticks"),
                yaml.getInt("remaining-air", 300),
                (float) yaml.getDouble("fall-distance"),
                yaml.getBoolean("allow-flight"),
                yaml.getBoolean("flying"),
                (float) yaml.getDouble("walk-speed", 0.2),
                (float) yaml.getDouble("fly-speed", 0.1),
                effects
        );
    }
}
