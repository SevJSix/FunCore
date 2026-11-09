package me.sevj6.funCore.kit;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The items a game hands out, each with a default slot. Admins edit a game's kit in {@code /game kit <game>} (saved
 * in {@code games/<id>.yml}); players can rearrange the non-armour slots for themselves in {@code /game layout <game>}.
 *
 * <p>Kits are immutable; editing makes a new one.
 */
public final class Kit {

    public static final int BOOTS = 36, LEGGINGS = 37, CHESTPLATE = 38, HELMET = 39, OFFHAND = 40;

    private final List<KitItem> items;

    public Kit(List<KitItem> items) {
        Set<String> ids = new HashSet<>();
        Set<Integer> slots = new HashSet<>();
        for (KitItem item : items) {
            if (!ids.add(item.id())) throw new IllegalArgumentException("duplicate kit item id: " + item.id());
            if (!slots.add(item.slot())) throw new IllegalArgumentException("two kit items in slot " + item.slot());
        }
        this.items = List.copyOf(items);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<KitItem> getItems() {
        return items;
    }

    public @Nullable KitItem getItem(String id) {
        for (KitItem item : items) {
            if (item.id().equals(id)) return item;
        }
        return null;
    }

    /** Whether this kit hands out the same items in the same slots as another. */
    public boolean sameAs(Kit other) {
        return items.equals(other.items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * Where each item goes for a player: their saved layout where it's valid, otherwise the default slot, otherwise
     * the first free slot. Items are never lost to a clash.
     *
     * @param layout item id to inventory slot (0-35, or {@link #OFFHAND}), e.g. from {@link KitLayouts}; may be empty
     */
    public Map<KitItem, Integer> resolveSlots(Map<String, Integer> layout) {
        Map<KitItem, Integer> result = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        // Armour and offhand are fixed.
        for (KitItem item : items) {
            if (!item.isRearrangeable()) {
                result.put(item, item.slot());
                used.add(item.slot());
            }
        }
        // Then the player's choices, then the defaults, then whatever is free.
        List<KitItem> rest = new ArrayList<>();
        for (KitItem item : items) {
            if (!item.isRearrangeable()) continue;
            Integer chosen = layout.get(item.id());
            // Any inventory slot, or the offhand (a game may remember an item moved there during a match).
            boolean valid = chosen != null && ((chosen >= 0 && chosen < BOOTS) || chosen == OFFHAND);
            if (valid && used.add(chosen)) result.put(item, chosen);
            else rest.add(item);
        }
        List<KitItem> unplaced = new ArrayList<>();
        for (KitItem item : rest) {
            if (used.add(item.slot())) result.put(item, item.slot());
            else unplaced.add(item);
        }
        int next = 0;
        for (KitItem item : unplaced) {
            while (used.contains(next)) next++;
            if (next >= BOOTS) break; // a full inventory; can't happen with fewer than 36 items
            used.add(next);
            result.put(item, next);
        }
        return result;
    }

    /**
     * Clears the player's inventory and gives them the kit.
     *
     * @param layout their saved layout (may be empty)
     * @param adjust changes each item before it's given, e.g. dyeing armour a team colour; return it unchanged if not
     */
    public void give(Player player, Map<String, Integer> layout, UnaryOperator<ItemStack> adjust) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        resolveSlots(layout).forEach((item, slot) -> inventory.setItem(slot, adjust.apply(item.item())));
        player.updateInventory();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Saving / loading
    // ------------------------------------------------------------------------------------------------------------

    /** Writes the kit as a list under {@code path}: {@code - {id: ..., slot: ..., item: <ItemStack>}}. */
    public void save(ConfigurationSection config, String path) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (KitItem item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", item.id());
            entry.put("slot", item.slot());
            entry.put("item", item.item());
            list.add(entry);
        }
        config.set(path, list);
    }

    /**
     * Reads a kit written by {@link #save}.
     *
     * @throws IllegalArgumentException if an entry is malformed
     */
    public static Kit load(ConfigurationSection config, String path) {
        List<KitItem> items = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList(path)) {
            if (!(entry.get("id") instanceof String id) || !(entry.get("slot") instanceof Number slot)
                    || !(entry.get("item") instanceof ItemStack item)) {
                throw new IllegalArgumentException("a kit entry needs an id, a slot and an item: " + entry);
            }
            items.add(new KitItem(id, item, slot.intValue()));
        }
        return new Kit(items);
    }

    /**
     * Makes a kit from inventory contents (index = player inventory slot), naming items after their material
     * ({@code iron_sword}, {@code arrow}, {@code arrow_2}, ...). Keeping names stable keeps players' layouts working when
     * an admin edits the kit.
     */
    public static Kit fromContents(Map<Integer, ItemStack> contents) {
        Map<String, Integer> counts = new HashMap<>();
        List<KitItem> items = new ArrayList<>();
        // Lowest slot first, so the "first" of a material keeps its plain name.
        List<Integer> slots = new ArrayList<>(contents.keySet());
        Collections.sort(slots);
        for (int slot : slots) {
            ItemStack item = contents.get(slot);
            if (item == null || item.getType() == Material.AIR) continue;
            String base = item.getType().getKey().getKey();
            int count = counts.merge(base, 1, Integer::sum);
            items.add(new KitItem(count == 1 ? base : base + "_" + count, item, slot));
        }
        return new Kit(items);
    }

    /** Builds a kit in code, naming items after their material like {@link #fromContents}. */
    public static final class Builder {

        private final Map<Integer, ItemStack> contents = new HashMap<>();

        private Builder() {
        }

        public Builder slot(int slot, ItemStack item) {
            if (slot < 0 || slot > OFFHAND) throw new IllegalArgumentException("slot must be 0-" + OFFHAND);
            contents.put(slot, item.clone());
            return this;
        }

        public Builder helmet(ItemStack item) {
            return slot(HELMET, item);
        }

        public Builder chestplate(ItemStack item) {
            return slot(CHESTPLATE, item);
        }

        public Builder leggings(ItemStack item) {
            return slot(LEGGINGS, item);
        }

        public Builder boots(ItemStack item) {
            return slot(BOOTS, item);
        }

        public Kit build() {
            return fromContents(contents);
        }
    }
}
