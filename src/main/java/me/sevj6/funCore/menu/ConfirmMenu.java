package me.sevj6.funCore.menu;

import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A yes/no menu for anything that costs cash or can't be undone:
 * <pre>
 * new ConfirmMenu(Utils.mm("Buy this sword?"), swordIcon,
 *         player -&gt; buySword(player),
 *         player -&gt; player.sendMessage("Cancelled.")).open(player);
 * </pre>
 * Exactly one of the two callbacks runs, once, on the tick after the menu closes (so it's safe for a callback to open
 * another menu). Closing the menu without choosing, or logging out, counts as cancelling. Make a new instance for each
 * question.
 */
public final class ConfirmMenu extends Menu {

    private final Consumer<Player> onConfirm;
    private final @Nullable Consumer<Player> onCancel;
    private boolean decided;

    /**
     * @param preview  shown in the middle, e.g. the item being bought
     * @param onCancel {@code null} to do nothing on cancel
     */
    public ConfirmMenu(Component title, ItemStack preview, Consumer<Player> onConfirm, @Nullable Consumer<Player> onCancel) {
        super(title, 3);
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;

        set(1, 2, MenuButton.of(ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE).name("<green><bold>Confirm").build(),
                click -> decide(click, true)));
        set(1, 4, MenuButton.display(preview));
        set(1, 6, MenuButton.of(ItemBuilder.of(Material.RED_STAINED_GLASS_PANE).name("<red><bold>Cancel").build(),
                click -> decide(click, false)));
        fillEmpty(MenuButton.filler());
    }

    private void decide(MenuClick click, boolean confirmed) {
        if (decided) return; // a second click before the menu closes
        decided = true;
        click.close();
        runLater(confirmed ? onConfirm : onCancel, click.player());
    }

    @Override
    protected void onClose(Player player) {
        if (decided) return;
        decided = true;
        runLater(onCancel, player);
    }

    private static void runLater(@Nullable Consumer<Player> callback, Player player) {
        if (callback != null) Utils.runLater(() -> callback.accept(player), 1L);
    }
}
