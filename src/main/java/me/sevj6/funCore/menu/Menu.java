package me.sevj6.funCore.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A chest-style menu of clickable {@link MenuButton}s. Players can't take, move or add items while it's open; every
 * click is cancelled and handed to the button in that slot.
 *
 * <p>Use it directly:
 * <pre>
 * Menu menu = new Menu(Utils.mm("&lt;gold&gt;Shop"), 3);
 * menu.set(13, MenuButton.of(icon, click -&gt; click.player().sendMessage("Bought!")));
 * menu.fillEmpty(MenuButton.filler());
 * menu.open(player);
 * </pre>
 * or extend it, overriding {@link #onOpen} / {@link #onClose} for menus with their own state. A menu can be shown to
 * several players at once (they share it), but normally each player gets their own instance.
 *
 * <p>Slots are numbered 0 to {@code rows * 9 - 1}, left to right, top to bottom; {@link #slot(int, int)} converts
 * from row and column.
 */
public class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final int rows;
    private final Map<Integer, MenuButton> buttons = new HashMap<>();

    /**
     * @param rows 1 to 6
     */
    public Menu(Component title, int rows) {
        if (rows < 1 || rows > 6) throw new IllegalArgumentException("A menu has 1 to 6 rows, not " + rows);
        this.rows = rows;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    /** The slot number for a row and column, both counted from 0. */
    public static int slot(int row, int column) {
        if (column < 0 || column > 8) throw new IllegalArgumentException("Column must be 0 to 8, not " + column);
        return row * 9 + column;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Buttons
    // ------------------------------------------------------------------------------------------------------------

    /** Puts a button in a slot (replacing what's there), or clears the slot if {@code button} is {@code null}. */
    public Menu set(int slot, @Nullable MenuButton button) {
        checkSlot(slot);
        if (button == null) {
            buttons.remove(slot);
            inventory.setItem(slot, null);
        } else {
            buttons.put(slot, button);
            inventory.setItem(slot, button.icon());
        }
        return this;
    }

    public Menu set(int row, int column, @Nullable MenuButton button) {
        return set(slot(row, column), button);
    }

    public @Nullable MenuButton get(int slot) {
        return buttons.get(slot);
    }

    public boolean isEmpty(int slot) {
        return !buttons.containsKey(slot);
    }

    public Menu clear() {
        buttons.clear();
        inventory.clear();
        return this;
    }

    /** Puts {@code button} in every slot that has nothing in it yet. */
    public Menu fillEmpty(MenuButton button) {
        for (int slot = 0; slot < size(); slot++) {
            if (isEmpty(slot)) set(slot, button);
        }
        return this;
    }

    /** Puts {@code button} in every slot of one row (counted from 0). */
    public Menu fillRow(int row, MenuButton button) {
        for (int column = 0; column < 9; column++) set(row, column, button);
        return this;
    }

    /** Puts {@code button} around the outside edge of the menu. */
    public Menu fillBorder(MenuButton button) {
        for (int slot = 0; slot < size(); slot++) {
            int row = slot / 9, column = slot % 9;
            if (row == 0 || row == rows - 1 || column == 0 || column == 8) set(slot, button);
        }
        return this;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Opening and closing
    // ------------------------------------------------------------------------------------------------------------

    /** Shows the menu to a player. Don't call this from inside a click; use {@link MenuClick#open} there instead. */
    public void open(Player player) {
        // openInventory returns null if another plugin cancelled the open.
        if (player.openInventory(inventory) != null) onOpen(player);
    }

    /** Closes the menu for everyone viewing it. */
    public void close() {
        for (HumanEntity viewer : List.copyOf(inventory.getViewers())) viewer.closeInventory();
    }

    /** Players currently looking at this menu. */
    public List<Player> getViewers() {
        List<Player> viewers = new ArrayList<>();
        for (HumanEntity viewer : inventory.getViewers()) {
            if (viewer instanceof Player player) viewers.add(player);
        }
        return viewers;
    }

    /** Called after the menu opens for a player. */
    protected void onOpen(Player player) {
    }

    /** Called after a player closes the menu (by pressing Esc, opening another inventory, logging out, ...). */
    protected void onClose(Player player) {
    }

    // ------------------------------------------------------------------------------------------------------------

    void handleClick(MenuClick click) {
        MenuButton button = buttons.get(click.slot());
        if (button != null) button.click(click);
    }

    void handleClose(Player player) {
        onClose(player);
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return rows * 9;
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= size()) throw new IllegalArgumentException("Slot " + slot + " is outside this " + rows + "-row menu");
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
