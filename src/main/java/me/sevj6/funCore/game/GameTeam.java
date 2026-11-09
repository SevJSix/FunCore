package me.sevj6.funCore.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A team in one match, made when the match starts. Outside the team modes every player gets a team of their own
 * (named after them), so games can treat FFA and team modes the same way ("last team standing wins").
 *
 * <p>In team modes, a team's {@linkplain #getIndex() index} is its slot: slot 0 is always Red Team, slot 1 Blue
 * Team, and so on ({@link #nameOf}, {@link #colorOf}). Players can pick a slot in the queue ({@code /game team}), so
 * a match may have, say, Red and Yellow but no Blue.
 *
 * <p>Members stay on the team after they're eliminated or leave, so scores and results can still name them. Use
 * {@link Minigame#getRemainingTeams()} for the teams that still have players in the match.
 */
public final class GameTeam {

    private static final NamedTextColor[] COLORS = {
            NamedTextColor.RED, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.YELLOW,
            NamedTextColor.AQUA, NamedTextColor.LIGHT_PURPLE, NamedTextColor.GOLD, NamedTextColor.WHITE,
            NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA, NamedTextColor.DARK_PURPLE, NamedTextColor.GRAY};
    private static final String[] NAMES = {
            "Red", "Blue", "Lime", "Yellow", "Aqua", "Pink", "Orange", "White", "Green", "Cyan", "Purple", "Gray"};
    private static final Material[] WOOL = {
            Material.RED_WOOL, Material.BLUE_WOOL, Material.LIME_WOOL, Material.YELLOW_WOOL,
            Material.LIGHT_BLUE_WOOL, Material.PINK_WOOL, Material.ORANGE_WOOL, Material.WHITE_WOOL,
            Material.GREEN_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.GRAY_WOOL};
    /** How many slots have a name of their own; players can choose between at most this many teams. */
    public static final int NAMED_SLOTS = NAMES.length;

    private final int index;
    private final NamedTextColor color;
    private final Component name;
    private final Set<UUID> members = new LinkedHashSet<>();

    GameTeam(int index, NamedTextColor color, Component name) {
        this.index = index;
        this.color = color;
        this.name = name;
    }

    /** A team-mode team for a slot, named and coloured after it. */
    static GameTeam forSlot(int slot) {
        return new GameTeam(slot, colorOf(slot), nameOf(slot));
    }

    /** The colour of a team slot (they repeat after {@link #NAMED_SLOTS}). */
    public static NamedTextColor colorOf(int slot) {
        return COLORS[slot % COLORS.length];
    }

    /** "Red Team", "Blue Team", ..., then "Team 13" and on. Already coloured. */
    public static Component nameOf(int slot) {
        return Component.text(slot < NAMES.length ? NAMES[slot] + " Team" : "Team " + (slot + 1), colorOf(slot));
    }

    /** The slot's id in commands: "red", "blue", ... (only the {@link #NAMED_SLOTS named slots} have one). */
    public static String idOf(int slot) {
        return slot < NAMES.length ? NAMES[slot].toLowerCase() : "team" + (slot + 1);
    }

    /** The named slot with this {@link #idOf id}, or -1. */
    public static int slotOf(String id) {
        for (int slot = 0; slot < NAMES.length; slot++) {
            if (NAMES[slot].equalsIgnoreCase(id)) return slot;
        }
        return -1;
    }

    /** Wool in the slot's colour, for icons. */
    public static Material woolOf(int slot) {
        return WOOL[slot % WOOL.length];
    }

    /** In team modes, the team's slot (which decides its name and colour); outside them, its position in the match. */
    public int getIndex() {
        return index;
    }

    public NamedTextColor getColor() {
        return color;
    }

    /** "Red Team", or the player's name in solo mode. Already coloured. */
    public Component getName() {
        return name;
    }

    /** Everyone who started on this team, including players who are out. */
    public Set<UUID> getMembers() {
        return Collections.unmodifiableSet(members);
    }

    public boolean contains(UUID uuid) {
        return members.contains(uuid);
    }

    int size() {
        return members.size();
    }

    void add(UUID uuid) {
        members.add(uuid);
    }

    @Override
    public String toString() {
        return "GameTeam[" + index + ", members=" + members.size() + "]";
    }
}
