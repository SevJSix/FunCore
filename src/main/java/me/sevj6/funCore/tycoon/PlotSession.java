package me.sevj6.funCore.tycoon;

import me.sevj6.funCore.tycoon.core.Money;
import me.sevj6.funCore.tycoon.core.Purchases;
import me.sevj6.funCore.tycoon.core.data.PlayerData;
import me.sevj6.funCore.tycoon.core.sim.Cents;
import me.sevj6.funCore.tycoon.core.sim.Item;
import me.sevj6.funCore.tycoon.core.sim.PlotSim;
import me.sevj6.funCore.tycoon.core.sim.Segment;
import me.sevj6.funCore.tycoon.core.template.Belt;
import me.sevj6.funCore.tycoon.core.template.Box;
import me.sevj6.funCore.tycoon.core.template.StarShower;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Ladder;
import me.sevj6.funCore.tycoon.core.template.Part;
import me.sevj6.funCore.tycoon.core.template.Offset;
import me.sevj6.funCore.tycoon.core.template.Placeholders;
import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.tycoon.core.template.Vec;
import me.sevj6.funCore.tycoon.plot.BeeKeeper;
import me.sevj6.funCore.tycoon.plot.BeltAnimator;
import me.sevj6.funCore.tycoon.plot.ItemViews;
import me.sevj6.funCore.tycoon.plot.PlotSlot;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
    static final String MANUAL_PAD = "#manual", SPEED_PAD = "#speed", STAR_PAD = "#stars";
    static final String STARS = "stars";
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
    /** The same for what the plot earns by itself (items and earning cosmetics: not clicks or stars). */
    private final long[] autoBySecond = new long[INCOME_WINDOW];
    private long incomeSecond = -1;
    /**
     * Whether an admin used a testing tool on this plot. Its progress was saved just before, and from then on it's
     * never saved again; when the session ends the player goes back to {@link #beforeTest}.
     */
    private boolean testMode;
    private @Nullable PlayerData beforeTest;
    /** Makes the belt surfaces run. */
    private final BeltAnimator animator = new BeltAnimator();
    /** The manual earners' click boxes (the main one, and More Miners' extra ones once owned). */
    private final List<Interaction> manualNodes = new ArrayList<>();
    /** The gate lasers' lever (its look and its click box), while the plot has them. */
    private @Nullable BlockDisplay leverLook;
    private @Nullable Interaction leverBox;
    /** Bees living on owned components. */
    private final List<BeeKeeper> beeKeepers = new ArrayList<>();
    /** Fallen stars lying on the plot, and the tick the next one falls. */
    private final Map<UUID, org.bukkit.entity.Item> stars = new LinkedHashMap<>();
    private long nextStar;
    /** Which way (and how fast, blocks a second) each belt bed block carries players, by bed block. */
    private Map<Offset, Vec> beltFlow = Map.of();

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
        applyBoosts();
    }

    /** The plot's income multiplier: every owned boost added up (+10% and +5% make 1.15x). */
    private void applyBoosts() {
        double multiplier = 1.0;
        for (String id : owned) {
            Component component = template.component(id);
            if (component != null) multiplier += component.common().boost();
        }
        sim.setIncomeMultiplier(multiplier);
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
        manualNodes.clear();
        leverLook = null;
        leverBox = null;
        beeKeepers.clear();
        plot.fixtures().remove(STARS);
        stars.clear();
        beltFlow = Map.of();
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
        beltFlow = Placeholders.flow(template, belts);
    }

    /**
     * How a belt carries a player standing on bed block {@code bed} (from the plot origin): the way items go, at the
     * belt's speed with this plot's speed upgrade, in blocks a second; or {@code null} off the belts.
     */
    @Nullable Vec beltFlowAt(Offset bed) {
        Vec flow = beltFlow.get(bed);
        if (flow == null) return null;
        double multiplier = sim.speedMultiplier();
        return new Vec(flow.x() * multiplier, flow.y() * multiplier, flow.z() * multiplier);
    }

    /** Hands any sliding parts (belt surfaces) to the animator. */
    private void animate(List<Display> displays, List<Part> parts) {
        for (int i = 0; i < displays.size() && i < parts.size(); i++) {
            if (parts.get(i).slide() != null) animator.add(displays.get(i), parts.get(i).slide());
        }
    }

    /** The manual earners: the main one, and More Miners' extra ones once owned. All are worth the same. */
    private void showManual() {
        plot.fixtures().remove(MANUAL);
        manualNodes.clear();
        Template.Manual manual = template.manual();
        if (manual == null) return;
        showManual(manual, manual.at(), manual.parts());
        Component more = template.withFeature("more-miners");
        if (more != null && owned.contains(more.id())) {
            for (Offset spot : manual.extra()) showManual(manual, spot, manual.partsAt(spot));
        }
    }

    /** One manual earner: its parts, a sign, and an invisible box around it that takes clicks. */
    private void showManual(Template.Manual manual, Offset at, List<Part> parts) {
        plot.fixtures().spawn(MANUAL, parts);
        plot.fixtures().label(MANUAL, new Vec(at.x() + 0.5, at.y() + 1.7, at.z() + 0.5), Utils.mm(
                "<gold><bold><name></bold></gold>\n<gray>Click to mine <white><value></white> a hit",
                Placeholder.unparsed("name", manual.name()), Placeholder.unparsed("value", Money.format(Cents.whole(manual.valueAt(data.manualLevel()))))));
        manualNodes.add(plot.fixtures().spawn(MANUAL, new Vec(at.x() + 0.5, at.y(), at.z() + 0.5), Interaction.class, box -> {
            box.setInteractionWidth(1.3f);
            box.setInteractionHeight(1.3f);
            box.setResponsive(true);
        }));
    }

    /** Whether {@code entity} is one of this plot's manual earners. */
    boolean isManualNode(Entity entity) {
        return manualNodes.contains(entity);
    }

    /** One click on the manual earner: cents earned (already added to the balance). */
    long mine() {
        Template.Manual manual = template.manual();
        if (manual == null) return 0;
        fraction += manual.valueAt(data.manualLevel()) * sim.incomeMultiplier();
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

    /** Called every tick: runs the belt surfaces and the bees, and drops stars. */
    void animate(long now) {
        animator.tick(now, sim.speedMultiplier());
        for (BeeKeeper keeper : beeKeepers) keeper.tick(now);
        tickStars(now);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Star shower
    // ------------------------------------------------------------------------------------------------------------

    private void tickStars(long now) {
        StarShower shower = template.starShower();
        StarShower.Level level = shower == null ? null : shower.at(data.starLevel());
        if (level == null || now < nextStar) return;
        nextStar = now + level.every();
        stars.values().removeIf(star -> !star.isValid());
        if (stars.size() >= shower.cap()) return; // no more lying around than this: picking some up makes room
        Offset spot = landingSpot(shower);
        if (spot == null) return;
        Location sky = plot.fixtures().at(new Vec(spot.x() + 0.5, spot.y() + openAbove(spot, shower.dropHeight()), spot.z() + 0.5));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        org.bukkit.entity.Item star = sky.getWorld().dropItem(sky, ItemStack.of(Material.NETHER_STAR), item -> {
            item.setUnlimitedLifetime(true); // never despawns
            item.setPickupDelay(10);
            item.setVelocity(new org.bukkit.util.Vector(random.nextDouble(-0.03, 0.03), -0.2, random.nextDouble(-0.03, 0.03)));
        });
        plot.fixtures().adopt(STARS, star);
        stars.put(star.getUniqueId(), star);
    }

    /**
     * How high above {@code spot} a star can start and still fall straight onto it: up to {@code height}, but never
     * at or above the first solid block overhead (the roof, a canopy), so it lands on the floor, not on top of that.
     */
    private double openAbove(Offset spot, int height) {
        org.bukkit.block.Block block = plot.fixtures().block(spot);
        for (int up = 1; up <= height; up++) {
            if (!block.getRelative(0, up, 0).isPassable()) return Math.max(0.2, up - 0.6);
        }
        return height;
    }

    /** A random open spot to land on in the shower's areas (nothing solid in it, floor under it), or {@code null}. */
    private @Nullable Offset landingSpot(StarShower shower) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int total = 0;
        for (Box area : shower.areas()) total += area.blocks().size();
        for (int attempt = 0; attempt < 8; attempt++) {
            int pick = random.nextInt(total);
            for (Box area : shower.areas()) {
                int size = area.blocks().size();
                if (pick >= size) {
                    pick -= size;
                    continue;
                }
                Offset spot = area.blocks().get(pick);
                org.bukkit.block.Block block = plot.fixtures().block(spot);
                if (block.isEmpty() && block.getRelative(org.bukkit.block.BlockFace.DOWN).isSolid()) return spot;
                break;
            }
        }
        return null;
    }

    /** Whether {@code item} is one of this plot's fallen stars. */
    boolean isStar(org.bukkit.entity.Item item) {
        return stars.containsKey(item.getUniqueId());
    }

    /** The owner picked up a star: it's gone, and they earn its worth. @return cents earned */
    long collectStar(org.bukkit.entity.Item item) {
        stars.remove(item.getUniqueId());
        int amount = item.getItemStack().getAmount(); // stars that landed together merge
        item.remove();
        StarShower.Level level = template.starShower() == null ? null : template.starShower().at(data.starLevel());
        if (level == null) return 0;
        fraction += level.value() * amount * sim.incomeMultiplier();
        long whole = Cents.whole(fraction);
        fraction -= whole;
        earn(whole);
        return whole;
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
        if ("gate-lasers".equals(component.common().feature()) && template.gateLasers() != null) showLever(group);
        if (component.common().bees() != null) beeKeepers.add(new BeeKeeper(plot.fixtures(), group, component.common().bees()));
        if ("more-miners".equals(component.common().feature()) && manualNodes.size() > 0) showManual(); // bought now: add them
    }

    // ------------------------------------------------------------------------------------------------------------
    // Gate lasers
    // ------------------------------------------------------------------------------------------------------------

    /** The lever that turns the gate lasers on and off: a lever on the floor, and a box around it that takes clicks. */
    private void showLever(String group) {
        Offset at = template.gateLasers().lever();
        leverLook = plot.fixtures().spawn(group, new Vec(at.x(), at.y(), at.z()), BlockDisplay.class,
                look -> look.setBlock(leverData(data.gateLocked())));
        leverBox = plot.fixtures().spawn(group, new Vec(at.x() + 0.5, at.y(), at.z() + 0.5), Interaction.class, box -> {
            box.setInteractionWidth(0.7f);
            box.setInteractionHeight(0.6f);
            box.setResponsive(true);
        });
        plot.fixtures().label(group, new Vec(at.x() + 0.5, at.y() + 0.9, at.z() + 0.5),
                Utils.mm("<red>Gate Lasers</red>\n<gray>click to switch"));
    }

    private org.bukkit.block.data.BlockData leverData(boolean on) {
        String facing = template.gateLasers().leverFacing();
        return Material.LEVER.createBlockData(facing == null ? "[face=floor,facing=south,powered=" + on + "]"
                : "[face=wall,facing=" + facing + ",powered=" + on + "]");
    }

    boolean isLever(Entity entity) {
        return leverBox != null && leverBox.equals(entity);
    }

    /** Whether the gate lasers are up: the plot has them and the owner hasn't switched them off. */
    boolean lasersOn() {
        Template.GateLasers lasers = template.gateLasers();
        Component switcher = template.withFeature("gate-lasers");
        return lasers != null && switcher != null && owned.contains(switcher.id()) && data.gateLocked();
    }

    /** Pulls the lever: lasers on or off. @return whether they're on now */
    boolean toggleLasers() {
        data.setGateLocked(!data.gateLocked());
        if (leverLook != null && leverLook.isValid()) leverLook.setBlock(leverData(data.gateLocked()));
        return data.gateLocked();
    }

    /** Whether a block (from the plot origin) is inside the plot's footprint. */
    boolean inFootprint(Offset at) {
        return at.x() >= 0 && at.x() < template.size().x() && at.z() >= 0 && at.z() < template.size().z();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Earning cosmetics
    // ------------------------------------------------------------------------------------------------------------

    /** Cents a second the owned earning cosmetics bring in on their own. */
    double passiveIncome() {
        double cents = 0;
        for (String id : owned) {
            Component component = template.component(id);
            if (component != null) cents += component.common().income();
        }
        return cents * sim.incomeMultiplier();
    }

    /** Called once a second: pays the earning cosmetics. */
    void earnPassive() {
        double cents = passiveIncome();
        if (cents <= 0) return;
        fraction += cents;
        long whole = Cents.whole(fraction);
        fraction -= whole;
        earnAuto(whole);
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
        if (template.starShower() != null) showLadder(STAR_PAD, template.starShower().ladder(), data.starLevel());
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
        if (id.equals(MANUAL_PAD) || id.equals(SPEED_PAD) || id.equals(STAR_PAD)) return buyLevel(id);
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
        applyBoosts();
        showPads(); // whatever it unlocked
        return Purchases.Result.OK;
    }

    /** Buys a ladder's next level (pickaxe or belt speed). */
    private Purchases.Result buyLevel(String id) {
        boolean manual = id.equals(MANUAL_PAD);
        Ladder ladder = ladderFor(id);
        int level = levelFor(id);
        Ladder.Step next = ladder == null ? null : ladder.next(level);
        if (next == null) return Purchases.Result.ALREADY_OWNED;
        if (!data.spend(next.price())) return Purchases.Result.TOO_POOR;
        if (id.equals(STAR_PAD)) {
            data.setStarLevel(level + 1);
        } else if (manual) {
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

    private @Nullable Ladder ladderFor(String id) {
        return switch (id) {
            case MANUAL_PAD -> template.manual() == null ? null : template.manual().upgrades();
            case SPEED_PAD -> template.beltSpeed();
            case STAR_PAD -> template.starShower() == null ? null : template.starShower().ladder();
            default -> null;
        };
    }

    private int levelFor(String id) {
        return switch (id) {
            case MANUAL_PAD -> data.manualLevel();
            case SPEED_PAD -> data.speedLevel();
            case STAR_PAD -> data.starLevel();
            default -> 0;
        };
    }

    /** What a pad sells, for messages: its name (with the level for ladders) and price. */
    record Offer(String name, long price) {
    }

    @Nullable Offer offer(String id) {
        if (id.startsWith("#")) {
            Ladder ladder = ladderFor(id);
            int level = levelFor(id);
            if (ladder == null) return null;
            Ladder.Step next = ladder.next(level);
            return new Offer(ladder.name() + " " + Ladder.roman(level + 1), next == null ? 0 : next.price());
        }
        Component component = template.component(id);
        return component == null ? null : new Offer(component.name(), component.common().price());
    }

    /** The level just bought on a ladder pad (for messages). */
    String boughtName(String id) {
        if (id.startsWith("#")) return ladderFor(id).name() + " " + Ladder.roman(levelFor(id));
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

    /** Income the plot makes by itself (items, earning cosmetics): it also counts toward what it earns while away. */
    private void earnAuto(long cents) {
        earn(cents);
        int bucket = (int) Math.floorMod(incomeSecond, INCOME_WINDOW);
        autoBySecond[bucket] = Cents.add(autoBySecond[bucket], cents);
    }

    /** Cents a second the plot earns by itself, averaged over the last {@value #INCOME_WINDOW} seconds. */
    double autoIncomePerSecond() {
        roll(sim.now() / 20);
        long total = 0;
        for (long cents : autoBySecond) total = Cents.add(total, cents);
        return (double) total / INCOME_WINDOW;
    }

    /** Moves the income ring on to {@code second}, emptying the seconds it passes. */
    private void roll(long second) {
        if (incomeSecond < 0) incomeSecond = second;
        while (incomeSecond < second) {
            incomeSecond++;
            incomeBySecond[(int) Math.floorMod(incomeSecond, INCOME_WINDOW)] = 0;
            autoBySecond[(int) Math.floorMod(incomeSecond, INCOME_WINDOW)] = 0;
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
        earnAuto(whole);
    }

    @Override
    public void discarded(Item item) {
        views.remove(item);
    }
}
