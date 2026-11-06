package me.sevj6.funCore.menu;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.Nullable;

/**
 * Makes {@link Menu}s read-only and routes clicks to their buttons. While a menu is open, every click and drag is
 * cancelled, including clicks in the player's own inventory, since shift-clicks and double-clicks there could move
 * items into the menu.
 */
public final class MenuListener implements Listener {

    private final FunCore plugin;

    public MenuListener(FunCore plugin) {
        this.plugin = plugin;
    }

    /** Closes every open menu, so nobody is left holding a menu whose plugin has been disabled. */
    public static void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (menuOf(player.getOpenInventory().getTopInventory()) != null) player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        Menu menu = menuOf(top);
        if (menu == null) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;
        // A double click fires a normal click first, then this; only the first one counts.
        if (event.getClick() == ClickType.DOUBLE_CLICK) return;

        try {
            menu.handleClick(new MenuClick(player, menu, event.getSlot(), event.getClick()));
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("Error handling a click in {} for {}", menu.getClass().getSimpleName(), player.getName(), e);
            player.sendMessage(Utils.prefixed(Utils.mm("<red>Something went wrong. Please try again.")));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu == null || !(event.getPlayer() instanceof Player player)) return;
        try {
            menu.handleClose(player);
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("Error closing {} for {}", menu.getClass().getSimpleName(), player.getName(), e);
        }
    }

    private static @Nullable Menu menuOf(Inventory inventory) {
        return inventory.getHolder(false) instanceof Menu menu ? menu : null;
    }
}
