package me.sevj6.funCore.npc.behavior;

import me.sevj6.funCore.npc.Npc;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code greeting}: the NPC says something when a player walks into its proximity range (see
 * {@code /npc edit <id> proximityrange}).
 * <pre>
 * - type: greeting
 *   lines: ["Hey &lt;player&gt;!", "Back again, &lt;player&gt;?"]   # one is picked at random
 *   cooldown: 60        # seconds before the same player is greeted again
 *   show-name: true
 * </pre>
 */
public final class GreetingBehavior implements NpcBehavior {

    public static final String TYPE = "greeting";
    private static final int DEFAULT_COOLDOWN_SECONDS = 60;

    private final List<String> lines;
    private final int cooldownSeconds;
    private final boolean showName;
    private final Map<UUID, Long> lastGreeted = new HashMap<>();

    public GreetingBehavior(List<String> lines, int cooldownSeconds, boolean showName) {
        if (lines.isEmpty()) throw new IllegalArgumentException("a greeting needs at least one line");
        this.lines = List.copyOf(lines);
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.showName = showName;
    }

    public static BehaviorType definition() {
        return new BehaviorType(TYPE, "<text the NPC says when someone comes near>", GreetingBehavior::load, value -> {
            if (value.isBlank()) throw new IllegalArgumentException("Give the greeting text, e.g. Hey <player>!");
            return new GreetingBehavior(List.of(value), DEFAULT_COOLDOWN_SECONDS, true);
        });
    }

    private static GreetingBehavior load(ConfigurationSection config) {
        return new GreetingBehavior(config.getStringList("lines"),
                config.getInt("cooldown", DEFAULT_COOLDOWN_SECONDS), config.getBoolean("show-name", true));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String summary() {
        String first = lines.getFirst();
        return lines.size() + " line(s), every " + cooldownSeconds + "s per player: \""
                + (first.length() > 40 ? first.substring(0, 40) + "..." : first) + "\"";
    }

    @Override
    public void save(ConfigurationSection config) {
        config.set("lines", lines);
        config.set("cooldown", cooldownSeconds);
        config.set("show-name", showName);
    }

    @Override
    public void onProximityEnter(Npc npc, Player player) {
        long now = System.currentTimeMillis();
        Long last = lastGreeted.get(player.getUniqueId());
        if (last != null && now - last < cooldownSeconds * 1000L) return;
        lastGreeted.put(player.getUniqueId(), now);
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        player.sendMessage(MessageBehavior.speech(npc, player, line, showName));
    }
}
