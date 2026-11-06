package me.sevj6.funCore.menu;

import me.sevj6.funCore.util.Utils;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** A click on a {@link MenuButton}: who clicked, where, and how. */
public final class MenuClick {

    private final Player player;
    private final Menu menu;
    private final int slot;
    private final ClickType type;

    MenuClick(Player player, Menu menu, int slot, ClickType type) {
        this.player = player;
        this.menu = menu;
        this.slot = slot;
        this.type = type;
    }

    public Player player() {
        return player;
    }

    public Menu menu() {
        return menu;
    }

    public int slot() {
        return slot;
    }

    public ClickType type() {
        return type;
    }

    public boolean isLeftClick() {
        return type.isLeftClick();
    }

    public boolean isRightClick() {
        return type.isRightClick();
    }

    public boolean isShiftClick() {
        return type.isShiftClick();
    }

    /**
     * Opens another menu for this player. It opens on the next tick, because opening an inventory in the middle of a
     * click is unsafe.
     */
    public void open(Menu next) {
        Utils.runLater(() -> {
            if (player.isOnline()) next.open(player);
        }, 1L);
    }

    /** Closes the menu for this player (on the next tick, for the same reason as {@link #open}). */
    public void close() {
        Utils.runLater(() -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) == menu) player.closeInventory();
        }, 1L);
    }
}
