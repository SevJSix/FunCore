package me.sevj6.funCore.npc.behavior;

import me.sevj6.funCore.npc.Npc;
import me.sevj6.funCore.npc.event.NpcInteractEvent;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code message}: the NPC says something when clicked.
 * <pre>
 * - type: message
 *   lines: ["Welcome, &lt;player&gt;!", "&lt;gray&gt;Right-click again for a tip."]
 *   mode: sequence      # all (every line at once) | sequence (next line each click) | random (one random line)
 *   click: any          # any | left | right
 *   show-name: true     # prefix each line with the NPC's name
 * </pre>
 * Lines are MiniMessage; {@code <player>} becomes the clicking player's name.
 */
public final class MessageBehavior implements NpcBehavior {

    public static final String TYPE = "message";

    public enum Mode { ALL, SEQUENCE, RANDOM }

    private final List<String> lines;
    private final Mode mode;
    private final ClickFilter click;
    private final boolean showName;
    /** Next line index per player, for {@link Mode#SEQUENCE}. */
    private final Map<UUID, Integer> progress = new HashMap<>();

    public MessageBehavior(List<String> lines, Mode mode, ClickFilter click, boolean showName) {
        if (lines.isEmpty()) throw new IllegalArgumentException("a message needs at least one line");
        this.lines = List.copyOf(lines);
        this.mode = mode;
        this.click = click;
        this.showName = showName;
    }

    public static BehaviorType definition() {
        return new BehaviorType(TYPE, "<text the NPC says>", MessageBehavior::load, value -> {
            if (value.isBlank()) throw new IllegalArgumentException("Give the text the NPC should say, e.g. Hello, <player>!");
            return new MessageBehavior(List.of(value), Mode.ALL, ClickFilter.ANY, true);
        });
    }

    private static MessageBehavior load(ConfigurationSection config) {
        String mode = config.getString("mode", "all");
        Mode parsed;
        try {
            parsed = Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be all, sequence or random, not '" + mode + "'");
        }
        return new MessageBehavior(config.getStringList("lines"), parsed,
                ClickFilter.parse(config.getString("click", "any")), config.getBoolean("show-name", true));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String summary() {
        String first = lines.getFirst();
        return mode.name().toLowerCase(Locale.ROOT) + ", " + lines.size() + " line(s), " + click.configName() + " click: \""
                + (first.length() > 40 ? first.substring(0, 40) + "..." : first) + "\"";
    }

    @Override
    public void save(ConfigurationSection config) {
        config.set("lines", lines);
        config.set("mode", mode.name().toLowerCase(Locale.ROOT));
        config.set("click", click.configName());
        config.set("show-name", showName);
    }

    @Override
    public void onInteract(Npc npc, Player player, NpcInteractEvent.Click click) {
        if (!this.click.matches(click)) return;
        switch (mode) {
            case ALL -> lines.forEach(line -> player.sendMessage(speech(npc, player, line, showName)));
            case RANDOM -> player.sendMessage(speech(npc, player, lines.get(ThreadLocalRandom.current().nextInt(lines.size())), showName));
            case SEQUENCE -> {
                int index = progress.getOrDefault(player.getUniqueId(), 0);
                player.sendMessage(speech(npc, player, lines.get(index), showName));
                progress.put(player.getUniqueId(), (index + 1) % lines.size());
            }
        }
    }

    /** One line of NPC speech: {@code Name » line}, or just the line. {@code <player>} is filled in. */
    static Component speech(Npc npc, Player player, String line, boolean showName) {
        Component text = Utils.mm(line, Placeholder.unparsed("player", player.getName()));
        if (!showName) return text;
        return Utils.mm("<name> <dark_gray>»</dark_gray> ", Placeholder.component("name", npc.getDisplayName())).append(text);
    }
}
