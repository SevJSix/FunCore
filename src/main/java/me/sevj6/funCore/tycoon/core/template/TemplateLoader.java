package me.sevj6.funCore.tycoon.core.template;

import me.sevj6.funCore.tycoon.core.Money;
import me.sevj6.funCore.tycoon.core.sim.Cents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a parsed {@code template.yml} (plain maps and lists, as SnakeYAML reads it) into a {@link Template}. Problems
 * are collected, each naming where it is and what's wrong, instead of stopping at the first one; a template with any
 * error isn't used. Money in the file is in dollars ({@code price: 12.5}); the template holds cents.
 *
 * <pre>
 * schema: 2
 * size: [64, 32, 64]
 * base:
 *   spawn: [32, 1, 4]
 *   spawn-yaw: 180
 *   collectors: { main: [32, 1, 58] }
 *   belts:                               # free, always there
 *     vault-belt: { speed: 2, path: [[8, 1, 40], [8, 1, 56]], collector: vault }
 *   rebirth-pad: [36, 1, 6]
 *   vault: { door: [[19, 1, 12], [21, 3, 12]], unlock-rebirths: 1 }   # sealed until then
 *   gate-label: { at: [20, 4, 44], front: south }   # the plot's name sign, on that block's front (optional)
 *   manual:                              # clicked to earn by hand (optional)
 *     name: Ore Vein
 *     at: [26, 1, 40]
 *     parts: [...]                       # as for components
 *     value: 0.10                        # dollars a click to start
 *     max-clicks-per-second: 8
 *     upgrades: { name: Pickaxe, pad: [24, 1, 40], levels: [{ price: 73, value: 0.23 }, ...] }
 *   belt-speed:                          # belt speed upgrades (optional)
 *     name: Belt Speed
 *     pad: [29, 1, 37]
 *     levels: [{ price: 540, speed: 1.5 }, ...]   # speed = how many times the belts' own speed
 *   gate-lasers:                         # where gate lasers go once a component switches them on (optional)
 *     from: [17, 1, 44]
 *     to: [23, 3, 44]
 *     lever: [16, 2, 43]
 *     lever-facing: north                # on a wall, facing this way; without it, on the floor
 *   star-shower:                         # stars falling onto the plot, picked up for money (optional)
 *     name: Star Shower
 *     pad: [17, 1, 38]
 *     cap: 250                           # most stars lying on a plot at once
 *     drop-height: 14
 *     areas: [{ from: [1, 1, 13], to: [39, 1, 24] }]   # where they may land (above the floor)
 *     levels: [{ price: 150, value: 2.96, every: 8 }]  # dollars; value a star; seconds between stars
 *   beds:                                # the map's belt beds; every belt must run on them (optional)
 *     - { from: [4, 0, 30], to: [35, 0, 30] }
 * start-with: [main-belt, wooden-dropper]
 * components:
 *   main-belt:
 *     type: conveyor
 *     name: Main Belt
 *     price: 50
 *     pad: [30, 1, 6]
 *     speed: 2
 *     path: [[20, 1, 20], [32, 1, 20], [32, 1, 56]]
 *     collector: main                    # or merge: { belt: other-belt, waypoint: 2 }
 *   wooden-dropper:
 *     type: dropper
 *     requires: [main-belt]
 *     spout: [20, 4, 20]
 *     interval: 40                       # ticks
 *     value: 1                           # dollars an item
 *     item: { display: item, material: COAL, scale: 0.5 }
 *     route: { belt: main-belt, waypoint: 0 }
 *   smelter:
 *     type: upgrader
 *     at: { belt: main-belt, waypoint: 1 }
 *     multiplier: 2
 * </pre>
 * Every component also takes {@code name}, {@code price}, {@code requires}, {@code min-rebirths} (default 0),
 * {@code zone} ({@code main} or {@code vault}, default main), {@code pad}, {@code outside} (true lets it reach outside
 * the plot's footprint; default false), {@code income} (dollars a second it earns on its own while owned),
 * {@code feature} ({@code gate-lasers} switches the gate lasers on), {@code boost} (extra income for the whole plot,
 * 0.1 = +10%), {@code bees} ({@code { home: [x, y, z], count: 3, range: 10 }}: real bees that live on it), and how
 * it looks:
 * <pre>
 *     parts:                             # display entities; without any, placeholder parts are used
 *       - { block: "minecraft:oak_planks", at: [29, 1, 26], size: [2, 3, 2] }      # at = the block's min corner
 *       - { item: "minecraft:coal", at: [29.5, 2.5, 30.5], size: [0.5, 0.5, 0.5] } # at = the item's centre
 *     barriers:                          # made solid while it's owned: single blocks, or from/to boxes
 *       - [29, 1, 26]
 *       - { from: [29, 1, 26], to: [30, 3, 27] }
 * </pre>
 */
public final class TemplateLoader {

    /** 2: plots on the pasted map (no base build, no cleared box). */
    public static final int SCHEMA = 2;

    /** The template, or {@code null} if there were errors. */
    public record Result(@Nullable Template template, List<String> errors) {

        public boolean ok() {
            return template != null;
        }
    }

    private final List<String> errors = new ArrayList<>();

    private TemplateLoader() {
    }

    /** Reads and validates a template ({@link TemplateValidator} runs on it too). */
    public static Result load(@Nullable Object root) {
        TemplateLoader loader = new TemplateLoader();
        Template template = loader.read(root);
        if (template != null) loader.errors.addAll(TemplateValidator.validate(template));
        return new Result(loader.errors.isEmpty() ? template : null, List.copyOf(loader.errors));
    }

    private @Nullable Template read(@Nullable Object root) {
        Map<String, Object> top = map(root, "the file");
        if (top == null) return null;
        Object schema = top.get("schema");
        if (schema != null && !(schema instanceof Number n && n.intValue() == SCHEMA)) {
            error("the file", "schema is " + schema + ", but this version reads schema " + SCHEMA);
        }
        Offset size = offset(top.get("size"), "size");
        if (size != null && (size.x() < 1 || size.y() < 1 || size.z() < 1)) error("size", "every side must be at least 1");

        Map<String, Object> base = map(top.get("base"), "base");
        if (base == null) return null;
        Offset spawn = offset(base.get("spawn"), "base.spawn");
        double spawnYaw = number(base.get("spawn-yaw"), "base.spawn-yaw", 0);

        Map<String, Offset> collectors = new LinkedHashMap<>();
        Map<String, Object> collectorMap = map(base.get("collectors"), "base.collectors");
        if (collectorMap != null) {
            collectorMap.forEach((id, value) -> {
                Offset at = offset(value, "base.collectors." + id);
                if (at != null) collectors.put(id, at);
            });
            if (collectorMap.isEmpty()) error("base.collectors", "needs at least one collector");
        }

        Map<String, Belt> baseBelts = new LinkedHashMap<>();
        Object beltsValue = base.get("belts");
        if (beltsValue != null) {
            Map<String, Object> belts = map(beltsValue, "base.belts");
            if (belts != null) {
                belts.forEach((id, value) -> {
                    Map<String, Object> section = map(value, "base.belts." + id);
                    Belt belt = section == null ? null : belt(id, section, "base belt '" + id + "'");
                    if (belt != null) baseBelts.put(id, belt);
                });
            }
        }
        Offset rebirthPad = base.get("rebirth-pad") == null ? null : offset(base.get("rebirth-pad"), "base.rebirth-pad");
        Template.VaultDoor vaultDoor = null;
        if (base.get("vault") != null) {
            Map<String, Object> vault = map(base.get("vault"), "base.vault");
            if (vault != null) {
                Object door = vault.get("door");
                int unlock = (int) number(vault.get("unlock-rebirths"), "base.vault.unlock-rebirths", 1);
                if (unlock < 0) error("base.vault", "unlock-rebirths can't be negative");
                if (door instanceof List<?> corners && corners.size() == 2) {
                    Offset a = offset(corners.get(0), "base.vault.door[0]"), b = offset(corners.get(1), "base.vault.door[1]");
                    if (a != null && b != null) {
                        vaultDoor = new Template.VaultDoor(a, b, Math.max(0, unlock));
                        if (vaultDoor.blocks().size() > 64) error("base.vault.door", "is more than 64 blocks; is it the doorway?");
                    }
                } else {
                    error("base.vault.door", "must be two corners, like [[19, 1, 12], [21, 3, 12]]");
                }
            }
        }

        Map<String, Offset> trophySlots = new LinkedHashMap<>();
        if (base.get("trophies") != null) {
            Map<String, Object> trophies = map(base.get("trophies"), "base.trophies");
            if (trophies != null) {
                trophies.forEach((id, value) -> {
                    Offset at = offset(value, "base.trophies." + id);
                    if (at != null) trophySlots.put(id, at);
                });
            }
        }

        Template.GateSign gateLabel = gateSign(base.get("gate-label"));
        Template.Manual manual = base.get("manual") == null ? null : manual(base.get("manual"));
        Ladder beltSpeed = base.get("belt-speed") == null ? null : ladder(base.get("belt-speed"), "base.belt-speed", "speed", 1);
        StarShower starShower = base.get("star-shower") == null ? null : starShower(base.get("star-shower"));
        Template.GateLasers gateLasers = null;
        if (base.get("gate-lasers") != null) {
            Map<String, Object> lasers = map(base.get("gate-lasers"), "base.gate-lasers");
            Offset from = lasers == null ? null : offset(lasers.get("from"), "base.gate-lasers from");
            Offset to = lasers == null ? null : offset(lasers.get("to"), "base.gate-lasers to");
            Offset lever = lasers == null ? null : offset(lasers.get("lever"), "base.gate-lasers lever");
            String facing = lasers == null ? null : string(lasers.get("lever-facing"), "base.gate-lasers lever-facing", false);
            if (facing != null) {
                facing = facing.toLowerCase(Locale.ROOT);
                if (!List.of("north", "south", "east", "west").contains(facing)) {
                    error("base.gate-lasers", "lever-facing must be north, south, east or west");
                    facing = null;
                }
            }
            if (from != null && to != null && lever != null) gateLasers = new Template.GateLasers(new Box(from, to), lever, facing);
        }
        List<Box> beds = new ArrayList<>();
        if (base.get("beds") != null) {
            if (base.get("beds") instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Map<String, Object> bed = map(list.get(i), "base.beds[" + i + "]");
                    Offset from = bed == null ? null : offset(bed.get("from"), "base.beds[" + i + "] from");
                    Offset to = bed == null ? null : offset(bed.get("to"), "base.beds[" + i + "] to");
                    if (from != null && to != null) beds.add(new Box(from, to));
                }
            } else {
                error("base.beds", "must be a list of { from: [x, y, z], to: [x, y, z] }");
            }
        }

        List<String> startWith = strings(top.get("start-with"), "start-with");

        Map<String, Component> components = new LinkedHashMap<>();
        Map<String, Object> componentMap = map(top.get("components"), "components");
        if (componentMap != null) {
            componentMap.forEach((id, value) -> {
                Map<String, Object> section = map(value, "component '" + id + "'");
                if (section == null) return;
                if (baseBelts.containsKey(id)) {
                    error("component '" + id + "'", "has the same id as a base belt");
                    return;
                }
                Component component = component(id, section);
                if (component != null) components.put(id, component);
            });
        }

        if (!errors.isEmpty() || size == null || spawn == null) return null;
        return new Template(size, spawn, (float) spawnYaw, collectors, baseBelts, rebirthPad, vaultDoor, trophySlots, beds,
                gateLabel, manual, beltSpeed, gateLasers, starShower, startWith, components);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Components
    // ------------------------------------------------------------------------------------------------------------

    private @Nullable Component component(String id, Map<String, Object> section) {
        String where = "component '" + id + "'";
        String type = string(section.get("type"), where + " type", true);
        if (type == null) return null;
        Component.Common common = common(id, section, where);
        if (common == null) return null;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "dropper" -> dropper(common, section, where);
            case "conveyor" -> {
                Belt belt = belt(id, section, where);
                yield belt == null ? null : new Component.Conveyor(common, belt);
            }
            case "upgrader" -> upgrader(common, section, where);
            case "wall", "decor" -> new Component.Wall(common);
            default -> {
                error(where, "type '" + type + "' isn't one of dropper, conveyor, upgrader, wall");
                yield null;
            }
        };
    }

    private Component.@Nullable Common common(String id, Map<String, Object> section, String where) {
        String name = string(section.get("name"), where + " name", false);
        long price = money(section.get("price"), where + " price", 0);
        List<String> requires = strings(section.get("requires"), where + " requires");
        int minRebirths = (int) number(section.get("min-rebirths"), where + " min-rebirths", 0);
        if (minRebirths < 0) error(where, "min-rebirths can't be negative");
        String zoneName = string(section.get("zone"), where + " zone", false);
        Component.Zone zone = Component.Zone.MAIN;
        if (zoneName != null) {
            try {
                zone = Component.Zone.valueOf(zoneName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                error(where, "zone '" + zoneName + "' isn't main or vault");
            }
        }
        Offset pad = section.get("pad") == null ? null : offset(section.get("pad"), where + " pad");
        List<Part> parts = parts(section.get("parts"), where);
        List<Offset> barriers = barriers(section.get("barriers"), where);
        boolean outside = section.get("outside") instanceof Boolean flag && flag;
        if (section.get("outside") != null && !(section.get("outside") instanceof Boolean)) error(where, "outside must be true or false");
        double income = number(section.get("income"), where + " income", 0) * 100.0;
        if (income < 0) error(where, "income can't be negative");
        String feature = string(section.get("feature"), where + " feature", false);
        if (feature != null && !Component.Common.FEATURES.contains(feature)) {
            error(where, "feature '" + feature + "' isn't one of " + Component.Common.FEATURES);
        }
        double boost = number(section.get("boost"), where + " boost", 0);
        if (boost < 0 || boost > 10) error(where, "boost must be between 0 and 10 (0.1 is +10%)");
        Component.Bees bees = null;
        if (section.get("bees") != null) {
            Map<String, Object> hive = map(section.get("bees"), where + " bees");
            Vec home = hive == null ? null : vec(hive.get("home"), where + " bees home");
            int count = hive == null ? 0 : (int) number(hive.get("count"), where + " bees count", 3);
            double range = hive == null ? 0 : number(hive.get("range"), where + " bees range", 10);
            if (count < 1 || count > 10) error(where, "bees count must be between 1 and 10");
            if (!(range >= 2) || range > 32) error(where, "bees range must be between 2 and 32 blocks");
            if (home != null) bees = new Component.Bees(home, Math.max(1, Math.min(10, count)), range);
        }
        return new Component.Common(id, name == null ? id : name, price, requires, minRebirths, zone, pad, parts, barriers,
                outside, Math.max(0, income), feature, Math.max(0, boost), bees);
    }

    private Component.@Nullable Dropper dropper(Component.Common common, Map<String, Object> section, String where) {
        Offset spout = offset(section.get("spout"), where + " spout");
        int interval = (int) number(section.get("interval"), where + " interval", -1);
        if (interval < 1) error(where, "interval must be at least 1 tick");
        double value = number(section.get("value"), where + " value", -1) * 100.0;
        if (!(value > 0) || value > Cents.MAX) error(where, "value must be more than $0 and at most " + Money.format(Cents.MAX));

        Component.Visual visual = new Component.Visual(false, "COAL", 0.5);
        if (section.get("item") != null) {
            Map<String, Object> item = map(section.get("item"), where + " item");
            if (item != null) {
                String display = string(item.get("display"), where + " item.display", false);
                boolean block = display != null && display.equalsIgnoreCase("block");
                if (display != null && !block && !display.equalsIgnoreCase("item")) {
                    error(where, "item.display must be item or block");
                }
                String material = string(item.get("material"), where + " item.material", true);
                double scale = number(item.get("scale"), where + " item.scale", 0.5);
                if (!(scale > 0) || scale > 4) error(where, "item.scale must be more than 0 and at most 4");
                if (material != null) visual = new Component.Visual(block, material.toUpperCase(Locale.ROOT), scale);
            }
        }

        Map<String, Object> route = map(section.get("route"), where + " route");
        String belt = route == null ? null : string(route.get("belt"), where + " route.belt", true);
        int waypoint = route == null ? 0 : (int) number(route.get("waypoint"), where + " route.waypoint", 0);
        if (spout == null || belt == null) return null;
        return new Component.Dropper(common, spout, interval, visual, value, belt, waypoint);
    }

    private Component.@Nullable Upgrader upgrader(Component.Common common, Map<String, Object> section, String where) {
        Map<String, Object> at = map(section.get("at"), where + " at");
        String belt = at == null ? null : string(at.get("belt"), where + " at.belt", true);
        int waypoint = at == null ? 0 : (int) number(at.get("waypoint"), where + " at.waypoint", 0);
        double multiplier = number(section.get("multiplier"), where + " multiplier", -1);
        if (!(multiplier > 0) || multiplier > 1_000_000) error(where, "multiplier must be more than 0 and at most 1000000");
        String swap = string(section.get("swap"), where + " swap", false);
        String particle = string(section.get("particle"), where + " particle", false);
        String sound = string(section.get("sound"), where + " sound", false);
        if (belt == null) return null;
        return new Component.Upgrader(common, belt, waypoint, multiplier,
                swap == null ? null : swap.toUpperCase(Locale.ROOT), particle, sound);
    }

    private @Nullable Belt belt(String id, Map<String, Object> section, String where) {
        double speed = number(section.get("speed"), where + " speed", -1);
        if (!(speed > 0) || speed > 100) error(where, "speed must be more than 0 and at most 100 blocks a second");
        List<Offset> path = new ArrayList<>();
        Object pathValue = section.get("path");
        if (pathValue instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Offset point = offset(list.get(i), where + " path[" + i + "]");
                if (point != null) path.add(point);
            }
        } else {
            error(where, "path must be a list of waypoints like [[0, 1, 0], [5, 1, 0]]");
        }
        if (pathValue instanceof List<?> && path.isEmpty()) error(where, "path needs at least one waypoint");

        Object collector = section.get("collector");
        Object merge = section.get("merge");
        Belt.End end = null;
        if ((collector == null) == (merge == null)) {
            error(where, "needs exactly one of 'collector: <id>' or 'merge: { belt: <id>, waypoint: <n> }'");
        } else if (collector != null) {
            String target = string(collector, where + " collector", true);
            if (target != null) end = new Belt.ToCollector(target);
        } else {
            Map<String, Object> into = map(merge, where + " merge");
            String target = into == null ? null : string(into.get("belt"), where + " merge.belt", true);
            int waypoint = into == null ? 0 : (int) number(into.get("waypoint"), where + " merge.waypoint", 0);
            if (target != null) end = new Belt.Merge(target, waypoint);
        }
        if (end == null || path.isEmpty() || !(speed > 0)) return null;
        return new Belt(id, speed, path, end);
    }

    private Template.@Nullable Manual manual(Object value) {
        Map<String, Object> section = map(value, "base.manual");
        if (section == null) return null;
        String name = string(section.get("name"), "base.manual name", false);
        Offset at = offset(section.get("at"), "base.manual at");
        List<Part> parts = parts(section.get("parts"), "base.manual");
        double cents = number(section.get("value"), "base.manual value", -1) * 100.0;
        if (!(cents > 0)) error("base.manual", "value must be more than $0");
        int maxClicks = (int) number(section.get("max-clicks-per-second"), "base.manual max-clicks-per-second", 8);
        if (maxClicks < 1 || maxClicks > 40) error("base.manual", "max-clicks-per-second must be between 1 and 40");
        Ladder upgrades = section.get("upgrades") == null
                ? new Ladder("Upgrade", at == null ? Offset.ZERO : at, List.of())
                : ladder(section.get("upgrades"), "base.manual upgrades", "value", 100.0);
        if (at == null || upgrades == null || !(cents > 0)) return null;
        return new Template.Manual(name == null ? "Ore Vein" : name, at, parts, cents, maxClicks, upgrades);
    }

    /**
     * A ladder: {@code { name, pad, levels: [{ price, <amountKey> }, ...] }}. Prices are dollars; amounts are
     * multiplied by {@code scale} (100 for money, so it's kept in cents).
     */
    private @Nullable Ladder ladder(Object value, String where, String amountKey, double scale) {
        Map<String, Object> section = map(value, where);
        if (section == null) return null;
        String name = string(section.get("name"), where + " name", false);
        Offset pad = offset(section.get("pad"), where + " pad");
        List<Ladder.Step> steps = new ArrayList<>();
        if (section.get("levels") instanceof List<?> levels) {
            for (int i = 0; i < levels.size(); i++) {
                String at = where + " levels[" + i + "]";
                Map<String, Object> level = map(levels.get(i), at);
                if (level == null) continue;
                long price = money(level.get("price"), at + " price", -1);
                double amount = number(level.get(amountKey), at + " " + amountKey, -1) * scale;
                if (price < 0) error(at, "needs a price");
                if (!(amount > 0)) error(at, amountKey + " must be more than 0");
                if (!steps.isEmpty() && price < steps.getLast().price()) error(at, "costs less than the level before it");
                if (!steps.isEmpty() && amount <= steps.getLast().amount()) error(at, amountKey + " isn't more than the level before it");
                steps.add(new Ladder.Step(Math.max(0, price), amount));
            }
        } else {
            error(where, "levels must be a list like [{ price: 100, " + amountKey + ": 2 }]");
        }
        if (pad == null) return null;
        return new Ladder(name == null ? "Upgrade" : name, pad, steps);
    }

    private @Nullable StarShower starShower(Object value) {
        String where = "base.star-shower";
        Map<String, Object> section = map(value, where);
        if (section == null) return null;
        String name = string(section.get("name"), where + " name", false);
        Offset pad = offset(section.get("pad"), where + " pad");
        int cap = (int) number(section.get("cap"), where + " cap", 250);
        if (cap < 1 || cap > 1000) error(where, "cap must be between 1 and 1000");
        int height = (int) number(section.get("drop-height"), where + " drop-height", 14);
        if (height < 1 || height > 64) error(where, "drop-height must be between 1 and 64");
        List<Box> areas = new ArrayList<>();
        if (section.get("areas") instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Map<String, Object> area = map(list.get(i), where + " areas[" + i + "]");
                Offset from = area == null ? null : offset(area.get("from"), where + " areas[" + i + "] from");
                Offset to = area == null ? null : offset(area.get("to"), where + " areas[" + i + "] to");
                if (from != null && to != null) areas.add(new Box(from, to));
            }
        }
        if (areas.isEmpty()) error(where, "needs at least one area for stars to land in");
        List<StarShower.Level> levels = new ArrayList<>();
        if (section.get("levels") instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String at = where + " levels[" + i + "]";
                Map<String, Object> level = map(list.get(i), at);
                if (level == null) continue;
                long price = money(level.get("price"), at + " price", -1);
                double cents = number(level.get("value"), at + " value", -1) * 100.0;
                int every = (int) Math.round(number(level.get("every"), at + " every", -1) * 20);
                if (price < 0) error(at, "needs a price");
                if (!(cents > 0)) error(at, "value must be more than $0");
                if (every < 1) error(at, "every must be at least 0.05 seconds");
                if (!levels.isEmpty() && price < levels.getLast().price()) error(at, "costs less than the level before it");
                levels.add(new StarShower.Level(Math.max(0, price), cents, Math.max(1, every)));
            }
        } else {
            error(where, "levels must be a list like [{ price: 150, value: 3, every: 8 }]");
        }
        if (pad == null || areas.isEmpty()) return null;
        return new StarShower(name == null ? "Star Shower" : name, pad, cap, height, areas, levels);
    }

    /** {@code gate-label}: {@code { at: [x, y, z], front: north|south|east|west }}, or just {@code [x, y, z]}. */
    private Template.@Nullable GateSign gateSign(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof List<?>) {
            Offset at = offset(value, "base.gate-label");
            return at == null ? null : new Template.GateSign(at, null);
        }
        Map<String, Object> sign = map(value, "base.gate-label");
        if (sign == null) return null;
        Offset at = offset(sign.get("at"), "base.gate-label at");
        String front = string(sign.get("front"), "base.gate-label front", false);
        if (front != null) {
            front = front.toLowerCase(Locale.ROOT);
            if (!List.of("north", "south", "east", "west").contains(front)) {
                error("base.gate-label", "front must be north, south, east or west, not '" + front + "'");
                return null;
            }
        }
        return at == null ? null : new Template.GateSign(at, front);
    }

    /** {@code parts}: each {@code { block: <data>, at: [x, y, z], size: [x, y, z] }} or the same with {@code item}. */
    private List<Part> parts(@Nullable Object value, String where) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            error(where, "parts must be a list");
            return List.of();
        }
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String at = where + " parts[" + i + "]";
            Map<String, Object> part = map(list.get(i), at);
            if (part == null) continue;
            Object block = part.get("block"), item = part.get("item");
            if ((block == null) == (item == null)) {
                error(at, "needs exactly one of 'block: <block data>' or 'item: <item>'");
                continue;
            }
            String data = string(block != null ? block : item, at + (block != null ? " block" : " item"), true);
            Vec position = vec(part.get("at"), at + " at");
            Vec size = part.get("size") == null ? new Vec(1, 1, 1) : vec(part.get("size"), at + " size");
            if (size != null && (!(size.x() > 0) || !(size.y() > 0) || !(size.z() > 0)
                    || size.x() > 64 || size.y() > 64 || size.z() > 64)) {
                error(at, "every side of size must be more than 0 and at most 64");
                continue;
            }
            if (data != null && position != null && size != null) parts.add(new Part(block != null, data, position, size));
        }
        return parts;
    }

    /** {@code barriers}: single blocks {@code [x, y, z]}, or boxes {@code { from: [x, y, z], to: [x, y, z] }}. */
    private List<Offset> barriers(@Nullable Object value, String where) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            error(where, "barriers must be a list");
            return List.of();
        }
        List<Offset> barriers = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String at = where + " barriers[" + i + "]";
            Object entry = list.get(i);
            if (entry instanceof Map<?, ?>) {
                Map<String, Object> box = map(entry, at);
                Offset from = box == null ? null : offset(box.get("from"), at + " from");
                Offset to = box == null ? null : offset(box.get("to"), at + " to");
                if (from == null || to == null) continue;
                List<Offset> blocks = Offset.box(from, to);
                if (blocks.size() > 512) {
                    error(at, "is more than 512 blocks");
                    continue;
                }
                barriers.addAll(blocks);
            } else {
                Offset single = offset(entry, at);
                if (single != null) barriers.add(single);
            }
        }
        return barriers;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------------------------------------------------

    private void error(String where, String message) {
        errors.add(where + ": " + message);
    }

    @SuppressWarnings("unchecked")
    private @Nullable Map<String, Object> map(@Nullable Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<Object, Object>) map).forEach((key, entry) -> result.put(String.valueOf(key), entry));
            return result;
        }
        error(where, value == null ? "is missing" : "must be a section");
        return null;
    }

    private @Nullable String string(@Nullable Object value, String where, boolean required) {
        if (value == null) {
            if (required) error(where, "is missing");
            return null;
        }
        if (value instanceof Map || value instanceof List) {
            error(where, "must be a single value");
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() && required) error(where, "is empty");
        return text.isEmpty() ? null : text;
    }

    private List<String> strings(@Nullable Object value, String where) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            error(where, "must be a list, like [a, b]");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object entry : list) result.add(String.valueOf(entry));
        return result;
    }

    private double number(@Nullable Object value, String where, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.doubleValue();
        error(where, "must be a number, not '" + value + "'");
        return fallback;
    }

    /** Dollars in the file, cents in the template. */
    private long money(@Nullable Object value, String where, long fallback) {
        if (value == null) return fallback;
        double dollars = number(value, where, -1);
        if (dollars < 0 || dollars * 100.0 > Cents.MAX) {
            error(where, "must be between $0 and " + Money.format(Cents.MAX));
            return fallback;
        }
        return Math.round(dollars * 100.0);
    }

    private @Nullable Vec vec(@Nullable Object value, String where) {
        if (value instanceof List<?> list && list.size() == 3
                && list.get(0) instanceof Number x && list.get(1) instanceof Number y && list.get(2) instanceof Number z) {
            return new Vec(x.doubleValue(), y.doubleValue(), z.doubleValue());
        }
        error(where, value == null ? "is missing" : "must be a point like [x, y, z], not " + value);
        return null;
    }

    private @Nullable Offset offset(@Nullable Object value, String where) {
        if (value instanceof List<?> list && list.size() == 3
                && list.get(0) instanceof Number x && list.get(1) instanceof Number y && list.get(2) instanceof Number z) {
            return new Offset(x.intValue(), y.intValue(), z.intValue());
        }
        error(where, value == null ? "is missing" : "must be a position like [x, y, z], not " + value);
        return null;
    }
}
