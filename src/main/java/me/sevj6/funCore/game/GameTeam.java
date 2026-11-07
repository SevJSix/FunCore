package me.sevj6.funCore.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A team in one match, made when the match starts. In {@link TeamMode#SOLO} every player gets a team of their own
 * (named after them), so games can treat solo and team modes the same way ("last team standing wins").
 *
 * <p>Members stay on the team after they're eliminated or leave, so scores and results can still name them. Use
 * {@link Minigame#getRemainingTeams()} for the teams that still have players in the match.
 */
public final class GameTeam {

    private final int index;
    private final NamedTextColor color;
    private final Component name;
    private final Set<UUID> members = new LinkedHashSet<>();

    GameTeam(int index, NamedTextColor color, Component name) {
        this.index = index;
        this.color = color;
        this.name = name;
    }

    /** 0-based position, which also decides the team's spawn. */
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

    void add(UUID uuid) {
        members.add(uuid);
    }

    @Override
    public String toString() {
        return "GameTeam[" + index + ", members=" + members.size() + "]";
    }
}
