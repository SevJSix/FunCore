package me.sevj6.funCore.tycoon;

import me.sevj6.funCore.tycoon.core.Money;
import me.sevj6.funCore.tycoon.core.sim.Cents;
import me.sevj6.funCore.tycoon.core.template.Ladder;
import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The tycoon pickaxe: in the first hotbar slot while a player has a plot, named for their pickaxe level and made of
 * better stuff as it goes up (wood to enchanted netherite). It can't be moved, swapped or dropped, it's taken away
 * whenever they leave the tycoon (or log out) and given back when they return, and any found outside the tycoon (after
 * a crash) is removed.
 */
final class TycoonPickaxe implements Listener {

    private static final int SLOT = 0;
    /** The look for each pickaxe level, from none bought up (the last is used past the end). */
    private static final List<String> MATERIALS = List.of("WOODEN_PICKAXE", "STONE_PICKAXE", "COPPER_PICKAXE",
            "IRON_PICKAXE", "GOLDEN_PICKAXE", "DIAMOND_PICKAXE", "DIAMOND_PICKAXE", "NETHERITE_PICKAXE",
            "NETHERITE_PICKAXE", "NETHERITE_PICKAXE");
    /** From this level on it glints as if enchanted. */
    private static final int ENCHANTED_FROM = 6;

    private final TycoonManager tycoon;
    private final NamespacedKey key;

    TycoonPickaxe(TycoonManager tycoon, NamespacedKey key) {
        this.tycoon = tycoon;
        this.key = key;
    }

    /**
     * Puts the pickaxe for {@code level} in the first slot. Whatever was there moves to a free slot; with no free slot,
     * it stays and they get no pickaxe (nothing is ever lost).
     */
    void give(Player player, Template template, int level) {
        PlayerInventory inventory = player.getInventory();
        ItemStack there = inventory.getItem(SLOT);
        if (there != null && !there.getType().isAir() && !isPickaxe(there)) {
            int free = inventory.firstEmpty();
            if (free < 0) {
                player.sendMessage(Utils.prefixed(Utils.mm("<gray>Free up an inventory slot to get your tycoon pickaxe.")));
                return;
            }
            inventory.setItem(free, there);
        }
        inventory.setItem(SLOT, create(template, level));
    }

    /** Takes every tycoon pickaxe out of the player's inventory. */
    void take(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isPickaxe(inventory.getItem(slot))) inventory.setItem(slot, null);
        }
        if (isPickaxe(player.getItemOnCursor())) player.setItemOnCursor(null);
    }

    private ItemStack create(Template template, int level) {
        String materialName = MATERIALS.get(Math.min(level, MATERIALS.size() - 1));
        Material material = Material.matchMaterial(materialName);
        if (material == null) material = Material.STONE_PICKAXE; // a server without copper tools
        ItemStack item = ItemStack.of(material);
        Ladder ladder = template.manual() == null ? null : template.manual().upgrades();
        String name = (ladder == null ? "Pickaxe" : ladder.name()) + (level > 0 ? " " + Ladder.roman(level) : "");
        long perHit = template.manual() == null ? 0 : Cents.whole(template.manual().valueAt(level));
        item.editMeta(meta -> {
            meta.displayName(Utils.mm("<gold><bold><name>", Placeholder.unparsed("name", name)).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Utils.mm("<gray>Mines <white><value></white> a hit", Placeholder.unparsed("value", Money.format(perHit)))
                            .decoration(TextDecoration.ITALIC, false),
                    Utils.mm("<dark_gray>Upgrade it on the pad by your Ore Vein").decoration(TextDecoration.ITALIC, false)));
            meta.setUnbreakable(true);
            meta.setEnchantmentGlintOverride(level >= ENCHANTED_FROM);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    boolean isPickaxe(@Nullable ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------------------------------------------------
    // It stays put
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean touchesIt = isPickaxe(event.getCurrentItem()) || isPickaxe(event.getCursor())
                || (event.getClick() == ClickType.NUMBER_KEY && isPickaxe(player.getInventory().getItem(event.getHotbarButton())))
                || (event.getClick() == ClickType.SWAP_OFFHAND && isPickaxe(player.getInventory().getItemInOffHand()))
                || (event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == SLOT && tycoon.session(player.getUniqueId()) != null);
        if (touchesIt) event.setResult(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isPickaxe(event.getOldCursor())) {
            event.setResult(Event.Result.DENY);
            return;
        }
        if (tycoon.session(player.getUniqueId()) == null) return;
        for (int raw : event.getRawSlots()) {
            if (event.getView().getInventory(raw) instanceof PlayerInventory && event.getView().convertSlot(raw) == SLOT) {
                event.setResult(Event.Result.DENY);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isPickaxe(event.getMainHandItem()) || isPickaxe(event.getOffHandItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent event) {
        if (isPickaxe(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    /** A pickaxe outside the tycoon (left over from a crash) is taken away as they join. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!tycoon.isTycoonWorld(player.getWorld())) take(player);
    }
}
