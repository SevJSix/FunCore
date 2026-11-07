package me.sevj6.funCore.npc.behavior;

import me.sevj6.funCore.game.GameManager;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.game.JoinResult;
import me.sevj6.funCore.game.TeamMode;
import me.sevj6.funCore.npc.Npc;
import me.sevj6.funCore.npc.event.NpcInteractEvent;
import me.sevj6.funCore.util.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * {@code game}: clicking the NPC joins a minigame, optionally in a given team mode.
 * <pre>
 * - type: game
 *   game: spleef
 *   mode: duos       # optional; any mode if left out
 *   click: right
 * </pre>
 * From a command: {@code /npc behavior <id> add game spleef} or {@code ... add game spleef duos}.
 */
public final class GameBehavior implements NpcBehavior {

    public static final String TYPE = "game";

    private final GameManager games;
    private final String game;
    private final @Nullable String mode;
    private final ClickFilter click;

    public GameBehavior(GameManager games, String game, @Nullable String mode, ClickFilter click) {
        if (game.isBlank()) throw new IllegalArgumentException("a game behavior needs a game id");
        this.games = games;
        this.game = game;
        this.mode = mode == null || mode.isBlank() ? null : mode;
        this.click = click;
    }

    public static BehaviorType definition(GameManager games) {
        return new BehaviorType(TYPE, "<game id> [mode]",
                config -> new GameBehavior(games, config.getString("game", ""), config.getString("mode"),
                        ClickFilter.parse(config.getString("click", "any"))),
                value -> {
                    String[] parts = value.trim().split("\\s+");
                    if (parts.length > 2) throw new IllegalArgumentException("Give a game id and, optionally, a mode.");
                    GameType type = games.getType(parts[0]);
                    if (type == null) throw new IllegalArgumentException("There is no game called '" + parts[0] + "'. See /game list.");
                    String mode = parts.length == 2 ? parts[1] : null;
                    if (mode != null && type.getMode(mode) == null) {
                        throw new IllegalArgumentException(type.getId() + " has no mode called '" + mode + "'.");
                    }
                    return new GameBehavior(games, type.getId(), mode, ClickFilter.ANY);
                });
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String summary() {
        GameType type = games.getType(game);
        String problem = type == null ? " (NO SUCH GAME)" : mode != null && type.getMode(mode) == null ? " (NO SUCH MODE)" : "";
        return click.configName() + " click joins '" + game + "'" + (mode == null ? "" : " (" + mode + ")") + problem;
    }

    @Override
    public void save(ConfigurationSection config) {
        config.set("game", game);
        if (mode != null) config.set("mode", mode);
        config.set("click", click.configName());
    }

    @Override
    public void onInteract(Npc npc, Player player, NpcInteractEvent.Click click) {
        if (!this.click.matches(click)) return;
        GameType type = games.getType(game);
        TeamMode teamMode = type == null || mode == null ? null : type.getMode(mode);
        if (type == null || (mode != null && teamMode == null)) {
            player.sendMessage(Utils.prefixed(Utils.mm("<red>This game isn't available right now.")));
            return;
        }
        JoinResult result = games.join(player, type, teamMode);
        if (!result.isSuccess()) player.sendMessage(Utils.prefixed(result.message()));
    }
}
