package me.sevj6.funCore.tycoon;

import me.sevj6.funCore.tycoon.core.template.Offset;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code plugins/FunCore/tycoon/config.yml}: where the map is and where its plots are. The plot layout itself is in
 * the template.
 *
 * <p>The map is pasted once into the tycoon world. {@code map.origin} (its minimum corner, in world coordinates) is
 * the only world position in the tycoon's config or data: the hub and every plot slot are offsets from it.
 *
 * @param origin          the map's minimum corner, or {@code null} until an admin sets it (the tycoon is off until then)
 * @param mapSize         the map's size, for chunk tickets and catching players who fall off it
 * @param hub             where visitors (and players without a plot) are sent, from the map origin
 * @param slots           each plot's origin (its minimum x/z corner, at ground level), from the map origin
 * @param flushTicks      how often collected income is paid out
 * @param maxVisibleItems items shown as entities on one plot at once (past it, they're simulated unseen)
 */
public record TycoonSettings(String world, @Nullable Offset origin, Offset mapSize, Offset hub, float hubYaw,
                             List<Offset> slots, int flushTicks, int maxVisibleItems) {

    public TycoonSettings {
        slots = List.copyOf(slots);
    }

    /** Reads the file; problems are added to {@code errors} (and defaults used for them). */
    public static TycoonSettings load(File file, List<String> errors) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String world = config.getString("map.world", "tycoon");
        if (world == null || !world.matches("[a-z0-9_.-]+")) {
            errors.add("config.yml: map.world must be a lowercase name (letters, digits, _ - .), not '" + world + "'");
            world = "tycoon";
        }
        Offset origin = config.isList("map.origin") ? offset(config.getList("map.origin"), "map.origin", errors) : null;
        Offset size = offset(config.getList("map.size"), "map.size", errors);
        if (size == null) size = new Offset(225, 32, 157);
        Offset hub = offset(config.getList("map.hub"), "map.hub", errors);
        if (hub == null) hub = new Offset(0, 0, 0);
        float hubYaw = (float) config.getDouble("map.hub-yaw", 0);

        List<Offset> slots = new ArrayList<>();
        List<?> slotList = config.getList("slots");
        if (slotList == null || slotList.isEmpty()) errors.add("config.yml: slots needs at least one plot origin");
        else for (int i = 0; i < slotList.size(); i++) {
            Offset slot = offset(slotList.get(i) instanceof List<?> list ? list : null, "slots[" + (i + 1) + "]", errors);
            if (slot != null) slots.add(slot);
        }

        double flushSeconds = config.getDouble("income-flush-seconds", 3);
        if (flushSeconds < 0.05 || flushSeconds > 60) errors.add("config.yml: income-flush-seconds must be between 0.05 and 60");
        int visible = config.getInt("max-visible-items", 150);
        if (visible < 0) errors.add("config.yml: max-visible-items can't be negative");
        return new TycoonSettings(world, origin, size, hub, hubYaw, slots,
                (int) Math.max(1, Math.round(flushSeconds * 20)), Math.max(0, visible));
    }

    private static @Nullable Offset offset(@Nullable List<?> list, String where, List<String> errors) {
        if (list != null && list.size() == 3 && list.get(0) instanceof Number x && list.get(1) instanceof Number y
                && list.get(2) instanceof Number z) {
            return new Offset(x.intValue(), y.intValue(), z.intValue());
        }
        errors.add("config.yml: " + where + " must be a position like [x, y, z]");
        return null;
    }

    /** Problems with these settings and a template's plot footprint together: every plot has to fit in the map. */
    public List<String> check(Offset footprint) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Offset slot = slots.get(i);
            if (slot.x() < 0 || slot.z() < 0 || slot.y() < 0 || slot.x() + footprint.x() > mapSize.x()
                    || slot.z() + footprint.z() > mapSize.z() || slot.y() + footprint.y() > mapSize.y()) {
                errors.add("config.yml: slot " + (i + 1) + " " + slot + " with the template's footprint " + footprint
                        + " doesn't fit inside the map " + mapSize);
            }
            for (int j = 0; j < i; j++) {
                Offset other = slots.get(j);
                if (Math.abs(other.x() - slot.x()) < footprint.x() && Math.abs(other.z() - slot.z()) < footprint.z()) {
                    errors.add("config.yml: slots " + (j + 1) + " and " + (i + 1) + " overlap");
                }
            }
        }
        return errors;
    }

    /** The same map in the same place with the same slots (what can't change while plots are in use). */
    public boolean sameMap(TycoonSettings other) {
        return world.equals(other.world) && java.util.Objects.equals(origin, other.origin) && slots.equals(other.slots);
    }
}
