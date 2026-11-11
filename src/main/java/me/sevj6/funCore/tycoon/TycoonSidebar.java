package me.sevj6.funCore.tycoon;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import me.sevj6.funCore.tycoon.core.Money;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The sidebar a tycoon player sees: their tycoon balance (its own money, separate from the server's economy), what
 * they're making a second, and their rebirths. Each player gets their own scoreboard while they have a plot; the one
 * they had before is put back when they leave, unless something else (a game) has already replaced it.
 */
final class TycoonSidebar {

    private record Shown(Scoreboard board, Scoreboard previous) {
    }

    private final Map<UUID, Shown> shown = new HashMap<>();

    void show(Player player) {
        if (shown.containsKey(player.getUniqueId())) return;
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("tycoon", Criteria.DUMMY, Utils.mm("<gold><bold>Tycoon"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        shown.put(player.getUniqueId(), new Shown(board, player.getScoreboard()));
        player.setScoreboard(board);
    }

    void update(Player player, long balance, long perSecond, int rebirths) {
        Shown entry = shown.get(player.getUniqueId());
        if (entry == null) return;
        Objective objective = entry.board().getObjective("tycoon");
        if (objective == null) return;
        line(objective, "balance", 4, "<gray>Balance: <green><value>", Money.format(balance));
        line(objective, "income", 3, "<gray>Income: <yellow><value>/s", Money.format(perSecond));
        line(objective, "rebirths", 2, "<gray>Rebirths: <light_purple><value>", String.valueOf(rebirths));
        line(objective, "help", 1, "<dark_gray>/tycoon to go home", "");
    }

    private static void line(Objective objective, String entry, int order, String miniMessage, String value) {
        var score = objective.getScore(entry);
        score.setScore(order);
        score.customName(Utils.mm(miniMessage, Placeholder.unparsed("value", value)));
    }

    /** Takes it away, putting back the scoreboard they had (if nothing has replaced ours meanwhile). */
    void hide(@Nullable Player player, UUID id) {
        Shown entry = shown.remove(id);
        if (entry == null || player == null) return;
        if (player.getScoreboard() == entry.board()) player.setScoreboard(entry.previous());
    }
}
