package me.sevj6.funCore.menu;

import me.sevj6.funCore.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * One slot of a {@link Menu}: the item shown there and what happens when it's clicked.
 *
 * @param action {@code null} for an icon that does nothing when clicked
 */
public record MenuButton(ItemStack icon, @Nullable Consumer<MenuClick> action) {

    public MenuButton {
        icon = icon.clone();
    }

    /** A button that runs {@code action} when clicked. */
    public static MenuButton of(ItemStack icon, Consumer<MenuClick> action) {
        return new MenuButton(icon, action);
    }

    /** An icon that does nothing when clicked (information, decoration). */
    public static MenuButton display(ItemStack icon) {
        return new MenuButton(icon, null);
    }

    /** A blank pane with no tooltip, for filling empty slots and borders. */
    public static MenuButton filler(Material pane) {
        return display(ItemBuilder.of(pane).hideTooltip().build());
    }

    /** The default filler: a gray pane. */
    public static MenuButton filler() {
        return filler(Material.GRAY_STAINED_GLASS_PANE);
    }

    void click(MenuClick click) {
        if (action != null) action.accept(click);
    }
}
