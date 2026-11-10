package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /ping}: your connection's round trip to the server, in milliseconds (as the tab list measures it).
 * {@code /ping <player>} shows someone else's.
 */
public final class PingCommand {

    public static final String PERMISSION = "funcore.command.ping";

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("ping")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::own)
                .then(Commands.argument("player", ArgumentTypes.player()).executes(this::other))
                .build();
    }

    private int own(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm("<red>Use /ping <player> from the console.")));
            return 0;
        }
        return reply(ctx, "<gray>Your ping: <ping>", player);
    }

    private int other(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Player> found = ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        Player player = found.getFirst();
        return reply(ctx, "<gray><name>'s ping: <ping>", player);
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String miniMessage, Player player) {
        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm(miniMessage,
                Placeholder.component("name", player.name()), Placeholder.parsed("ping", format(player.getPing())))));
        return Command.SINGLE_SUCCESS;
    }

    /** Green under 100 ms, yellow under 180 ms, red from there (the steps Clutch's ping grace uses by default). */
    private static String format(int ping) {
        String colour = ping < 100 ? "<green>" : ping < 180 ? "<yellow>" : "<red>";
        return colour + ping + " ms";
    }
}
