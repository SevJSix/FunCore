package me.sevj6.funCore.kit;

import me.sevj6.funCore.menu.Menu;
import me.sevj6.funCore.menu.MenuButton;
import me.sevj6.funCore.menu.MenuClick;
import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Lets a player arrange a game's kit for themselves. The top four rows mirror their inventory (the fourth row is the
 * hotbar): click an item, then click where it should go (swapping with whatever is there). Armour isn't shown, since
 * it always goes on the armour slots.
 */
public final class LayoutEditorMenu extends Menu {

    private static final int SAVE = slot(4, 3), RESET = slot(4, 4), CANCEL = slot(4, 5);

    private final Kit kit;
    private final KitLayouts layouts;
    private final UUID owner;
    private final UnaryOperator<ItemStack> preview;
    /** Inventory slot (0-35) to the kit item shown there. */
    private final Map<Integer, KitItem> placement = new HashMap<>();
    private int selected = -1;

    /**
     * @param preview how items look when given out (e.g. coloured for a team), so the editor shows the real thing
     */
    public LayoutEditorMenu(Component gameName, Kit kit, KitLayouts layouts, UUID owner, UnaryOperator<ItemStack> preview) {
        super(Utils.mm("<dark_gray>Kit layout: </dark_gray><name>", Placeholder.component("name", gameName)), 5);
        this.kit = kit;
        this.layouts = layouts;
        this.owner = owner;
        this.preview = preview;
        load(layouts.get(owner));
        render();
    }

    private void load(Map<String, Integer> layout) {
        placement.clear();
        kit.resolveSlots(layout).forEach((item, slot) -> {
            if (item.isRearrangeable()) placement.put(slot, item);
        });
        selected = -1;
    }

    /** Menu slot (0-35) to inventory slot: rows 0-2 are the inventory (9-35), row 3 is the hotbar (0-8). */
    private static int toInventory(int menuSlot) {
        return menuSlot < 27 ? menuSlot + 9 : menuSlot - 27;
    }

    private void render() {
        for (int menuSlot = 0; menuSlot < 36; menuSlot++) {
            int inventorySlot = toInventory(menuSlot);
            KitItem item = placement.get(inventorySlot);
            MenuButton button;
            if (item == null) {
                button = MenuButton.of(ItemBuilder.of(inventorySlot < 9 ? Material.LIGHT_GRAY_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE)
                        .name(selected >= 0 ? "<yellow>Move here" : inventorySlot < 9 ? "<gray>Hotbar slot " + (inventorySlot + 1) : "<gray>Empty")
                        .build(), click -> clicked(inventorySlot));
            } else {
                ItemBuilder icon = ItemBuilder.of(preview.apply(item.item()));
                if (inventorySlot == selected) icon.glow().addLore("<yellow>Selected: click another slot to move it there");
                else icon.addLore(selected >= 0 ? "<yellow>Click to swap" : "<gray>Click to pick up");
                button = MenuButton.of(icon.build(), click -> clicked(inventorySlot));
            }
            set(menuSlot, button);
        }
        for (int slot = 36; slot < 45; slot++) set(slot, MenuButton.filler());
        set(SAVE, MenuButton.of(ItemBuilder.of(Material.LIME_DYE).name("<green>Save layout").build(), this::save));
        set(RESET, MenuButton.of(ItemBuilder.of(Material.WATER_BUCKET).name("<aqua>Reset to default")
                .lore("<gray>Shows the default layout. Save to keep it.").build(), click -> {
            load(Map.of());
            render();
        }));
        set(CANCEL, MenuButton.of(ItemBuilder.of(Material.BARRIER).name("<red>Cancel").build(), MenuClick::close));
    }

    private void clicked(int inventorySlot) {
        if (selected < 0) {
            if (placement.containsKey(inventorySlot)) selected = inventorySlot;
        } else if (selected == inventorySlot) {
            selected = -1;
        } else {
            KitItem moving = placement.remove(selected);
            KitItem other = placement.remove(inventorySlot);
            if (moving != null) placement.put(inventorySlot, moving);
            if (other != null) placement.put(selected, other);
            selected = -1;
        }
        render();
    }

    private void save(MenuClick click) {
        Map<String, Integer> layout = new LinkedHashMap<>();
        placement.forEach((slot, item) -> {
            if (slot != item.slot()) layout.put(item.id(), slot); // only what differs from the default
        });
        layouts.set(owner, layout);
        Player player = click.player();
        player.sendMessage(Utils.prefixed(Utils.mm(layout.isEmpty() ? "<green>Saved: you're using the default layout."
                : "<green>Saved your layout. It's used from your next kit on.")));
        click.close();
    }
}
