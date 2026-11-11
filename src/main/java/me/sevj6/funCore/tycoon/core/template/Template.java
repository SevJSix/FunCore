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
 * @param startWith   components every run starts with (no pad, no price paid)
 * @param components  everything else, in the order the template lists them
 */
public record Template(Offset size, Offset spawn, float spawnYaw, Map<String, Offset> collectors,
                       Map<String, Belt> baseBelts, @Nullable Offset rebirthPad, @Nullable VaultDoor vaultDoor,
                       Map<String, Offset> trophySlots, List<Box> beds, List<String> startWith,
                       Map<String, Component> components) {

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
