package me.sevj6.funCore.game;

import me.sevj6.funCore.menu.Menu;
import me.sevj6.funCore.menu.MenuButton;
import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The team picker players see in the queue of a team-mode match ({@code /game team}, or the wool in their hotbar).
 * One per viewer, since it marks their own choice; {@link Minigame} refreshes every open one when a choice changes.
 */
final class TeamMenu extends Menu {

    private final Minigame game;
    private final Player viewer;
    private final int teams;

    TeamMenu(Minigame game, Player viewer, int teams) {
        super(Utils.mm("Choose a team"), rows(teams));
        this.game = game;
        this.viewer = viewer;
        this.teams = teams;
        refresh();
    }

    /** Teams first, with "random" in the menu's last slot. */
    private static int rows(int teams) {
        return Math.min(6, teams / 9 + 1);
    }

    void refresh() {
        Integer mine = game.getTeamChoice(viewer);
        int size = game.getMode().teamSize();
        for (int slot = 0; slot < teams; slot++) {
            List<UUID> members = game.getTeamChoosers(slot);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + members.size() + "/" + size + " players");
            for (UUID member : members) {
                Player player = Bukkit.getPlayer(member);
                if (player != null) lore.add("<white>- " + player.getName());
            }
            lore.add("");
            boolean chosen = mine != null && mine == slot;
            lore.add(chosen ? "<green>You're on this team." : members.size() >= size ? "<red>Full." : "<yellow>Click to join.");
            int team = slot;
            set(slot, MenuButton.of(ItemBuilder.of(GameTeam.woolOf(slot)).name(GameTeam.nameOf(slot))
                    .lore(lore.toArray(String[]::new)).glow(chosen).build(), click -> game.chooseTeam(viewer, team)));
        }
        set(size() - 1, MenuButton.of(ItemBuilder.of(Material.NETHER_STAR).name(Component.text("Random team"))
                .lore("<gray>Get put on whichever team needs players.", "",
                        mine == null ? "<green>Chosen." : "<yellow>Click to choose.")
                .glow(mine == null).build(), click -> game.chooseTeam(viewer, -1)));
    }

    @Override
    protected void onOpen(Player player) {
        game.teamMenuOpened(this);
    }

    @Override
    protected void onClose(Player player) {
        game.teamMenuClosed(this);
    }
}
