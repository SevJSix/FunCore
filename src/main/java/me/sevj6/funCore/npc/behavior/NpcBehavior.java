package me.sevj6.funCore.npc.behavior;

import me.sevj6.funCore.npc.Npc;
import me.sevj6.funCore.npc.event.NpcInteractEvent;
import me.sevj6.funCore.npc.event.NpcProximityLeaveEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Something an NPC does: answer a click, greet players who come near, open a menu, and so on. An NPC can have any
 * number of behaviours; they run in the order they were added.
 *
 * <p>Each behaviour has a {@link BehaviorType} that knows how to load it from the NPC's config file and create it from
 * a command. New kinds of behaviour (a vendor, a game queue) are added by registering a new type with the
 * {@link BehaviorRegistry}.
 *
 * <p>Override only the hooks you need. Per-player state (cooldowns, dialogue progress) can live in fields; it isn't
 * saved and resets when the NPC is reloaded.
 */
public interface NpcBehavior {

    /** The id of this behaviour's {@link BehaviorType}, written to the config as {@code type}. */
    String type();

    /** One line describing this behaviour's settings, for {@code /npc behavior <id> list}. */
    String summary();

    /** Writes this behaviour's settings (everything except {@code type}) so its type can load it again. */
    void save(ConfigurationSection config);

    /** A player clicked the NPC. */
    default void onInteract(Npc npc, Player player, NpcInteractEvent.Click click) {
    }

    /** A player came within the NPC's proximity range. */
    default void onProximityEnter(Npc npc, Player player) {
    }

    /** A player left the NPC's proximity range. */
    default void onProximityLeave(Npc npc, Player player, NpcProximityLeaveEvent.Reason reason) {
    }
}
