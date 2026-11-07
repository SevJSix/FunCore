package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.game.GameManager;
import me.sevj6.funCore.game.GameState;
import me.sevj6.funCore.game.JoinResult;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /game}: play minigames.
 *
 * <pre>
 * /game list               every game, its state and player count
 * /game join &lt;game&gt;
 * /game leave
 * /game start &lt;game&gt;      admin: start now, skipping the countdown
 * /game stop &lt;game&gt;       admin: stop now and send everyone back
 * </pre>
 */
public final class GameCommand {

    public static final String PERMISSION = "funcore.command.game";
    public static final String PERMISSION_ADMIN = "funcore.command.game.admin";

    private final GameManager games;

    public GameCommand(GameManager games) {
        this.games = games;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("game")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::list)
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("join").then(gameArgument().executes(this::join)))
                .then(Commands.literal("leave").executes(this::leave))
                .then(Commands.literal("start")
                        .requires(source -> source.getSender().hasPermission(PERMISSION_ADMIN))
                        .then(gameArgument().executes(this::start)))
                .then(Commands.literal("stop")
                        .requires(source -> source.getSender().hasPermission(PERMISSION_ADMIN))
                        .then(gameArgument().executes(this::stop)))
                .build();
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (games.getGames().isEmpty()) return reply(ctx, "<gray>There are no games right now.");
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Games <gray>(join with /game join <game>)")));
        for (Minigame game : games.getGames()) {
            sender.sendMessage(Utils.mm("<gray> - <name> <dark_gray>(<id>)</dark_gray> <state> <dark_gray><count>/<max> players",
                    Placeholder.component("name", game.getDisplayName()),
                    Placeholder.unparsed("id", game.getId()),
                    Placeholder.parsed("state", describe(game)),
                    Placeholder.unparsed("count", String.valueOf(game.getPlayerCount())),
                    Placeholder.unparsed("max", String.valueOf(game.getMaxPlayers()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String describe(Minigame game) {
        return switch (game.getState()) {
            case WAITING -> "<green>open, needs " + game.getMinPlayers() + "</green>";
            case STARTING -> "<yellow>starting in " + game.getCountdownRemaining() + "s</yellow>";
            case RUNNING -> "<red>in progress</red>";
            case ENDING -> "<red>finishing</red>";
        };
    }

    private int join(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) return fail(ctx, "Only players can join games.");
        Minigame game = game(ctx);
        if (game == null) return 0;
        JoinResult result = game.join(player);
        if (!result.isSuccess()) {
            player.sendMessage(Utils.prefixed(result.message()));
            return 0;
        }
        return Command.SINGLE_SUCCESS; // the game announces the join itself
    }

    private int leave(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) return fail(ctx, "Only players can leave games.");
        Minigame game = games.getGame(player);
        if (game == null) return fail(ctx, "You're not in a game.");
        game.leave(player, LeaveReason.LEFT);
        return reply(ctx, "<gray>You left <name>. Your items and location are back.", Placeholder.component("name", game.getDisplayName()));
    }

    private int start(CommandContext<CommandSourceStack> ctx) {
        Minigame game = game(ctx);
        if (game == null) return 0;
        if (game.getState() == GameState.RUNNING || game.getState() == GameState.ENDING) return fail(ctx, "That game is already running.");
        if (game.getPlayerCount() == 0) return fail(ctx, "Nobody has joined that game yet.");
        game.start();
        return reply(ctx, "<green>Started <name>.", Placeholder.component("name", game.getDisplayName()));
    }

    private int stop(CommandContext<CommandSourceStack> ctx) {
        Minigame game = game(ctx);
        if (game == null) return 0;
        if (game.getState() == GameState.WAITING && game.getPlayerCount() == 0) return fail(ctx, "That game isn't running.");
        game.forceStop();
        return reply(ctx, "<green>Stopped <name>. Everyone in it was sent back.", Placeholder.component("name", game.getDisplayName()));
    }

    // ------------------------------------------------------------------------------------------------------------

    private RequiredArgumentBuilder<CommandSourceStack, String> gameArgument() {
        return Commands.argument("game", StringArgumentType.word()).suggests((ctx, builder) -> {
            for (Minigame game : games.getGames()) {
                if (game.getId().startsWith(builder.getRemainingLowerCase())) builder.suggest(game.getId());
            }
            return builder.buildFuture();
        });
    }

    private Minigame game(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "game");
        Minigame game = games.getGame(id);
        if (game == null) fail(ctx, "There is no game called '" + id + "'. See /game list.");
        return game;
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String miniMessage, TagResolver... resolvers) {
        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm(miniMessage, resolvers)));
        return Command.SINGLE_SUCCESS;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm("<red><message>", Placeholder.unparsed("message", message))));
        return 0;
    }
}
