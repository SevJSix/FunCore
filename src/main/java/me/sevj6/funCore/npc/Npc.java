package me.sevj6.funCore.npc;

import com.destroystokyo.paper.SkinParts;
import me.sevj6.funCore.npc.behavior.NpcBehavior;
import me.sevj6.funCore.npc.event.NpcProximityEnterEvent;
import me.sevj6.funCore.npc.event.NpcProximityLeaveEvent;
import me.sevj6.funCore.npc.skin.Skin;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A player-like NPC, shown in the world as a {@link Mannequin} entity. Because a Mannequin isn't a player, it never
 * shows up in the tab list or the player count.
 *
 * <p>An {@code Npc} is the lasting definition (settings saved to its config file) plus whatever entity currently
 * represents it. The entity is never saved with the world. The {@link NpcManager} spawns it whenever the NPC's chunk is
 * loaded and it disappears when the chunk unloads, so editing the config file is always the source of truth.
 *
 * <p>Setters apply to the spawned entity right away but don't save. Call {@link NpcManager#save(Npc)} afterward.
 */
public final class Npc {

    public static final double DEFAULT_LOOK_RANGE = 20;
    public static final double DEFAULT_PROXIMITY_RANGE = 5;
    /** Resend look packets at least this often, in case the server re-sent the entity's real rotation. */
    private static final int LOOK_REFRESH_TICKS = 10;

    private final NpcManager manager;
    private final String id;
    private final boolean saved;

    private Location location;
    private String name;
    private boolean nameVisible = true;
    private @Nullable String description;
    private @Nullable Skin skin;
    private boolean lookAtPlayers = true;
    private double lookRange = DEFAULT_LOOK_RANGE;
    private double proximityRange = DEFAULT_PROXIMITY_RANGE;
    private final List<NpcBehavior> behaviors = new ArrayList<>();

    private @Nullable Mannequin entity;
    /** Players currently inside the proximity range. */
    private final Set<UUID> nearby = new LinkedHashSet<>();
    /** Players currently being looked at, with the rotation last sent to each. */
    private final Map<UUID, LookState> lookers = new HashMap<>();

    private static final class LookState {
        float yaw;
        float pitch;
        long sentAt;
    }

    Npc(NpcManager manager, String id, Location location, String name, boolean saved) {
        this.manager = manager;
        this.id = id;
        this.location = location.clone();
        this.name = name;
        this.saved = saved;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Entity
    // ------------------------------------------------------------------------------------------------------------

    /** Spawns the entity if it isn't already. Does nothing if the NPC's chunk isn't loaded. */
    public void spawn() {
        if (isSpawned()) return;
        World world = location.getWorld();
        if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;

        entity = world.spawn(location, Mannequin.class, mannequin -> {
            mannequin.setPersistent(false); // never saved with the chunk; we respawn it from config
            mannequin.setInvulnerable(true);
            mannequin.setImmovable(true);
            mannequin.setGravity(false);
            mannequin.setSilent(true);
            mannequin.setCollidable(false);
            mannequin.setRemoveWhenFarAway(false);
            mannequin.setSkinParts(SkinParts.allParts());
            mannequin.getPersistentDataContainer().set(manager.npcKey(), PersistentDataType.STRING, id);
            mannequin.setRotation(location.getYaw(), location.getPitch());
            mannequin.setBodyYaw(location.getYaw());
            applyAppearance(mannequin);
        });
    }

    /** Removes the entity (the NPC itself still exists and can be spawned again). */
    public void despawn() {
        clearViewers();
        if (entity != null) {
            entity.remove();
            entity = null;
        }
    }

    public boolean isSpawned() {
        return entity != null && entity.isValid();
    }

    /** The entity currently showing this NPC, or {@code null} if it isn't spawned. */
    public @Nullable Mannequin getEntity() {
        return isSpawned() ? entity : null;
    }

    private void applyAppearance(Mannequin mannequin) {
        // Hiding the name completely needs no custom name; "custom name not visible" still shows it when looked at.
        if (nameVisible) {
            mannequin.customName(getDisplayName());
            mannequin.setCustomNameVisible(true);
        } else {
            mannequin.customName(null);
            mannequin.setCustomNameVisible(false);
        }
        mannequin.setDescription(description == null ? null : Utils.mm(description));
        mannequin.setProfile(skin != null ? skin.toProfile() : Mannequin.defaultProfile());
    }

    private void refreshAppearance() {
        Mannequin mannequin = getEntity();
        if (mannequin != null) applyAppearance(mannequin);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Ticking (called every tick by NpcManager)
    // ------------------------------------------------------------------------------------------------------------

    void tick(long tick) {
        if (entity != null && !entity.isValid()) {
            // Chunk unloaded, /kill, etc. It is respawned once its chunk is loaded again.
            entity = null;
            clearViewers();
        }
        Mannequin mannequin = entity;
        if (mannequin == null) return;

        World world = mannequin.getWorld();
        Location base = mannequin.getLocation();
        Location eye = mannequin.getEyeLocation();
        double proximitySq = proximityRange * proximityRange;
        double lookSq = lookRange * lookRange;

        for (Player player : List.copyOf(world.getPlayers())) {
            UUID uuid = player.getUniqueId();
            boolean counts = player.getGameMode() != GameMode.SPECTATOR;
            double distanceSq = counts ? player.getLocation().distanceSquared(base) : Double.MAX_VALUE;

            if (proximityRange > 0 && distanceSq <= proximitySq) {
                if (nearby.add(uuid)) Bukkit.getPluginManager().callEvent(new NpcProximityEnterEvent(this, player));
            } else if (nearby.remove(uuid)) {
                Bukkit.getPluginManager().callEvent(new NpcProximityLeaveEvent(this, player, NpcProximityLeaveEvent.Reason.OUT_OF_RANGE));
            }
            if (!isSpawned()) return; // an event listener despawned or removed us

            if (lookAtPlayers && distanceSq <= lookSq) {
                lookAt(player, mannequin, eye, tick);
            } else if (lookers.remove(uuid) != null) {
                manager.packets().sendRotation(player, mannequin, location.getYaw(), location.getPitch());
            }
        }

        // Players we were tracking who are no longer in this world.
        for (UUID uuid : List.copyOf(nearby)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.getWorld().equals(world)) continue;
            nearby.remove(uuid);
            if (player != null) {
                Bukkit.getPluginManager().callEvent(new NpcProximityLeaveEvent(this, player, NpcProximityLeaveEvent.Reason.CHANGED_WORLD));
            }
        }
        lookers.keySet().removeIf(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            return player == null || !player.getWorld().equals(world);
        });
    }

    private void lookAt(Player player, Mannequin mannequin, Location eye, long tick) {
        Location facing = eye.clone().setDirection(player.getEyeLocation().toVector().subtract(eye.toVector()));
        float yaw = facing.getYaw();
        float pitch = facing.getPitch();

        LookState state = lookers.computeIfAbsent(player.getUniqueId(), uuid -> {
            LookState s = new LookState();
            s.sentAt = Long.MIN_VALUE / 2; // forces a send on the first tick
            return s;
        });
        boolean turned = Math.abs(angleDifference(yaw, state.yaw)) >= 1f || Math.abs(pitch - state.pitch) >= 1f;
        if (!turned && tick - state.sentAt < LOOK_REFRESH_TICKS) return;

        manager.packets().sendRotation(player, mannequin, yaw, pitch);
        state.yaw = yaw;
        state.pitch = pitch;
        state.sentAt = tick;
    }

    private static float angleDifference(float a, float b) {
        float diff = (a - b) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        return diff;
    }

    /** Fires leave events for everyone nearby and resets the head of everyone being looked at. */
    private void clearViewers() {
        for (UUID uuid : List.copyOf(nearby)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                Bukkit.getPluginManager().callEvent(new NpcProximityLeaveEvent(this, player, NpcProximityLeaveEvent.Reason.NPC_DESPAWNED));
            }
        }
        nearby.clear();
        resetLookers();
    }

    private void resetLookers() {
        Mannequin mannequin = getEntity();
        if (mannequin != null) {
            for (UUID uuid : lookers.keySet()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) manager.packets().sendRotation(player, mannequin, location.getYaw(), location.getPitch());
            }
        }
        lookers.clear();
    }

    /** Called when a player disconnects. */
    void handleQuit(Player player) {
        lookers.remove(player.getUniqueId());
        if (nearby.remove(player.getUniqueId())) {
            Bukkit.getPluginManager().callEvent(new NpcProximityLeaveEvent(this, player, NpcProximityLeaveEvent.Reason.QUIT));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------------------------

    public String getId() {
        return id;
    }

    /** Whether this NPC is written to {@code npcs/<id>.yml}. Temporary NPCs (e.g. made by a minigame) are not. */
    public boolean isSaved() {
        return saved;
    }

    public Location getLocation() {
        return location.clone();
    }

    /** Moves the NPC. Its rotation becomes the default it faces when nobody is in look range. */
    public void setLocation(Location location) {
        Objects.requireNonNull(location.getWorld(), "location must have a world");
        this.location = location.clone();
        resetLookers();
        Mannequin mannequin = getEntity();
        if (mannequin != null && mannequin.getWorld().equals(location.getWorld())) {
            mannequin.teleport(location);
            mannequin.setBodyYaw(location.getYaw());
        } else {
            despawn();
            spawn();
        }
    }

    /** The name as a MiniMessage string. */
    public String getName() {
        return name;
    }

    public Component getDisplayName() {
        return Utils.mm(name);
    }

    /** Sets the name, as a MiniMessage string. */
    public void setName(String name) {
        this.name = name;
        refreshAppearance();
    }

    public boolean isNameVisible() {
        return nameVisible;
    }

    public void setNameVisible(boolean nameVisible) {
        this.nameVisible = nameVisible;
        refreshAppearance();
    }

    /** The line shown under the name, as a MiniMessage string, or {@code null} for none. */
    public @Nullable String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description == null || description.isBlank() ? null : description;
        refreshAppearance();
    }

    /** The NPC's skin, or {@code null} for the default Mannequin skin. */
    public @Nullable Skin getSkin() {
        return skin;
    }

    public void setSkin(@Nullable Skin skin) {
        this.skin = skin;
        refreshAppearance();
    }

    public boolean isLookAtPlayers() {
        return lookAtPlayers;
    }

    /** Whether the NPC turns its head to look at each player in {@link #getLookRange() look range}. */
    public void setLookAtPlayers(boolean lookAtPlayers) {
        this.lookAtPlayers = lookAtPlayers;
        if (!lookAtPlayers) resetLookers();
    }

    public double getLookRange() {
        return lookRange;
    }

    public void setLookRange(double lookRange) {
        this.lookRange = Math.max(0, lookRange);
    }

    public double getProximityRange() {
        return proximityRange;
    }

    /** Range for {@link NpcProximityEnterEvent}/{@link NpcProximityLeaveEvent}. 0 turns the events off. */
    public void setProximityRange(double proximityRange) {
        this.proximityRange = Math.max(0, proximityRange);
    }

    /** What this NPC does when clicked or approached, in the order they run (read-only). */
    public List<NpcBehavior> getBehaviors() {
        return Collections.unmodifiableList(behaviors);
    }

    public void addBehavior(NpcBehavior behavior) {
        behaviors.add(behavior);
    }

    /** Removes the behaviour at {@code index} (counted from 0) and returns it. */
    public NpcBehavior removeBehavior(int index) {
        return behaviors.remove(index);
    }

    public void clearBehaviors() {
        behaviors.clear();
    }

    /** Players currently in proximity range (read-only). */
    public Set<UUID> getNearbyPlayers() {
        return Collections.unmodifiableSet(nearby);
    }

    public boolean isNearby(Player player) {
        return nearby.contains(player.getUniqueId());
    }

    @Override
    public String toString() {
        return "Npc[id=" + id + ", spawned=" + isSpawned() + "]";
    }
}
