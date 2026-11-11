package me.sevj6.funCore.tycoon;

import me.sevj6.funCore.tycoon.core.sim.Item;
import me.sevj6.funCore.tycoon.core.sim.PlotSim;
import me.sevj6.funCore.tycoon.core.sim.Segment;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.tycoon.plot.ItemViews;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;

import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * One online player's tycoon: their plot slot, what they own, the simulation running it and the views showing it,
 * plus income collected but not yet paid into the economy.
 */
public final class PlotSession implements PlotSim.Listener {

    final UUID player;
    final int slot;
    final Location origin;
    final Set<String> owned = new LinkedHashSet<>();
    private final NamespacedKey viewKey;
    private final int visibleCap;
    private Template template;
    private PlotSim sim;
    private ItemViews views;
    /** Cents collected since the last payout (fractions carry over). */
    double pending;

    PlotSession(UUID player, int slot, Location origin, NamespacedKey viewKey, int visibleCap) {
        this.player = player;
        this.slot = slot;
        this.origin = origin;
        this.viewKey = viewKey;
        this.visibleCap = visibleCap;
    }

    public UUID player() {
        return player;
    }

    public int slot() {
        return slot;
    }

    public Location origin() {
        return origin.clone();
    }

    public Template template() {
        return template;
    }

    public PlotSim sim() {
        return sim;
    }

    public ItemViews views() {
        return views;
    }

    /** Cents collected but not paid into the economy yet. */
    public double pending() {
        return pending;
    }

    /**
     * (Re)starts the plot for {@code template} from tick {@code now}. The plot itself is part of the map: nothing here
     * places or removes blocks.
     */
    void build(Template template, long now) {
        if (sim != null) sim.reset(); // removes the old views
        this.template = template;
        owned.removeIf(id -> template.component(id) == null);
        views = new ItemViews(viewKey, origin, visibleCap);
        sim = new PlotSim(template, this, new Random(), now);
        sim.own(owned);
    }

    /** Stops production: every item is dropped (its view removed). The blocks stay until {@link #clear()}. */
    void stop() {
        if (sim != null) sim.reset();
    }

    /** Where the owner spawns on their plot. */
    public Location spawn() {
        Location spawn = views.at(template.spawn().center());
        spawn.setY(origin.getY() + template.spawn().y());
        spawn.setYaw(template.spawnYaw());
        return spawn;
    }

    // ------------------------------------------------------------------------------------------------------------
    // The simulation's events
    // ------------------------------------------------------------------------------------------------------------

    @Override
    public void spawned(Item item) {
        views.spawn(item);
    }

    @Override
    public void moving(Item item, Segment segment) {
        views.move(item, segment);
    }

    @Override
    public void upgraded(Item item, Component.Upgrader upgrader) {
        if (upgrader.swap() != null) views.swap(item, upgrader.swap());
    }

    @Override
    public void collected(Item item, String collector, double payout) {
        views.remove(item);
        pending += payout;
    }

    @Override
    public void discarded(Item item) {
        views.remove(item);
    }
}
