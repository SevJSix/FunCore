package me.sevj6.funCore.tycoon.core.template;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything every plot is built from: the base build (always there) and the components players buy. Positions are
 * all {@link Offset offsets} from the plot origin.
 *
 * @param size        the plot's bounding box, from the origin; cleared before every build
 * @param basePiece   the base build's piece name, or {@code null} for a placeholder platform
 * @param collectors  where items are cashed in, by id
 * @param baseBelts   belts that are part of the base (free, always present), by id
 * @param rebirthPad  where the rebirth pad goes, or {@code null} for none
 * @param startWith   components every run starts with (no pad, no price paid)
 * @param components  everything else, in the order the template lists them
 */
public record Template(Offset size, @Nullable String basePiece, Offset spawn, float spawnYaw,
                       Map<String, Offset> collectors, Map<String, Belt> baseBelts, @Nullable Offset rebirthPad,
                       List<String> startWith, Map<String, Component> components) {

    public Template {
        collectors = Collections.unmodifiableMap(new LinkedHashMap<>(collectors));
        baseBelts = Collections.unmodifiableMap(new LinkedHashMap<>(baseBelts));
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
