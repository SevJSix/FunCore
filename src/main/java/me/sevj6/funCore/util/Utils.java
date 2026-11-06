package me.sevj6.funCore.util;

import me.sevj6.funCore.FunCore;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;

/** Static helpers used across the plugin. */
public final class Utils {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** Prefix for plugin-wide (non-game) messages. */
    public static final Component PREFIX = MINI_MESSAGE.deserialize("<dark_gray>[<gold>FunCore</gold>]</dark_gray> ");

    private Utils() {
    }

    // ------------------------------------------------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Parses a <a href="https://docs.advntr.dev/minimessage/format.html">MiniMessage</a> string, e.g.
     * {@code mm("<red>Hello <name>", Placeholder.unparsed("name", player.getName()))}.
     * Put player-supplied text in a placeholder rather than in the string itself, so it can't add formatting.
     */
    public static Component mm(String miniMessage, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(miniMessage, resolvers);
    }

    /** {@link #PREFIX} followed by the message. */
    public static Component prefixed(Component message) {
        return PREFIX.append(message);
    }

    /** The plain text of a component, without formatting. */
    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Picks the singular or plural word for a count, e.g. {@code plural(n, "life", "lives")}. */
    public static String plural(long count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

    /** Formats seconds as {@code m:ss}, or {@code h:mm:ss} for an hour or more. */
    public static String formatTime(long totalSeconds) {
        totalSeconds = Math.max(0, totalSeconds);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? "%d:%02d:%02d".formatted(hours, minutes, seconds)
                : "%d:%02d".formatted(minutes, seconds);
    }

    /** Shows a title. Fade in, stay and fade out are in ticks. */
    public static void title(Audience audience, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        audience.showTitle(Title.title(title, subtitle,
                Title.Times.times(ticks(fadeIn), ticks(stay), ticks(fadeOut))));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Sounds
    // ------------------------------------------------------------------------------------------------------------

    /** Plays a sound by its vanilla key, e.g. {@code "entity.experience_orb.pickup"}. */
    public static void playSound(Audience audience, String key, float volume, float pitch) {
        audience.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch));
    }

    /** The note-block "pling", handy for countdowns and alerts. */
    public static void playPling(Audience audience, float pitch) {
        playSound(audience, "block.note_block.pling", 1f, pitch);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Scheduling (main thread unless stated otherwise)
    // ------------------------------------------------------------------------------------------------------------

    public static long secondsToTicks(double seconds) {
        return Math.round(seconds * 20);
    }

    public static Duration ticks(long ticks) {
        return Duration.ofMillis(ticks * 50);
    }

    /** Runs on the main thread: right away if already on it, otherwise on the next tick. */
    public static void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(FunCore.getInstance(), task);
        }
    }

    public static BukkitTask runLater(Runnable task, long delayTicks) {
        return Bukkit.getScheduler().runTaskLater(FunCore.getInstance(), task, delayTicks);
    }

    public static BukkitTask runTimer(Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(FunCore.getInstance(), task, delayTicks, periodTicks);
    }

    /** Runs off the main thread. Don't touch the world, entities or players from here. */
    public static BukkitTask async(Runnable task) {
        return Bukkit.getScheduler().runTaskAsynchronously(FunCore.getInstance(), task);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Players
    // ------------------------------------------------------------------------------------------------------------

    public static double getMaxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    /** Full health and food, and puts out fire. */
    public static void heal(Player player) {
        player.setHealth(getMaxHealth(player));
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setExhaustion(0f);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
    }

    public static void clearPotionEffects(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    /**
     * Gives the player a blank slate: empty inventory, no effects or XP, full health and food, default speeds and no
     * flight. Doesn't change their game mode or location.
     */
    public static void resetPlayer(Player player) {
        player.setItemOnCursor(null);
        player.closeInventory();
        player.getInventory().clear();
        clearPotionEffects(player);
        heal(player);
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0f);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------------------------------------------------

    /** Copies an item array; empty slots stay {@code null}. */
    public static @Nullable ItemStack[] cloneItems(@Nullable ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        return copy;
    }

    /**
     * Turns items into a Base64 string, keeping the slot order. Paper's item format records the game version, so
     * these strings still load after a Minecraft update.
     */
    public static String itemsToBase64(@Nullable ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    /** Reverses {@link #itemsToBase64}. Empty slots come back as air. */
    public static ItemStack[] itemsFromBase64(String data) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(data));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Locations / config
    // ------------------------------------------------------------------------------------------------------------

    /** Writes a location under {@code path}, saving the world by name. */
    public static void writeLocation(ConfigurationSection section, String path, Location location) {
        ConfigurationSection s = section.createSection(path);
        s.set("world", location.getWorld().getName());
        s.set("x", location.getX());
        s.set("y", location.getY());
        s.set("z", location.getZ());
        s.set("yaw", (double) location.getYaw());
        s.set("pitch", (double) location.getPitch());
    }

    /** Reads a location written by {@link #writeLocation}, or {@code null} if it's missing or its world isn't loaded. */
    public static @Nullable Location readLocation(ConfigurationSection section, String path) {
        ConfigurationSection s = section.getConfigurationSection(path);
        if (s == null) return null;
        String worldName = s.getString("world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    /** Moves a file over another, atomically where the filesystem allows. */
    public static void moveReplacing(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
