package me.sevj6.funCore.tycoon;

import me.sevj6.funCore.tycoon.core.sim.Item;
import me.sevj6.funCore.tycoon.core.sim.PlotSim;
import me.sevj6.funCore.tycoon.core.sim.Segment;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.tycoon.core.Money;
import me.sevj6.funCore.tycoon.core.template.Offset;
import me.sevj6.funCore.tycoon.core.template.Placeholders;
import me.sevj6.funCore.tycoon.core.template.Vec;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import me.sevj6.funCore.tycoon.plot.ItemViews;
import me.sevj6.funCore.tycoon.plot.PlotSlot;
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

    /** Group-name prefixes in the plot's fixtures: owned components, and buy pads. */
    static final String COMPONENT = "component:", PAD = "pad:";
    /** How high above its pressure plate a pad's sign floats. */
    private static final double PAD_LABEL_HEIGHT = 1.1;

    final UUID player;
    final int slot;
    final PlotSlot plot;
    final Location origin;
    final Set<String> owned = new LinkedHashSet<>();
    private final NamespacedKey viewKey;
    private final int visibleCap;
    private Template template;
    private PlotSim sim;
    private ItemViews views;
    /** Cents collected since the last payout (fractions carry over). */
    double pending;
    /** Rebirths so far (always 0 until rebirths exist). */
    int rebirths;
    /**
     * Whether an admin used a testing tool on this plot. From then on its income is only lent (see {@link TestIncome})
     * and it must never be saved as the player's progress.
     */
    boolean testMode;

    PlotSession(UUID player, PlotSlot plot, NamespacedKey viewKey, int visibleCap) {
        this.player = player;
        this.slot = plot.index();
        this.plot = plot;
        this.origin = plot.origin();
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

    public boolean testMode() {
        return testMode;
    }

    /**
     * (Re)starts the plot for {@code template} from tick {@code now}: every owned component is shown, and production
     * starts. The map's own blocks are never touched; components are display entities (see
     * {@link me.sevj6.funCore.tycoon.plot.Fixtures}).
     */
    void build(Template template, long now) {
        if (sim != null) sim.reset(); // removes the old item views
        plot.fixtures().removeAll(COMPONENT);
        plot.fixtures().removeAll(PAD);
        this.template = template;
        owned.removeIf(id -> template.component(id) == null);
        int index = 0;
        for (Component component : template.components().values()) {
            if (owned.contains(component.id())) show(component, index);
            index++;
        }
        showPads();
        views = new ItemViews(viewKey, origin, visibleCap);
        sim = new PlotSim(template, this, new Random(), now);
        sim.own(owned);
    }

    /** Stops production and takes away everything the owner had on the plot. The plot is as the map has it again. */
    void stop() {
        if (sim != null) sim.reset();
        plot.fixtures().removeAll(COMPONENT);
        plot.fixtures().removeAll(PAD);
    }

    /** Shows an owned component: its parts, and barriers wherever it's solid. */
    private void show(Component component, int index) {
        String group = COMPONENT + component.id();
        plot.fixtures().spawn(group, Placeholders.of(template, component, index));
        for (Offset barrier : component.common().barriers()) plot.fixtures().place(group, barrier, Material.BARRIER);
    }

    /**
     * Shows a pad for every component the owner could buy next: not owned yet, and everything it requires owned.
     * Ones that need more rebirths show as locked.
     */
    private void showPads() {
        for (Component component : template.components().values()) {
            Offset pad = component.common().pad();
            if (pad == null || owned.contains(component.id()) || !owned.containsAll(component.common().requires())) continue;
            boolean locked = component.common().minRebirths() > rebirths;
            String group = PAD + component.id();
            plot.fixtures().place(group, pad, locked ? Material.POLISHED_BLACKSTONE_PRESSURE_PLATE : Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
            net.kyori.adventure.text.Component text = locked
                    ? Utils.mm("<white><name></white>\n<red>Requires Rebirth <n>", Placeholder.unparsed("name", component.name()),
                    Placeholder.unparsed("n", String.valueOf(component.common().minRebirths())))
                    : Utils.mm("<white><name></white>\n<gold><price>", Placeholder.unparsed("name", component.name()),
                    Placeholder.unparsed("price", Money.format(component.common().price())));
            plot.fixtures().label(group, new Vec(pad.x() + 0.5, pad.y() + PAD_LABEL_HEIGHT, pad.z() + 0.5), text);
        }
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
