package me.sevj6.funCore.menu;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Menus with a name, so config files can refer to them (e.g. an NPC's {@code menu} behaviour). Code registers a
 * factory that builds a fresh menu for each player:
 * <pre>
 * menus.register("shop", player -&gt; new ShopMenu(player));
 * menus.open("shop", player);
 * </pre>
 */
public final class MenuRegistry {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final Map<String, Function<Player, Menu>> factories = new TreeMap<>();

    /** @throws IllegalArgumentException if the id is invalid or already registered */
    public void register(String id, Function<Player, Menu> factory) {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid menu id '" + id + "' (use a-z, 0-9, _ and -)");
        if (factories.putIfAbsent(id, factory) != null) throw new IllegalArgumentException("A menu with id '" + id + "' is already registered");
    }

    public void unregister(String id) {
        factories.remove(id);
    }

    public boolean has(String id) {
        return factories.containsKey(id);
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(factories.keySet());
    }

    /**
     * Builds the menu for this player and opens it. Don't call this from inside a menu click; use
     * {@link MenuClick#open} there.
     *
     * @return {@code false} if no menu has this id
     */
    public boolean open(String id, Player player) {
        Function<Player, Menu> factory = factories.get(id);
        if (factory == null) return false;
        factory.apply(player).open(player);
        return true;
    }
}
