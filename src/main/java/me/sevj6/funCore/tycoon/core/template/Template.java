package me.sevj6.funCore.tycoon.core.template;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a plot is made of besides the map itself: the positions the map's plot provides (spawn, collectors,
 * belts, the vault door, trophy slots) and the components players buy. Positions are all {@link Offset offsets} from
 * the plot origin.
 *
 * @param size        the plot's footprint from the origin (x and z), and the height above the floor it uses
 * @param collectors  where items are cashed in, by id
 * @param baseBelts   belts that are part of the base (free, always present), by id
 * @param rebirthPad  where the rebirth pad goes, or {@code null} for none
 * @param vaultDoor   the vault's doorway, sealed until the owner has enough rebirths, or {@code null} for no vault
 * @param trophySlots where trophies stand, by slot id
 * @param beds        the map's belt beds: every belt has to run along these blocks (empty = anywhere)
 * @param gateLabel   where the plot's name sign goes (on the gate), or {@code null} for no sign
 * @param manual      the thing players mine by clicking (and its upgrades), or {@code null} for none
 * @param beltSpeed   belt speed upgrades ({@link Ladder.Step#amount()} is the speed multiplier), or {@code null}
 * @param gateLasers  where gate lasers go (switched on by a component with {@code feature: gate-lasers}), or
 *                    {@code null}
 * @param starShower  falling stars to pick up for money, bought in levels, or {@code null}
 * @param startWith   components every run starts with (no pad, no price paid)
 * @param components  everything else, in the order the template lists them
 * @param rebirth     what a rebirth costs, and the sign that says so, or {@code null}
 */
public record Template(Offset size, Offset spawn, float spawnYaw, Map<String, Offset> collectors,
                       Map<String, Belt> baseBelts, @Nullable Offset rebirthPad, @Nullable VaultDoor vaultDoor,
                       Map<String, Offset> trophySlots, List<Box> beds, @Nullable GateSign gateLabel,
                       @Nullable Manual manual, @Nullable Ladder beltSpeed, @Nullable GateLasers gateLasers,
                       @Nullable StarShower starShower, List<String> startWith, Map<String, Component> components,
                       @Nullable Rebirth rebirth) {

    /**
     * What a rebirth costs and gives, and a big sign saying so (in front of the vault, say). Its pad is
     * {@link #rebirthPad()}, shown once the run is complete ({@link #runComplete}).
     *
     * @param price cents
     * @param bonus income added per rebirth (0.5: rebirth 1 is 1.5x, rebirth 2 is 2x)
     * @param sign  where the sign goes, or {@code null} for none
     * @param scale how big the sign's text is (1 = a normal label)
     */
    public record Rebirth(long price, double bonus, @Nullable GateSign sign, double scale) {
    }

    /**
     * Whether a run is complete, so its owner can rebirth: every main-zone component owned, and every upgrade ladder
     * (pickaxe, belt speed, star shower) maxed.
     */
    public boolean runComplete(java.util.Set<String> owned, int manualLevel, int speedLevel, int starLevel) {
        for (Component component : components.values()) {
            if (component.common().zone() == Component.Zone.MAIN && !owned.contains(component.id())) return false;
        }
        if (manual != null && manual.upgrades().next(manualLevel) != null) return false;
        if (beltSpeed != null && beltSpeed.next(speedLevel) != null) return false;
        return starShower == null || starShower.ladder().next(starLevel) == null;
    }

    /**
     * Lasers across the plot's gate: while they're on, only the owner can walk in through {@code zone}. The owner
     * turns them on and off with a lever at {@code lever}: on a wall, facing {@code leverFacing} (away from the block
     * it's fixed to), or on the floor when that's {@code null}.
     */
    public record GateLasers(Box zone, Offset lever, @Nullable String leverFacing) {
    }

    /** The component that switches on {@code feature}, or {@code null}. */
    public @Nullable Component withFeature(String feature) {
        for (Component component : components.values()) {
            if (feature.equals(component.common().feature())) return component;
        }
        return null;
    }

    /**
     * Something on the plot players click to earn money by hand (an ore vein): how a run starts out, before droppers
     * earn much. Its upgrades raise what a click is worth.
     *
     * @param at         the block it stands on (clicks hit a box around it)
     * @param value      cents a click before any upgrade
     * @param maxClicks  clicks a second that count, so an autoclicker can't do better than a quick hand
     * @param upgrades   {@link Ladder.Step#amount()} is cents a click at that level
     * @param extra      more spots just like it, switched on by a component with {@code feature: more-miners}
     */
    public record Manual(String name, Offset at, List<Part> parts, double value, int maxClicks, Ladder upgrades,
                         List<Offset> extra) {

        public Manual {
            parts = List.copyOf(parts);
            extra = List.copyOf(extra);
        }

        /** Its parts moved to stand at {@code spot} instead (for the extra spots). */
        public List<Part> partsAt(Offset spot) {
            double dx = spot.x() - at.x(), dy = spot.y() - at.y(), dz = spot.z() - at.z();
            List<Part> moved = new java.util.ArrayList<>();
            for (Part part : parts) {
                moved.add(new Part(part.block(), part.data(), new Vec(part.at().x() + dx, part.at().y() + dy, part.at().z() + dz),
                        part.size(), part.slide()));
            }
            return moved;
        }

        /** Cents a click at upgrade level {@code level}. */
        public double valueAt(int level) {
            return upgrades.amount(level, value);
        }
    }

    /**
     * The plot's name sign: on block {@code at} (part of the gate, say), just off its {@code front} face, so it's read
     * from that side and isn't hidden by the block itself. Without a front, it floats in the middle of the block.
     *
     * @param front {@code north}, {@code south}, {@code east}, {@code west}, or {@code null}
     */
    public record GateSign(Offset at, @Nullable String front) {

        /** How far the sign stands out from the block's centre: a quarter of a block clear of its face. */
        private static final double OUT = 0.75;

        /** Where the sign's text sits (its bottom middle), from the plot origin. */
        public Vec point() {
            double x = at.x() + 0.5, z = at.z() + 0.5;
            if (front != null) {
                switch (front) {
                    case "north" -> z -= OUT;
                    case "south" -> z += OUT;
                    case "west" -> x -= OUT;
                    case "east" -> x += OUT;
                    default -> {
                    }
                }
            }
            return new Vec(x, at.y() + 0.1, z);
        }
    }

    /**
     * The doorway into the rebirth vault: every block from {@code min} to {@code max}. While the owner has fewer than
     * {@code unlockRebirths} rebirths (and while nobody owns the plot) it's sealed.
     */
    public record VaultDoor(Offset min, Offset max, int unlockRebirths) {

        public VaultDoor {
            Offset low = new Offset(Math.min(min.x(), max.x()), Math.min(min.y(), max.y()), Math.min(min.z(), max.z()));
            Offset high = new Offset(Math.max(min.x(), max.x()), Math.max(min.y(), max.y()), Math.max(min.z(), max.z()));
            min = low;
            max = high;
        }

        /** Every block of the doorway. */
        public List<Offset> blocks() {
            return Offset.box(min, max);
        }
    }

    public Template {
        collectors = Collections.unmodifiableMap(new LinkedHashMap<>(collectors));
        baseBelts = Collections.unmodifiableMap(new LinkedHashMap<>(baseBelts));
        trophySlots = Collections.unmodifiableMap(new LinkedHashMap<>(trophySlots));
        beds = List.copyOf(beds);
        startWith = List.copyOf(startWith);
        components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
    }

    public @Nullable Component component(String id) {
        return components.get(id);
    }

    /** A belt by id: a base belt, or the belt of a conveyor component. */
    public @Nullable Belt belt(String id) {
        Belt base = baseBelts.get(id);
        if (base != null) return base;
        return components.get(id) instanceof Component.Conveyor conveyor ? conveyor.belt() : null;
    }

    /** Whether a belt is part of the base build (free, never cleared). */
    public boolean isBaseBelt(String id) {
        return baseBelts.containsKey(id);
    }

    public List<Component.Dropper> droppers() {
        List<Component.Dropper> droppers = new ArrayList<>();
        for (Component component : components.values()) {
            if (component instanceof Component.Dropper dropper) droppers.add(dropper);
        }
        return droppers;
    }

    public List<Component.Upgrader> upgraders() {
        List<Component.Upgrader> upgraders = new ArrayList<>();
        for (Component component : components.values()) {
            if (component instanceof Component.Upgrader upgrader) upgraders.add(upgrader);
        }
        return upgraders;
    }
}
