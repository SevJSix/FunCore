package me.sevj6.funCore.npc.behavior;

import org.bukkit.configuration.ConfigurationSection;

/**
 * A kind of {@link NpcBehavior}, registered with the {@link BehaviorRegistry} under its {@code id}.
 *
 * @param id          what config files and commands call it, e.g. {@code "message"}
 * @param usage       what {@code /npc behavior <id> add <type> <value>} expects as the value, for help text
 * @param loader      builds the behaviour from its saved settings
 * @param fromCommand builds the behaviour from the text typed after the type in the add command ({@code ""} if none);
 *                    throws {@link IllegalArgumentException} with a player-facing message if it's not valid
 */
public record BehaviorType(String id, String usage, Loader loader, CommandFactory fromCommand) {

    @FunctionalInterface
    public interface Loader {
        /** @throws IllegalArgumentException if the settings are invalid */
        NpcBehavior load(ConfigurationSection config);
    }

    @FunctionalInterface
    public interface CommandFactory {
        NpcBehavior create(String value);
    }
}
