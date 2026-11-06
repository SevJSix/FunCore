package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.menu.ConfirmMenu;
import me.sevj6.funCore.menu.Menu;
import me.sevj6.funCore.menu.MenuButton;
import me.sevj6.funCore.menu.MenuClick;
import me.sevj6.funCore.menu.PagedMenu;
import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /menutest}: TEMPORARY admin command for trying out the menu framework in-game. It opens a sample menu with a
 * click counter, a paged list and a confirm prompt. Nothing here touches real data (no cash is taken).
 *
 * <p>To remove it: delete this file and its line in {@code FunCore.registerCommands()}, and the permission in
 * plugin.yml.
 */
public final class MenuTestCommand {

    public static final String PERMISSION = "funcore.command.menutest";

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("menutest")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(ctx -> {
                    if (!(ctx.getSource().getExecutor() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm("<red>Only players can open menus.")));
                        return 0;
                    }
                    mainMenu().open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private static Menu mainMenu() {
        Menu menu = new Menu(Utils.mm("<dark_gray>Menu test"), 3);

        // A button whose icon changes each time it's clicked.
        int[] clicks = {0};
        menu.set(1, 1, counterButton(clicks));

        menu.set(1, 3, MenuButton.of(
                ItemBuilder.of(Material.BOOKSHELF).name("<gold>Paged list").lore("<gray>100 items across several pages", "<yellow>Click to open").build(),
                click -> click.open(pagedList())));

        ItemStack sword = ItemBuilder.of(Material.DIAMOND_SWORD).name("<aqua>Test Sword").lore("<gray>Price: <gold>$50", "<dark_gray>(test only, no cash is taken)").glow().build();
        menu.set(1, 5, MenuButton.of(
                ItemBuilder.of(sword).addLore("").addLore("<yellow>Click to buy").build(),
                click -> click.open(new ConfirmMenu(Utils.mm("<dark_gray>Buy the Test Sword?"), sword,
                        player -> player.sendMessage(Utils.prefixed(Utils.mm("<green>Confirmed. (Test only, nothing was bought.)"))),
                        player -> player.sendMessage(Utils.prefixed(Utils.mm("<gray>Cancelled.")))))));

        menu.set(1, 7, MenuButton.of(
                ItemBuilder.of(Material.COMPASS).name("<gold>Click types").lore("<gray>Try left, right and shift clicks").build(),
                click -> click.player().sendMessage(Utils.prefixed(Utils.mm("<gray>You clicked with <white><type></white> (left: <left>, right: <right>, shift: <shift>)",
                        Placeholder.unparsed("type", click.type().name()),
                        Placeholder.unparsed("left", String.valueOf(click.isLeftClick())),
                        Placeholder.unparsed("right", String.valueOf(click.isRightClick())),
                        Placeholder.unparsed("shift", String.valueOf(click.isShiftClick())))))));

        menu.set(2, 8, closeButton());
        menu.fillEmpty(MenuButton.filler());
        return menu;
    }

    private static MenuButton counterButton(int[] clicks) {
        return MenuButton.of(
                ItemBuilder.of(Material.EMERALD).amount(Math.clamp(clicks[0], 1, 64))
                        .name("<green>Click counter").lore("<gray>Clicked <white>" + clicks[0] + "</white> times", "<yellow>Click to count").build(),
                click -> {
                    clicks[0]++;
                    click.menu().set(click.slot(), counterButton(clicks));
                });
    }

    private static PagedMenu pagedList() {
        PagedMenu menu = new PagedMenu(Utils.mm("<dark_gray>Paged list"), 6);
        List<MenuButton> items = new ArrayList<>();
        for (Material material : Material.values()) {
            if (items.size() == 100) break;
            if (material.isLegacy() || !material.isItem() || material.isAir()) continue;
            int number = items.size() + 1;
            items.add(MenuButton.of(
                    ItemBuilder.of(material).name("<white>Item #" + number).lore("<gray>" + material.name().toLowerCase()).build(),
                    click -> click.player().sendMessage(Utils.prefixed(Utils.mm("<gray>You picked item <white>#" + number + "</white>.")))));
        }
        menu.setItems(items);
        menu.set(5, 0, MenuButton.of(ItemBuilder.of(Material.OAK_DOOR).name("<yellow>Back").build(), click -> click.open(mainMenu())));
        menu.set(5, 8, closeButton());
        return menu;
    }

    private static MenuButton closeButton() {
        return MenuButton.of(ItemBuilder.of(Material.BARRIER).name("<red>Close").build(), MenuClick::close);
    }
}
