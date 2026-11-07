package me.sevj6.funCore.games.bridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

/**
 * The two sides of a bridge map. The first team of a match is red, the second blue. Each side has a spawn point and a
 * goal area in the arena; the <b>other</b> side scores by jumping into a side's goal.
 */
enum Side {

    RED(NamedTextColor.RED, Material.RED_TERRACOTTA, Color.fromRGB(0xB02E26), "Red"),
    BLUE(NamedTextColor.BLUE, Material.BLUE_TERRACOTTA, Color.fromRGB(0x3C44AA), "Blue");

    final NamedTextColor color;
    final Material clay;
    final Color armorColor;
    final String displayName;
    /** Arena point where this side spawns. */
    final String spawnPoint;
    /** Arena area this side defends: the other side scores in it. */
    final String goalArea;

    Side(NamedTextColor color, Material clay, Color armorColor, String displayName) {
        this.color = color;
        this.clay = clay;
        this.armorColor = armorColor;
        this.displayName = displayName;
        this.spawnPoint = name().toLowerCase() + "-spawn";
        this.goalArea = name().toLowerCase() + "-goal";
    }

    Side opposite() {
        return this == RED ? BLUE : RED;
    }

    Component displayName() {
        return Component.text(displayName, color);
    }

    /** The side of the team with this index (0 red, 1 blue), or {@code null} past the second team. */
    static Side ofTeam(int index) {
        return index == 0 ? RED : index == 1 ? BLUE : null;
    }

    /** The blocks of the bridge, and the only ones players can place and break. */
    static boolean isBridgeBlock(Material material) {
        return material == Material.RED_TERRACOTTA || material == Material.BLUE_TERRACOTTA;
    }

    /**
     * Colours a kit item for this side: any terracotta becomes this side's clay, and leather armour is dyed. Other
     * items are returned unchanged.
     */
    ItemStack paint(ItemStack item) {
        Material type = item.getType();
        if (type == Material.TERRACOTTA || type == Material.WHITE_TERRACOTTA || isBridgeBlock(type)) {
            return item.withType(clay);
        }
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(armorColor);
            item.setItemMeta(meta);
        }
        return item;
    }
}
