package me.sevj6.funCore.tycoon.core.template;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Something a player can own on their plot: bought on its pad, shown as display-entity parts. Each kind adds its own
 * fields.
 */
public sealed interface Component permits Component.Dropper, Component.Conveyor, Component.Upgrader, Component.Wall {

    /** Main-zone purchases are cleared on rebirth; vault-zone ones are kept. */
    enum Zone {
        MAIN, VAULT
    }

    /**
     * What every component has.
     *
     * @param price       in cents
     * @param requires    ids of components that must be owned before this one can be bought
     * @param minRebirths rebirths needed before it can be bought
     * @param pad         where its buy pad goes (the pressure plate's block); {@code null} only for components every
     *                    run starts with
     * @param parts       how it looks, as display entities; empty for {@link Placeholders placeholder} parts
     * @param barriers    blocks made solid (with barriers) while it's owned, so players can't walk through it
     * @param outside     whether it may reach outside the plot's footprint (e.g. decoration on the walkway)
     */
    record Common(String id, String name, long price, List<String> requires, int minRebirths, Zone zone,
                  @Nullable Offset pad, List<Part> parts, List<Offset> barriers, boolean outside) {

        public Common {
            requires = List.copyOf(requires);
            parts = List.copyOf(parts);
            barriers = List.copyOf(barriers);
        }
    }

    Common common();

    default String id() {
        return common().id();
    }

    default String name() {
        return common().name();
    }

    default String typeName() {
        return switch (this) {
            case Dropper ignored -> "dropper";
            case Conveyor ignored -> "conveyor";
            case Upgrader ignored -> "upgrader";
            case Wall ignored -> "wall";
        };
    }

    /**
     * How an item looks: an item model ({@code item}) or a block ({@code block}) of {@code material}, at {@code scale}
     * (1 = full block size).
     */
    record Visual(boolean block, String material, double scale) {
    }

    /**
     * Makes an item every {@code interval} ticks at {@code spout}; it drops onto belt {@code belt} at waypoint
     * {@code waypoint}.
     *
     * @param value cents per item, before upgraders and multipliers
     */
    record Dropper(Common common, Offset spout, int interval, Visual item, double value, String belt, int waypoint)
            implements Component {
    }

    /** A belt that's bought: it exists on the plot once owned. */
    record Conveyor(Common common, Belt belt) implements Component {
    }

    /**
     * Multiplies the value of every item reaching waypoint {@code waypoint} of belt {@code belt}, once per item.
     *
     * @param swap     the material items look like afterwards (e.g. raw iron to iron ingot), or {@code null}
     * @param particle a particle name shown on upgrade, or {@code null}
     * @param sound    a sound key played on upgrade, or {@code null}
     */
    record Upgrader(Common common, String belt, int waypoint, double multiplier, @Nullable String swap,
                    @Nullable String particle, @Nullable String sound) implements Component {
    }

    /** Decoration: only parts (and barriers). */
    record Wall(Common common) implements Component {
    }
}
