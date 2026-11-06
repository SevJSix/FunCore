package me.sevj6.funCore.npc.behavior;

import me.sevj6.funCore.npc.Npc;
import me.sevj6.funCore.npc.event.NpcInteractEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code command}: runs commands when the NPC is clicked.
 * <pre>
 * - type: command
 *   commands: ["spawn", "say &lt;player&gt; visited the guide"]
 *   as: player          # player (with the player's own permissions) | console (with full permissions)
 *   click: any
 * </pre>
 * {@code <player>} and {@code <uuid>} are filled in. A leading {@code /} is optional.
 *
 * <p>{@code as: console} runs with every permission, so only use it for commands that are safe for anyone who clicks
 * the NPC. Only admins can edit NPCs, so players can't set this up themselves.
 */
public final class CommandBehavior implements NpcBehavior {

    public static final String TYPE = "command";

    public enum RunAs { PLAYER, CONSOLE }

    private final List<String> commands;
    private final RunAs runAs;
    private final ClickFilter click;

    public CommandBehavior(List<String> commands, RunAs runAs, ClickFilter click) {
        if (commands.isEmpty()) throw new IllegalArgumentException("a command behavior needs at least one command");
        this.commands = commands.stream().map(c -> c.startsWith("/") ? c.substring(1) : c).toList();
        this.runAs = runAs;
        this.click = click;
    }

    public static BehaviorType definition() {
        return new BehaviorType(TYPE, "<command, run as the player>", CommandBehavior::load, value -> {
            if (value.isBlank()) throw new IllegalArgumentException("Give the command to run, e.g. spawn");
            return new CommandBehavior(List.of(value), RunAs.PLAYER, ClickFilter.ANY);
        });
    }

    private static CommandBehavior load(ConfigurationSection config) {
        String as = config.getString("as", "player");
        RunAs runAs;
        try {
            runAs = RunAs.valueOf(as.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("as must be player or console, not '" + as + "'");
        }
        return new CommandBehavior(config.getStringList("commands"), runAs, ClickFilter.parse(config.getString("click", "any")));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String summary() {
        return "as " + runAs.name().toLowerCase(Locale.ROOT) + ", " + click.configName() + " click: /" + String.join(", /", commands);
    }

    @Override
    public void save(ConfigurationSection config) {
        config.set("commands", commands);
        config.set("as", runAs.name().toLowerCase(Locale.ROOT));
        config.set("click", click.configName());
    }

    @Override
    public void onInteract(Npc npc, Player player, NpcInteractEvent.Click click) {
        if (!this.click.matches(click)) return;
        for (String command : commands) {
            String filled = command.replace("<player>", player.getName()).replace("<uuid>", player.getUniqueId().toString());
            if (runAs == RunAs.CONSOLE) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled);
            } else {
                player.performCommand(filled);
            }
        }
    }
}
