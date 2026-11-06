package me.sevj6.funCore.npc.behavior;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stands in for a behaviour whose type isn't registered (e.g. one added by a system that's been removed) or whose
 * settings couldn't be read. It does nothing, but keeps the original settings so saving the NPC doesn't erase them.
 */
public final class UnknownBehavior implements NpcBehavior {

    private final String type;
    private final Map<String, Object> raw;
    private final String problem;

    UnknownBehavior(String type, Map<?, ?> raw, String problem) {
        this.type = type;
        this.raw = new LinkedHashMap<>();
        raw.forEach((key, value) -> this.raw.put(String.valueOf(key), value));
        this.problem = problem;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String summary() {
        return "INACTIVE (" + problem + ")";
    }

    @Override
    public void save(ConfigurationSection config) {
        raw.forEach((key, value) -> {
            if (!key.equals("type")) config.set(key, value);
        });
    }

    Map<String, Object> raw() {
        return raw;
    }
}
