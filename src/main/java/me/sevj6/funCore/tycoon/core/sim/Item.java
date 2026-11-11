package me.sevj6.funCore.tycoon.core.sim;

import me.sevj6.funCore.tycoon.core.template.Component;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One produced item, as data: where it's going, how far along it is and what it's worth. The entity players see is
 * only a view of it ({@link #view}), and may not exist at all; the item is simulated and paid either way.
 */
public final class Item {

    final long id;
    final Component.Dropper dropper;
    final List<Segment> route;
    /** The segment it's on; -1 until it starts moving. */
    int segment = -1;
    /** Cents, after the upgraders it has passed. */
    double value;
    private @Nullable Set<String> upgradedBy;
    boolean removed;
    /** Whatever the server shows for it (an entity), or {@code null}. Not used by the simulation. */
    public @Nullable Object view;

    Item(long id, Component.Dropper dropper, List<Segment> route) {
        this.id = id;
        this.dropper = dropper;
        this.route = route;
        this.value = dropper.value();
    }

    public long id() {
        return id;
    }

    public Component.Dropper dropper() {
        return dropper;
    }

    /** Its current value in cents. */
    public double value() {
        return value;
    }

    /** The segment it's moving along, or {@code null} before it starts. */
    public @Nullable Segment currentSegment() {
        return segment >= 0 && segment < route.size() ? route.get(segment) : null;
    }

    /** The segment after the current one (the first one, before it starts), or {@code null} at the end. */
    public @Nullable Segment upcomingSegment() {
        int next = segment + 1;
        return next < route.size() ? route.get(next) : null;
    }

    /** Applies an upgrader unless this item already went through it. */
    boolean upgrade(Component.Upgrader upgrader) {
        if (upgradedBy == null) upgradedBy = new HashSet<>(4);
        if (!upgradedBy.add(upgrader.id())) return false;
        value *= upgrader.multiplier();
        return true;
    }

    public boolean wasUpgradedBy(String upgrader) {
        return upgradedBy != null && upgradedBy.contains(upgrader);
    }
}
