package me.sevj6.funCore.tycoon;

import me.sevj6.funCore.tycoon.core.Money;
import me.sevj6.funCore.tycoon.core.Purchases;
import me.sevj6.funCore.tycoon.core.data.PlayerData;
import me.sevj6.funCore.tycoon.core.sim.Cents;
import me.sevj6.funCore.tycoon.core.sim.Item;
import me.sevj6.funCore.tycoon.core.sim.PlotSim;
import me.sevj6.funCore.tycoon.core.sim.Segment;
import me.sevj6.funCore.tycoon.core.template.Belt;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Ladder;
import me.sevj6.funCore.tycoon.core.template.Part;
import me.sevj6.funCore.tycoon.core.template.Offset;
import me.sevj6.funCore.tycoon.core.template.Placeholders;
import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.tycoon.core.template.Vec;
import me.sevj6.funCore.tycoon.plot.BeltAnimator;
import me.sevj6.funCore.tycoon.plot.ItemViews;
import me.sevj6.funCore.tycoon.plot.PlotSlot;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * One online player's tycoon: their plot, their progress ({@link PlayerData}), the simulation running the plot and
 * the views showing it.
 *
 * <p>Income goes straight into the player's tycoon balance as items are collected (whole cents; fractions carry
 * over). The tycoon has its own balance, in memory, so there's nothing to batch.
 */
public final class PlotSession implements PlotSim.Listener {

    /** Group names (and prefixes) in the plot's fixtures. */
    static final String COMPONENT = "component:", PAD = "pad:", LADDER = "ladder:", MANUAL = "manual", BASE_BELTS = "belts";
    /** Pad ids for the two ladders (component ids can't start with '#'). */
    static final String MANUAL_PAD = "#manual", SPEED_PAD = "#speed";
    /** How high above its pressure plate a pad's sign floats. */
    private static final double PAD_LABEL_HEIGHT = 1.1;
    /** Seconds the income rate is averaged over. */
    private static final int INCOME_WINDOW = 10;

    final UUID player;
    final int slot;
    final PlotSlot plot;
    final Location origin;
    /** What the plot runs: the template's start-with components and every saved purchase the template still has. */
    final Set<String> owned = new LinkedHashSet<>();
    private final NamespacedKey viewKey;
    private final int visibleCap;
    /** The player's progress (the live copy; saves take snapshots of it). */
    PlayerData data;
    private Template template;
    private PlotSim sim;
    private ItemViews views;
    /** Pads shown now: where (from the plot origin) to which component. */
    private final Map<Offset, String> pads = new HashMap<>();
    /** Fractions of a cent collected, not yet in the balance. */
    private double fraction;
    /** Income per second over the last {@link #INCOME_WINDOW} seconds (a ring), and the second it's at. */
    private final long[] incomeBySecond = new long[INCOME_WINDOW];
    private long incomeSecond = -1;
    /**
     * Whether an admin used a testing tool on this plot. Its progress was saved just before, and from then on it's
     * never saved again; when the session ends the player goes back to {@link #beforeTest}.
     */
    private boolean testMode;
    private @Nullable PlayerData beforeTest;
    /** Makes the belt surfaces run. */
    private final BeltAnimator animator = new BeltAnimator();
    /** The manual earner's click box, or {@code null} without one. */
    private @Nullable Interaction manualNode;

    PlotSession(UUID player, PlotSlot plot, PlayerData data, NamespacedKey viewKey, int visibleCap) {
        this.player = player;
        this.slot = plot.index();
        this.plot = plot;
        this.origin = plot.origin();
        this.data = data;
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

    public PlayerData data() {
        return data;
    }

    public boolean testMode() {
        return testMode;
    }

    /** Puts the session in test mode, keeping a copy of the progress to go back to. Call after saving it. */
    void enterTestMode() {
        if (testMode) return;
        testMode = true;
        beforeTest = data.copy();
    }

    /** Starts the session over with {@code fresh} progress (an admin reset): test mode ends, nothing to go back to. */
    void replaceData(PlayerData fresh) {
        data = fresh;
        testMode = false;
        beforeTest = null;
    }

    /** The progress as it was before test mode (or the live progress if there was no test). */
    PlayerData progressToKeep() {
        return testMode && beforeTest != null ? beforeTest : data;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------------------------------------------------

    /**
     * (Re)starts the plot for {@code template} from tick {@code now}: every owned component is shown, and production
     * starts. The map's own blocks are never touched; components are display entities (see
     * {@link me.sevj6.funCore.tycoon.plot.Fixtures}). Throws away any items in flight, so it isn't used for purchases.
     */
    void build(Template template, long now) {
        if (sim != null) sim.reset(); // removes the old item views
        clearFixtures();
        this.template = template;
        owned.clear();
        owned.addAll(template.startWith());
        for (String id : data.owned()) {
            if (template.component(id) != null) owned.add(id); // unknown ids stay saved, just not run
        }
        showBelts();
        int index = 0;
        for (Component component : template.components().values()) {
            if (owned.contains(component.id())) show(component, index);
            index++;
        }
        showManual();
        showPads();
        views = new ItemViews(viewKey, origin, visibleCap);
        sim = new PlotSim(template, this, new Random(), now);
        sim.setSpeedMultiplier(speedMultiplier());
        sim.own(owned);
    }

    /** Takes everything this session put on the plot away. */
    private void clearFixtures() {
        plot.fixtures().removeAll(COMPONENT);
        plot.fixtures().removeAll(PAD);
        plot.fixtures().removeAll(LADDER);
        plot.fixtures().remove(MANUAL);
        plot.fixtures().remove(BASE_BELTS);
        pads.clear();
        animator.clear();
        manualNode = null;
    }

    /**
     * Lays out the running surface of every belt on the plot together: the base's own (the vault's, free) and the
     * conveyors it owns that don't have parts of their own. Done again whenever a conveyor is bought, since a new
     * stretch changes where the surface starts.
     */
    private void showBelts() {
        plot.fixtures().remove(BASE_BELTS);
        animator.clear();
        List<Belt> belts = new ArrayList<>(template.baseBelts().values());
        for (Component component : template.components().values()) {
            if (component instanceof Component.Conveyor conveyor && owned.contains(conveyor.id())
                    && conveyor.common().parts().isEmpty()) belts.add(conveyor.belt());
        }
        List<Part> parts = Placeholders.surfaces(template, belts);
        animate(plot.fixtures().spawn(BASE_BELTS, parts), parts);
    }

    /** Hands any sliding parts (belt surfaces) to the animator. */
    private void animate(List<Display> displays, List<Part> parts) {
        for (int i = 0; i < displays.size() && i < parts.size(); i++) {
            if (parts.get(i).slide() != null) animator.add(displays.get(i), parts.get(i).slide());
        }
    }

    /** The manual earner: its parts and an invisible box around it that takes clicks. */
    private void showManual() {
        Template.Manual manual = template.manual();
        if (manual == null) return;
        plot.fixtures().spawn(MANUAL, manual.parts());
        Offset at = manual.at();
        plot.fixtures().label(MANUAL, new Vec(at.x() + 0.5, at.y() + 1.7, at.z() + 0.5), Utils.mm(
                "<gold><bold><name></bold></gold>\n<gray>Click to mine <white><value></white> a hit",
                Placeholder.unparsed("name", manual.name()), Placeholder.unparsed("value", Money.format(Cents.whole(manual.valueAt(data.manualLevel()))))));
        manualNode = plot.fixtures().spawn(MANUAL, new Vec(at.x() + 0.5, at.y(), at.z() + 0.5), Interaction.class, box -> {
            box.setInteractionWidth(1.3f);
            box.setInteractionHeight(1.3f);
            box.setResponsive(true);
        });
    }

    /** Whether {@code entity} is this plot's manual earner. */
    boolean isManualNode(Entity entity) {
        return manualNode != null && manualNode.equals(entity);
    }

    /** One click on the manual earner: cents earned (already added to the balance). */
    long mine() {
        Template.Manual manual = template.manual();
        if (manual == null) return 0;
        fraction += manual.valueAt(data.manualLevel());
        long whole = Cents.whole(fraction);
        fraction -= whole;
        earn(whole);
        return whole;
    }

    /** The belts' speed upgrade multiplier for this run. */
    double speedMultiplier() {
        Ladder speed = template.beltSpeed();
        return speed == null ? 1.0 : speed.amount(data.speedLevel(), 1.0);
    }

    /** Called every tick: runs the belt surfaces. */
    void animate(long now) {
        animator.tick(now, sim.speedMultiplier());
    }

    /** Saved ids the template doesn't have (renamed or removed components), to warn about. */
    List<String> unknownOwned() {
        List<String> unknown = new ArrayList<>();
        for (String id : data.owned()) if (template.component(id) == null) unknown.add(id);
        return unknown;
    }

    /** Stops production and takes away everything the owner had on the plot. The plot is as the map has it again. */
    void stop() {
        if (sim != null) sim.reset();
        clearFixtures();
    }

    /** Shows an owned component: its parts (belt surfaces running), and barriers wherever it's solid. */
    private void show(Component component, int index) {
        String group = COMPONENT + component.id();
        List<Part> parts = Placeholders.of(template, component, index);
        animate(plot.fixtures().spawn(group, parts), parts);
        for (Offset barrier : component.common().barriers()) plot.fixtures().place(group, barrier, Material.BARRIER);
    }

    /**
     * Shows a pad for every component the owner could buy next (not owned, everything it requires owned) that isn't
     * shown yet. Ones that need more rebirths show as locked.
     */
    private void showPads() {
        for (Component component : template.components().values()) {
            Offset pad = component.common().pad();
            String group = PAD + component.id();
            if (pad == null || owned.contains(component.id()) || !owned.containsAll(component.common().requires())
                    || plot.fixtures().has(group)) continue;
            boolean locked = component.common().minRebirths() > data.rebirths();
            plot.fixtures().place(group, pad, locked ? Material.POLISHED_BLACKSTONE_PRESSURE_PLATE : Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
            net.kyori.adventure.text.Component text = locked
                    ? Utils.mm("<white><name></white>\n<red>Requires Rebirth <n>", Placeholder.unparsed("name", component.name()),
                    Placeholder.unparsed("n", String.valueOf(component.common().minRebirths())))
                    : Utils.mm("<white><name></white>\n<gold><price>", Placeholder.unparsed("name", component.name()),
                    Placeholder.unparsed("price", Money.format(component.common().price())));
            plot.fixtures().label(group, new Vec(pad.x() + 0.5, pad.y() + PAD_LABEL_HEIGHT, pad.z() + 0.5), text);
            pads.put(pad, component.id());
        }
        if (template.manual() != null) showLadder(MANUAL_PAD, template.manual().upgrades(), data.manualLevel());
        if (template.beltSpeed() != null) showLadder(SPEED_PAD, template.beltSpeed(), data.speedLevel());
    }

    /** A ladder's pad: its next level and price, or that it's maxed (then there's nothing to step on). */
    private void showLadder(String id, Ladder ladder, int level) {
        String group = LADDER + id;
        plot.fixtures().remove(group);
        pads.values().removeIf(id::equals);
        Offset pad = ladder.pad();
        Ladder.Step next = ladder.next(level);
        Vec labelAt = new Vec(pad.x() + 0.5, pad.y() + PAD_LABEL_HEIGHT, pad.z() + 0.5);
        if (next == null) {
            plot.fixtures().label(group, labelAt, Utils.mm("<white><name> <level></white>\n<green>Maxed",
                    Placeholder.unparsed("name", ladder.name()), Placeholder.unparsed("level", Ladder.roman(level))));
            return;
        }
        plot.fixtures().place(group, pad, Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
        plot.fixtures().label(group, labelAt, Utils.mm("<white><name> <level></white>\n<gold><price>",
                Placeholder.unparsed("name", ladder.name()), Placeholder.unparsed("level", Ladder.roman(level + 1)),
                Placeholder.unparsed("price", Money.format(next.price()))));
        pads.put(pad, id);
    }

    /** The component whose pad is at {@code at} (from the plot origin), or {@code null}. */
    @Nullable String padAt(Offset at) {
        return pads.get(at);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Buying
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Buys a component: checks it can be bought, then takes the price from the tycoon balance in one step, and only
     * then owns it and shows it. Items already on the belts carry on.
     *
     * @return what happened; on {@link Purchases.Result#OK} it's bought (save it)
     */
    Purchases.Result buy(String id) {
        if (id.equals(MANUAL_PAD) || id.equals(SPEED_PAD)) return buyLevel(id);
        Purchases.Result result = Purchases.check(template, id, owned, data.rebirths(), data.balance());
        if (result != Purchases.Result.OK) return result;
        Component component = template.component(id);
        if (!data.spend(component.common().price())) return Purchases.Result.TOO_POOR;
        data.own(id, component.common().zone());
        owned.add(id);
        removePad(id);
        int index = new ArrayList<>(template.components().keySet()).indexOf(id);
        show(component, index);
        if (component instanceof Component.Conveyor) showBelts();
        sim.own(List.of(id));
        showPads(); // whatever it unlocked
        return Purchases.Result.OK;
    }

    /** Buys a ladder's next level (pickaxe or belt speed). */
    private Purchases.Result buyLevel(String id) {
        boolean manual = id.equals(MANUAL_PAD);
        Ladder ladder = manual ? template.manual().upgrades() : template.beltSpeed();
        int level = manual ? data.manualLevel() : data.speedLevel();
        Ladder.Step next = ladder == null ? null : ladder.next(level);
        if (next == null) return Purchases.Result.ALREADY_OWNED;
        if (!data.spend(next.price())) return Purchases.Result.TOO_POOR;
        if (manual) {
            data.setManualLevel(level + 1);
            plot.fixtures().remove(MANUAL);
            showManual(); // its sign shows the new value a hit
        } else {
            data.setSpeedLevel(level + 1);
            sim.setSpeedMultiplier(speedMultiplier());
        }
        showLadder(id, ladder, level + 1);
        return Purchases.Result.OK;
    }

    /** What a pad sells, for messages: its name (with the level for ladders) and price. */
    record Offer(String name, long price) {
    }

    @Nullable Offer offer(String id) {
        if (id.equals(MANUAL_PAD) || id.equals(SPEED_PAD)) {
            boolean manual = id.equals(MANUAL_PAD);
            Ladder ladder = manual ? (template.manual() == null ? null : template.manual().upgrades()) : template.beltSpeed();
            int level = manual ? data.manualLevel() : data.speedLevel();
            if (ladder == null) return null;
            Ladder.Step next = ladder.next(level);
            return new Offer(ladder.name() + " " + Ladder.roman(level + 1), next == null ? 0 : next.price());
        }
        Component component = template.component(id);
        return component == null ? null : new Offer(component.name(), component.common().price());
    }

    /** The level just bought on a ladder pad (for messages). */
    String boughtName(String id) {
        if (id.equals(MANUAL_PAD)) return template.manual().upgrades().name() + " " + Ladder.roman(data.manualLevel());
        if (id.equals(SPEED_PAD)) return template.beltSpeed().name() + " " + Ladder.roman(data.speedLevel());
        Component component = template.component(id);
        return component == null ? id : component.name();
    }

    private void removePad(String id) {
        plot.fixtures().remove(PAD + id);
        pads.values().removeIf(id::equals);
    }

    /** Admin testing: owns components without paying (rebuilds the plot). */
    void grant(List<String> ids, long now) {
        for (String id : ids) {
            Component component = template.component(id);
            if (component != null) data.own(id, component.common().zone());
        }
        build(template, now);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Money
    // ------------------------------------------------------------------------------------------------------------

    /** Cents a second, averaged over the last {@value #INCOME_WINDOW} seconds. */
    long incomePerSecond() {
        roll(sim.now() / 20);
        long total = 0;
        for (long cents : incomeBySecond) total = Cents.add(total, cents);
        return total / INCOME_WINDOW;
    }

    private void earn(long cents) {
        data.earn(cents);
        roll(sim.now() / 20);
        int bucket = (int) Math.floorMod(incomeSecond, INCOME_WINDOW);
        incomeBySecond[bucket] = Cents.add(incomeBySecond[bucket], cents);
    }

    /** Moves the income ring on to {@code second}, emptying the seconds it passes. */
    private void roll(long second) {
        if (incomeSecond < 0) incomeSecond = second;
        while (incomeSecond < second) {
            incomeSecond++;
            incomeBySecond[(int) Math.floorMod(incomeSecond, INCOME_WINDOW)] = 0;
            if (second - incomeSecond > INCOME_WINDOW) incomeSecond = second - INCOME_WINDOW; // long gap: skip ahead
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
        fraction += payout;
        long whole = Cents.whole(fraction);
        fraction -= whole;
        earn(whole);
    }

    @Override
    public void discarded(Item item) {
        views.remove(item);
    }
}
