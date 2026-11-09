package me.sevj6.funCore.kit;

import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The admin kit editor ({@code /game kit <game>}). Unlike a {@link me.sevj6.funCore.menu.Menu}, items can be moved
 * freely, so admins drag items in from their own inventory (in creative they can take anything).
 *
 * <pre>
 * rows 1-3   inventory slots 9-35
 * row 4      hotbar 0-8
 * row 5      helmet, chestplate, leggings, boots, offhand
 * row 6      Save / Reset to default / Cancel
 * </pre>
 * Closing without saving discards the changes.
 */
public final class KitEditor implements InventoryHolder {

    private static final int ARMOR_ROW = 36, LOCKED_FROM = 41;
    private static final int SAVE = 49, RESET = 48, CANCEL = 50;
    /** Editor slot to player inventory slot, for row 5. */
    private static final int[] ARMOR_SLOTS = {Kit.HELMET, Kit.CHESTPLATE, Kit.LEGGINGS, Kit.BOOTS, Kit.OFFHAND};

    private final Inventory inventory;
    private final Supplier<Kit> defaults;
    private final Consumer<Kit> onSave;
    private final boolean allowEmpty;
    private boolean saved;

    /**
     * @param title      what's being edited, e.g. the game's name
     * @param current    the kit to start from
     * @param defaults   the game's built-in kit, for "Reset to default"
     * @param allowEmpty whether saving a kit with no items is fine (e.g. a level that hands out nothing)
     * @param onSave     stores the edited kit
     */
    public KitEditor(Component title, Kit current, Supplier<Kit> defaults, boolean allowEmpty, Consumer<Kit> onSave) {
        this.inventory = Bukkit.createInventory(this, 54, Utils.mm("<dark_gray>Edit kit: </dark_gray><name>",
                Placeholder.component("name", title)));
        this.defaults = defaults;
        this.allowEmpty = allowEmpty;
        this.onSave = onSave;
        show(current);
        for (int slot = LOCKED_FROM; slot < 54; slot++) inventory.setItem(slot, filler());
        inventory.setItem(45, ItemBuilder.of(Material.BOOK).name("<gold>How to edit")
                .lore("<gray>Drag items in from your inventory.",
                        "<gray>Rows 1-3: inventory, row 4: hotbar.",
                        "<gray>Row 5: helmet, chestplate, leggings,",
                        "<gray>boots, offhand.",
                        "<gray>Closing without saving discards changes.").build());
        inventory.setItem(RESET, ItemBuilder.of(Material.WATER_BUCKET).name("<aqua>Reset to default")
                .lore("<gray>Loads the game's built-in kit. Save to keep it.").build());
        inventory.setItem(SAVE, ItemBuilder.of(Material.LIME_DYE).name("<green>Save kit").build());
        inventory.setItem(CANCEL, ItemBuilder.of(Material.BARRIER).name("<red>Cancel").build());
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void show(Kit kit) {
        for (int slot = 0; slot < LOCKED_FROM; slot++) inventory.setItem(slot, null);
        for (KitItem item : kit.getItems()) inventory.setItem(toEditor(item.slot()), item.item());
    }

    private static int toEditor(int inventorySlot) {
        if (inventorySlot >= Kit.BOOTS) {
            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                if (ARMOR_SLOTS[i] == inventorySlot) return ARMOR_ROW + i;
            }
        }
        return inventorySlot < 9 ? inventorySlot + 27 : inventorySlot - 9;
    }

    private static int toInventory(int editorSlot) {
        if (editorSlot >= ARMOR_ROW) return ARMOR_SLOTS[editorSlot - ARMOR_ROW];
        return editorSlot < 27 ? editorSlot + 9 : editorSlot - 27;
    }

    private Kit read() {
        Map<Integer, ItemStack> contents = new HashMap<>();
        for (int slot = 0; slot < LOCKED_FROM; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) contents.put(toInventory(slot), item.clone());
        }
        return Kit.fromContents(contents);
    }

    private static ItemStack filler() {
        return ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).name("<dark_gray>FunCore kit editor").build();
    }

    /** Keeps the bottom rows locked and runs the buttons. Registered once by FunCore. */
    public static final class EditorListener implements Listener {

        @EventHandler(priority = EventPriority.LOW)
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder(false) instanceof KitEditor editor)) return;
            if (!(event.getWhoClicked() instanceof Player player)) return;
            // Double-clicking could pull the locked panes onto the cursor.
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
                return;
            }
            if (event.getClickedInventory() != editor.inventory) return;
            int slot = event.getSlot();
            if (slot < LOCKED_FROM) return; // a kit slot: anything goes
            event.setCancelled(true);
            switch (slot) {
                case SAVE -> {
                    Kit kit = editor.read();
                    if (kit.isEmpty() && !editor.allowEmpty) {
                        player.sendMessage(Utils.prefixed(Utils.mm("<red>A kit needs at least one item.")));
                        return;
                    }
                    editor.saved = true;
                    editor.onSave.accept(kit);
                    player.closeInventory();
                }
                case RESET -> editor.show(editor.defaults.get());
                case CANCEL -> player.closeInventory();
                default -> {
                }
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onDrag(InventoryDragEvent event) {
            if (!(event.getView().getTopInventory().getHolder(false) instanceof KitEditor)) return;
            for (int raw : event.getRawSlots()) {
                if (raw >= LOCKED_FROM && raw < 54) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(InventoryCloseEvent event) {
            if (event.getInventory().getHolder(false) instanceof KitEditor editor && !editor.saved) {
                event.getPlayer().sendMessage(Utils.prefixed(Utils.mm("<gray>Closed the kit editor without saving.")));
            }
        }
    }
}
