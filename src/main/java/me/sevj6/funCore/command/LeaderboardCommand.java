package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.leaderboard.Leaderboard;
import me.sevj6.funCore.leaderboard.LeaderboardManager;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code /leaderboard}: managing the all-time records (see {@link Leaderboard}).
 *
 * <pre>
 * /leaderboard remove &lt;board&gt; &lt;player&gt;            every record of that player
 * /leaderboard remove &lt;board&gt; &lt;player&gt; &lt;record&gt;   just one: 1 for their best, 2 for their next best, ...
 * </pre>
 * Players are named as the board shows them, so it works for players who are offline.
 */
public final class LeaderboardCommand {

    public static final String PERMISSION = "funcore.command.leaderboard";

    private final LeaderboardManager leaderboards;

    public LeaderboardCommand(LeaderboardManager leaderboards) {
        this.leaderboards = leaderboards;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("leaderboard")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(ctx -> fail(ctx, "Usage: /leaderboard remove <board> <player> [record]"))
                .then(Commands.literal("remove")
                        .then(boardArgument()
                                .then(playerArgument().executes(ctx -> remove(ctx, 0))
                                        .then(Commands.argument("record", IntegerArgumentType.integer(1))
                                                .executes(ctx -> remove(ctx, IntegerArgumentType.getInteger(ctx, "record")))))))
                .build();
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> boardArgument() {
        return Commands.argument("board", StringArgumentType.word()).suggests((ctx, builder) -> {
            for (Leaderboard board : leaderboards.getAll()) {
                if (board.getId().startsWith(builder.getRemainingLowerCase())) builder.suggest(board.getId());
            }
            return builder.buildFuture();
        });
    }

    /** Names of players with records on the chosen board. */
    private RequiredArgumentBuilder<CommandSourceStack, String> playerArgument() {
        return Commands.argument("player", StringArgumentType.word()).suggests((ctx, builder) -> {
            Leaderboard board = leaderboards.get(StringArgumentType.getString(ctx, "board").toLowerCase(Locale.ROOT));
            if (board != null) {
                Set<String> names = new LinkedHashSet<>();
                for (Leaderboard.Entry entry : board.getEntries()) names.add(entry.name());
                for (String name : names) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) builder.suggest(name);
                }
            }
            return builder.buildFuture();
        });
    }

    /** @param which 0 for every record of the player, otherwise their n-th best */
    private int remove(CommandContext<CommandSourceStack> ctx, int which) {
        String boardId = StringArgumentType.getString(ctx, "board").toLowerCase(Locale.ROOT);
        Leaderboard board = leaderboards.get(boardId);
        if (board == null) return fail(ctx, "There's no leaderboard called '" + boardId + "'.");
        String name = StringArgumentType.getString(ctx, "player");
        List<Leaderboard.Entry> theirs = board.getEntries().stream()
                .filter(entry -> entry.name().equalsIgnoreCase(name)).toList();
        if (theirs.isEmpty()) return fail(ctx, name + " has no records on " + boardId + ".");

        if (which == 0) {
            board.remove(theirs.getFirst().player());
            return reply(ctx, "<green>Removed <count> record(s) of <white><name></white> from <board>.",
                    Placeholder.unparsed("count", String.valueOf(theirs.size())), Placeholder.unparsed("name", theirs.getFirst().name()),
                    Placeholder.unparsed("board", boardId));
        }
        if (which > theirs.size()) {
            return fail(ctx, theirs.getFirst().name() + " only has " + theirs.size() + " record(s) on " + boardId + ".");
        }
        Leaderboard.Entry entry = theirs.get(which - 1);
        board.remove(entry);
        return reply(ctx, "<green>Removed <white><name></white>'s record <gray>(<text>)</gray> from <board>.",
                Placeholder.unparsed("name", entry.name()), Placeholder.unparsed("text", entry.text()),
                Placeholder.unparsed("board", boardId));
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
