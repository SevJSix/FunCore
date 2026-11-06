package me.sevj6.funCore.npc.behavior;

import me.sevj6.funCore.menu.MenuRegistry;
import me.sevj6.funCore.npc.Npc;
import me.sevj6.funCore.npc.event.NpcInteractEvent;
import me.sevj6.funCore.util.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * {@code menu}: opens a menu registered in the {@link MenuRegistry} when the NPC is clicked.
 * <pre>
 * - type: menu
 *   menu: shop
 *   click: right
 * </pre>
 */
public final class MenuBehavior implements NpcBehavior {

    public static final String TYPE = "menu";

    private final MenuRegistry menus;
    private final String menu;
    private final ClickFilter click;

    public MenuBehavior(MenuRegistry menus, String menu, ClickFilter click) {
        if (menu.isBlank()) throw new IllegalArgumentException("a menu behavior needs a menu id");
        this.menus = menus;
        this.menu = menu;
        this.click = click;
    }

    public static BehaviorType definition(MenuRegistry menus) {
        return new BehaviorType(TYPE, "<menu id>",
                config -> new MenuBehavior(menus, config.getString("menu", ""), ClickFilter.parse(config.getString("click", "any"))),
                value -> {
                    String id = value.trim();
                    if (!menus.has(id)) {
                        throw new IllegalArgumentException("There is no menu called '" + id + "'. Menus: "
                                + (menus.ids().isEmpty() ? "none registered" : String.join(", ", menus.ids())));
                    }
                    return new MenuBehavior(menus, id, ClickFilter.ANY);
                });
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String summary() {
        return click.configName() + " click opens '" + menu + "'" + (menus.has(menu) ? "" : " (NOT REGISTERED)");
    }

    @Override
    public void save(ConfigurationSection config) {
        config.set("menu", menu);
        config.set("click", click.configName());
    }

    @Override
    public void onInteract(Npc npc, Player player, NpcInteractEvent.Click click) {
        if (!this.click.matches(click)) return;
        if (!menus.open(menu, player)) {
            player.sendMessage(Utils.prefixed(Utils.mm("<red>This isn't available right now.")));
        }
    }
}
