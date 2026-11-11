package me.sevj6.funCore.tycoon.core.template;

import me.sevj6.funCore.economy.Cash;
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
 *   piece: base                          # optional; placeholder platform without it
 *   spawn: [32, 1, 4]
 *   spawn-yaw: 180
 *   collectors: { main: [32, 1, 58] }
 *   belts:                               # free, always there
 *     vault-belt: { speed: 2, path: [[8, 1, 40], [8, 1, 56]], collector: vault }
 *   rebirth-pad: [36, 1, 6]
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
 * {@code zone} ({@code main} or {@code vault}, default main), {@code piece} and {@code pad}.
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
        String basePiece = string(base.get("piece"), "base.piece", false);
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
        return new Template(size, basePiece, spawn, (float) spawnYaw, collectors, baseBelts, rebirthPad, startWith, components);
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
        String piece = string(section.get("piece"), where + " piece", false);
        Offset pad = section.get("pad") == null ? null : offset(section.get("pad"), where + " pad");
        return new Component.Common(id, name == null ? id : name, price, requires, minRebirths, zone, piece, pad);
    }

    private Component.@Nullable Dropper dropper(Component.Common common, Map<String, Object> section, String where) {
        Offset spout = offset(section.get("spout"), where + " spout");
        int interval = (int) number(section.get("interval"), where + " interval", -1);
        if (interval < 1) error(where, "interval must be at least 1 tick");
        double value = number(section.get("value"), where + " value", -1) * 100.0;
        if (!(value > 0) || value > Cash.MAX) error(where, "value must be more than $0 and at most " + Cash.format(Cash.MAX));

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
        if (dollars < 0 || dollars * 100.0 > Cash.MAX) {
            error(where, "must be between $0 and " + Cash.format(Cash.MAX));
            return fallback;
        }
        return Math.round(dollars * 100.0);
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
