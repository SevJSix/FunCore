package me.sevj6.funCore.tycoon.core.template;

/**
 * One piece of how something on a plot looks: a display entity the plugin spawns (bought components are made of
 * these, not blocks, so the map itself never changes).
 *
 * @param block    a block display ({@code data} is block data such as {@code minecraft:dropper[facing=down]}) or an
 *                 item display ({@code data} is an item, such as {@code minecraft:coal})
 * @param at       from the plot origin: a block part's minimum corner, an item part's centre
 * @param size     the scale along each axis (1, 1, 1 is a full block)
 */
public record Part(boolean block, String data, Vec at, Vec size) {

    public static Part block(String data, double x, double y, double z, double sx, double sy, double sz) {
        return new Part(true, data, new Vec(x, y, z), new Vec(sx, sy, sz));
    }
}
