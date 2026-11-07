package me.sevj6.funCore.kit;

import org.bukkit.inventory.ItemStack;

/**
 * One item of a {@link Kit}.
 *
 * @param id   stable name used by players' saved layouts, e.g. {@code "iron_sword"}
 * @param item the item (a copy is given out)
 * @param slot where it goes by default: a player inventory index (0-8 hotbar, 9-35 inventory, 36 boots, 37 leggings,
 *             38 chestplate, 39 helmet, 40 offhand)
 */
public record KitItem(String id, ItemStack item, int slot) {

    public KitItem {
        if (slot < 0 || slot > Kit.OFFHAND) throw new IllegalArgumentException("slot must be 0-" + Kit.OFFHAND);
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }

    /** Armour and offhand items stay where the kit puts them; players can only rearrange the other slots. */
    public boolean isRearrangeable() {
        return slot < Kit.BOOTS;
    }
}
