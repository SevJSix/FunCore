package me.sevj6.funCore.tablist;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/**
 * The only class that touches PlaceholderAPI, so nothing else fails to load when it isn't installed. Only call it once
 * {@code isPluginEnabled("PlaceholderAPI")} is true.
 */
final class PlaceholderHook {

    private PlaceholderHook() {
    }

    /** Fills in PlaceholderAPI placeholders for a player. */
    static String apply(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
