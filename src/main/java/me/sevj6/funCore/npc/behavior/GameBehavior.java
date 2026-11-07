package me.sevj6.funCore.npc.behavior;

import me.sevj6.funCore.game.GameManager;
import me.sevj6.funCore.game.JoinResult;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.npc.Npc;
import me.sevj6.funCore.npc.event.NpcInteractEvent;
import me.sevj6.funCore.util.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * {@code game}: clicking the NPC joins a minigame.
 * <pre>
 * - type: game
 *   game: hotpotato
 *   click: right
 * </pre>
 */
public final class GameBehavior implements NpcBehavior {

    public static final String TYPE = "game";

    private final GameManager games;
    private final String game;
    private final ClickFilter click;

    public GameBehavior(GameManager games, String game, ClickFilter click) {
        if (game.isBlank()) throw new IllegalArgumentException("a game behavior needs a game id");
        this.games = games;
        this.game = game;
        this.click = click;
    }

    public static BehaviorType definition(GameManager games) {
        return new BehaviorType(TYPE, "<game id>",
                config -> new GameBehavior(games, config.getString("game", ""), ClickFilter.parse(config.getString("click", "any"))),
                value -> {
                    String id = value.trim();
                    if (games.getGame(id) == null) {
                        throw new IllegalArgumentException("There is no game called '" + id + "'. See /game list.");
                    }
                    return new GameBehavior(games, id, ClickFilter.ANY);
                });
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String summary() {
        return click.configName() + " click joins '" + game + "'" + (games.getGame(game) == null ? " (NO SUCH GAME)" : "");
    }

    @Override
    public void save(ConfigurationSection config) {
        config.set("game", game);
        config.set("click", click.configName());
    }

    @Override
    public void onInteract(Npc npc, Player player, NpcInteractEvent.Click click) {
        if (!this.click.matches(click)) return;
        Minigame minigame = games.getGame(game);
        if (minigame == null) {
            player.sendMessage(Utils.prefixed(Utils.mm("<red>This game isn't available right now.")));
            return;
        }
        JoinResult result = minigame.join(player);
        if (!result.isSuccess()) player.sendMessage(Utils.prefixed(result.message()));
    }
}
