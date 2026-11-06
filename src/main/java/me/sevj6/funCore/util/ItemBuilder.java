package me.sevj6.funCore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent builder for item stacks, mainly for menu icons:
 * <pre>
 * ItemStack icon = ItemBuilder.of(Material.DIAMOND)
 *         .name("&lt;aqua&gt;Shiny diamond")
 *         .lore("&lt;gray&gt;Costs $50", "&lt;yellow&gt;Click to buy")
 *         .glow()
 *         .build();
 * </pre>
 * Names and lore are MiniMessage strings or components. Minecraft shows custom names and lore in italics by default;
 * the builder turns that off unless the text sets italics itself, and gives lore gray as its default colour.
 */
public final class ItemBuilder {

    private final ItemStack item;
    private final List<Component> lore = new ArrayList<>();

    private ItemBuilder(ItemStack item) {
        this.item = item;
        List<Component> existing = item.getItemMeta() == null ? null : item.getItemMeta().lore();
        if (existing != null) lore.addAll(existing);
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(ItemStack.of(material));
    }

    /** Starts from a copy of an existing item (the original isn't changed). */
    public static ItemBuilder of(ItemStack item) {
        return new ItemBuilder(item.clone());
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    public ItemBuilder name(Component name) {
        return meta(meta -> meta.displayName(plain(name, NamedTextColor.WHITE)));
    }

    /** Sets the name from MiniMessage, e.g. {@code "<gold>Shop"}. */
    public ItemBuilder name(String miniMessage) {
        return name(Utils.mm(miniMessage));
    }

    /** Replaces the lore. */
    public ItemBuilder lore(List<Component> lines) {
        lore.clear();
        lines.forEach(this::addLore);
        return this;
    }

    /** Replaces the lore with MiniMessage lines. */
    public ItemBuilder lore(String... miniMessageLines) {
        lore.clear();
        Arrays.stream(miniMessageLines).forEach(this::addLore);
        return this;
    }

    public ItemBuilder addLore(Component line) {
        lore.add(plain(line, NamedTextColor.GRAY));
        return this;
    }

    public ItemBuilder addLore(String miniMessage) {
        return addLore(Utils.mm(miniMessage));
    }

    /** Adds the enchantment shimmer without adding an enchantment. */
    public ItemBuilder glow() {
        return glow(true);
    }

    public ItemBuilder glow(boolean glow) {
        return meta(meta -> meta.setEnchantmentGlintOverride(glow ? Boolean.TRUE : null));
    }

    /** Shows no tooltip at all when hovered (for filler panes). */
    public ItemBuilder hideTooltip() {
        return meta(meta -> meta.setHideTooltip(true));
    }

    /** Escape hatch for anything the builder doesn't cover. */
    public ItemBuilder meta(Consumer<ItemMeta> change) {
        item.editMeta(change);
        return this;
    }

    public ItemStack build() {
        List<Component> lines = List.copyOf(lore);
        item.editMeta(meta -> meta.lore(lines.isEmpty() ? null : lines));
        return item.clone();
    }

    private static Component plain(Component text, NamedTextColor defaultColor) {
        return text.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE).colorIfAbsent(defaultColor);
    }
}
