package me.sevj6.funCore.npc.behavior;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every kind of {@link NpcBehavior} that NPC config files can use, by type id. FunCore registers the built-in ones;
 * other systems (minigames, shops) register their own with {@link #register}.
 */
public final class BehaviorRegistry {

    private final Logger logger;
    private final Map<String, BehaviorType> types = new TreeMap<>();

    public BehaviorRegistry(Logger logger) {
        this.logger = logger;
    }

    /** @throws IllegalArgumentException if a type with this id is already registered */
    public void register(BehaviorType type) {
        if (types.putIfAbsent(type.id(), type) != null) {
            throw new IllegalArgumentException("A behavior type '" + type.id() + "' is already registered");
        }
    }

    public @Nullable BehaviorType get(String id) {
        return types.get(id);
    }

    public Collection<BehaviorType> types() {
        return Collections.unmodifiableCollection(types.values());
    }

    /**
     * Loads one behaviour from an NPC file's {@code behaviors} list. Never loses data: if the type isn't registered or
     * its settings are invalid, the entry is kept as an {@link UnknownBehavior} that does nothing and is saved back
     * unchanged.
     */
    public NpcBehavior load(String npcId, Map<?, ?> entry) {
        ConfigurationSection config = new MemoryConfiguration().createSection("behavior", entry);
        String typeId = config.getString("type", "");
        BehaviorType type = types.get(typeId);
        if (type == null) {
            logger.warn("NPC '{}' has a behavior of unknown type '{}'; it is kept but does nothing", npcId, typeId);
            return new UnknownBehavior(typeId, entry, "unknown type");
        }
        try {
            return type.loader().load(config);
        } catch (RuntimeException e) {
            logger.warn("NPC '{}' has an invalid '{}' behavior ({}); it is kept but does nothing", npcId, typeId, e.getMessage());
            return new UnknownBehavior(typeId, entry, e.getMessage());
        }
    }

    /** Turns a behaviour into the map written to the NPC's file, with {@code type} first. */
    public static Map<String, Object> save(NpcBehavior behavior) {
        if (behavior instanceof UnknownBehavior unknown) return unknown.raw();
        ConfigurationSection config = new MemoryConfiguration().createSection("behavior");
        behavior.save(config);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", behavior.type());
        map.putAll(config.getValues(false));
        return map;
    }
}
