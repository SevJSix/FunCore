package me.sevj6.funCore.game;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Stops players moving items around their own inventory, except in and out of a few open slots. Taking items out of a
 * container (a chest, a barrel, ...) into the inventory is always allowed, wherever they land.
 * <p>
 * A game keeps one of these, decides which {@link Rule} applies to a player, and asks it about their inventory clicks,
 * drags and hand swaps (cancelling the event when the answer is no). It remembers where each player's cursor item came
 * from, so an item taken from an open slot can't be dropped into a locked one.
 */
public final class InventoryLock {

    /** Player-inventory slot numbers, as Bukkit numbers them. */
    public static final int OFFHAND = 40, HELMET = 39, CHESTPLATE = 38, LEGGINGS = 37, BOOTS = 36, LAST_SLOT = 40;

    private static final Map<String, Integer> NAMED = Map.of("offhand", OFFHAND, "helmet", HELMET,
            "chestplate", CHESTPLATE, "leggings", LEGGINGS, "boots", BOOTS);

    /**
     * Which slots of a player's inventory items may move in and out of.
     *
     * @param everything nothing is locked
     * @param open       the slots that stay open when the rest are locked
     */
    public record Rule(boolean everything, Set<Integer> open) {

        public static final Rule ALL = new Rule(true, Set.of());
        public static final Rule NONE = new Rule(false, Set.of());

        public Rule {
            open = Set.copyOf(open);
        }

        public boolean isOpen(int slot) {
            return everything || open.contains(slot);
        }

        /**
         * Reads {@code all}, {@code none}, or a list of open slots: {@code hotbar-1} to {@code hotbar-9},
         * {@code offhand}, {@code helmet}, {@code chestplate}, {@code leggings}, {@code boots}, or slot numbers 0-40
         * (0-8 hotbar, 9-35 the rest, 36-39 armour, 40 offhand), separated by commas or spaces.
         *
         * @throws IllegalArgumentException with a message for the player if it's not valid
         */
        public static Rule parse(String text) {
            String clean = text.trim().toLowerCase(Locale.ROOT);
            if (clean.equals("all")) return ALL;
            if (clean.equals("none") || clean.isEmpty()) return NONE;
            Set<Integer> slots = new HashSet<>();
            for (String part : clean.split("[\\s,]+")) {
                if (part.isEmpty()) continue;
                slots.add(slot(part));
            }
            return new Rule(false, slots);
        }

        private static int slot(String part) {
            Integer named = NAMED.get(part);
            if (named != null) return named;
            try {
                if (part.startsWith("hotbar-")) {
                    int n = Integer.parseInt(part.substring("hotbar-".length()));
                    if (n >= 1 && n <= 9) return n - 1;
                } else {
                    int n = Integer.parseInt(part);
                    if (n >= 0 && n <= LAST_SLOT) return n;
                }
            } catch (NumberFormatException ignored) {
                // falls through to the message below
            }
            throw new IllegalArgumentException("'" + part + "' isn't a slot. Use all, none, or slots like hotbar-1, offhand, "
                    + "helmet, or numbers 0-40.");
        }

        /** The same text {@link #parse} reads, tidied up, e.g. {@code hotbar-1, offhand}. */
        public String format() {
            if (everything) return "all";
            if (open.isEmpty()) return "none";
            List<String> parts = new ArrayList<>();
            for (int slot : new TreeSet<>(open)) parts.add(name(slot));
            return String.join(", ", parts);
        }

        private static String name(int slot) {
            if (slot <= 8) return "hotbar-" + (slot + 1);
            for (var entry : NAMED.entrySet()) {
                if (entry.getValue() == slot) return entry.getKey();
            }
            return String.valueOf(slot);
        }

        /** Checks and tidies a rule typed as a game option (see {@link GameType.Builder#option(String, String, java.util.function.UnaryOperator, String)}). */
        public static String normalize(String text) {
            return parse(text).format();
        }
    }

    /** Players whose cursor holds an item taken from their own inventory (rather than from a container). */
    private final Set<UUID> cursorFromOwnInventory = new HashSet<>();

    /** Forgets a player's cursor, e.g. when they close their inventory or leave. */
    public void forget(UUID player) {
        cursorFromOwnInventory.remove(player);
    }

    /** Whether the rule allows this click; call it before the click happens and cancel the event if not. */
    public boolean allows(Player player, Rule rule, InventoryClickEvent event) {
        if (rule.everything()) return true;
        InventoryView view = event.getView();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return true; // outside the window: a drop, which the game's drop rules decide
        boolean container = isContainer(view.getTopInventory());
        UUID uuid = player.getUniqueId();
        switch (event.getAction()) {
            case COLLECT_TO_CURSOR -> {
                // A double click gathers matching items from everywhere, locked slots included.
                return false;
            }
            case NOTHING, CLONE_STACK -> {
                return true;
            }
            default -> {
                // decided below
            }
        }

        if (!(clicked instanceof PlayerInventory)) {
            if (!container) return false; // the crafting grid: that's moving items around
            return switch (event.getAction()) {
                case MOVE_TO_OTHER_INVENTORY -> true; // container to inventory
                case HOTBAR_SWAP -> {
                    // The container's item goes to that hotbar slot (or the offhand); what was there goes in the container.
                    int target = swapTarget(event);
                    yield rule.isOpen(target) || isEmpty(player.getInventory().getItem(target));
                }
                case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, SWAP_WITH_CURSOR -> {
                    cursorFromOwnInventory.remove(uuid);
                    yield true;
                }
                default -> true; // putting the cursor's item in the container
            };
        }

        int slot = event.getSlot();
        boolean open = rule.isOpen(slot);
        return switch (event.getAction()) {
            case HOTBAR_SWAP -> open && rule.isOpen(swapTarget(event));
            case MOVE_TO_OTHER_INVENTORY -> open && container;
            // Afterwards the cursor holds what was in the slot.
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, SWAP_WITH_CURSOR -> {
                if (open) cursorFromOwnInventory.add(uuid);
                yield open;
            }
            // Only adds to the slot, so a locked one may take an item that came from a container.
            case PLACE_ALL, PLACE_SOME, PLACE_ONE -> open || !cursorFromOwnInventory.contains(uuid);
            default -> open;
        };
    }

    /** Whether the rule allows this drag (spreading the cursor's item over several slots). */
    public boolean allows(Player player, Rule rule, InventoryDragEvent event) {
        if (rule.everything()) return true;
        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();
        boolean fromContainer = !cursorFromOwnInventory.contains(player.getUniqueId());
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize()) {
                if (!isContainer(top)) return false;
            } else if (!rule.isOpen(view.convertSlot(raw)) && !fromContainer) {
                return false;
            }
        }
        return true;
    }

    /** Whether the rule allows swapping the held item with the offhand (the F key). */
    public boolean allowsSwapHands(Player player, Rule rule) {
        return rule.isOpen(player.getInventory().getHeldItemSlot()) && rule.isOpen(OFFHAND);
    }

    /** The player-inventory slot a number-key (or F) press swaps with. */
    private static int swapTarget(InventoryClickEvent event) {
        return event.getClick() == ClickType.SWAP_OFFHAND || event.getHotbarButton() < 0 ? OFFHAND : event.getHotbarButton();
    }

    /** A real container, not the crafting grid of the player's own inventory screen. */
    private static boolean isContainer(Inventory top) {
        return top.getType() != InventoryType.CRAFTING && top.getType() != InventoryType.PLAYER;
    }

    private static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
