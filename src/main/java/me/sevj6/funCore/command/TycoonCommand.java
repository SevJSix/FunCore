package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.economy.Cash;
import me.sevj6.funCore.tycoon.PlotSession;
import me.sevj6.funCore.tycoon.TycoonManager;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /tycoon}: go to your tycoon plot (entering the tycoon world if you're not in it), and {@code /tycoon leave}
 * to go back to spawn. {@code /tycoon admin ...} holds the admin and testing tools.
 */
public final class TycoonCommand {

    private final TycoonManager tycoon;

    public TycoonCommand(TycoonManager tycoon) {
        this.tycoon = tycoon;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("tycoon")
                .requires(source -> source.getSender().hasPermission(TycoonManager.PERMISSION))
                .executes(this::enter)
                .then(Commands.literal("leave").executes(this::leave))
                .then(Commands.literal("admin")
                        .requires(source -> source.getSender().hasPermission(TycoonManager.PERMISSION_ADMIN))
                        .then(Commands.literal("reload").executes(this::reload))
                        .then(Commands.literal("origin").executes(this::origin))
                        .then(Commands.literal("edit").executes(this::edit))
                        .then(Commands.literal("tp").executes(ctx -> teleport(ctx, 0))
                                .then(Commands.argument("slot", IntegerArgumentType.integer(1))
                                        .executes(ctx -> teleport(ctx, IntegerArgumentType.getInteger(ctx, "slot")))))
                        .then(Commands.literal("plots").executes(this::plots))
                        .then(Commands.literal("fire")
                                .then(Commands.argument("dropper", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            PlotSession session = ctx.getSource().getExecutor() instanceof Player player
                                                    ? tycoon.session(player.getUniqueId()) : null;
                                            if (session != null) {
                                                for (Component.Dropper dropper : session.template().droppers()) {
                                                    if (dropper.id().startsWith(builder.getRemainingLowerCase())) builder.suggest(dropper.id());
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(this::fire))))
                .build();
    }

    private static Player player(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getExecutor() instanceof Player player ? player : null;
    }

    private int enter(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return fail(ctx, "Only players can go to a tycoon plot.");
        String problem = tycoon.enter(player);
        return problem == null ? Command.SINGLE_SUCCESS : fail(ctx, problem);
    }

    private int leave(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return fail(ctx, "Only players can leave the tycoon.");
        if (!tycoon.isTycoonWorld(player.getWorld())) return fail(ctx, "You're not in the tycoon.");
        tycoon.leave(player);
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        List<String> errors = tycoon.reload();
        if (errors.isEmpty()) {
            return reply(ctx, "<green>Tycoon reloaded; " + tycoon.sessions().size() + " plot(s) rebuilt.");
        }
        reply(ctx, "<red>Not reloaded. Nothing changed. Fix these first:");
        for (String error : errors) {
            ctx.getSource().getSender().sendMessage(Utils.mm("<gray> - <error>", Placeholder.unparsed("error", error)));
        }
        return 0;
    }

    private int plots(CommandContext<CommandSourceStack> ctx) {
        if (tycoon.sessions().isEmpty()) return reply(ctx, "<gray>No tycoon plots are active.");
        reply(ctx, "<gold>" + tycoon.sessions().size() + "/" + tycoon.settings().slots().size() + " plots in use:");
        for (PlotSession session : tycoon.sessions()) {
            Player player = Bukkit.getPlayer(session.player());
            ctx.getSource().getSender().sendMessage(Utils.mm(
                    "<gray> - plot <slot> at <x>, <z>: <white><name></white>, <owned> owned, <items> items (<shown> shown), pending <pending>",
                    Placeholder.unparsed("slot", String.valueOf(session.slot() + 1)),
                    Placeholder.unparsed("x", String.valueOf(session.origin().getBlockX())),
                    Placeholder.unparsed("z", String.valueOf(session.origin().getBlockZ())),
                    Placeholder.unparsed("name", player == null ? session.player().toString() : player.getName()),
                    Placeholder.unparsed("owned", String.valueOf(session.sim().owned().size())),
                    Placeholder.unparsed("items", String.valueOf(session.sim().itemCount())),
                    Placeholder.unparsed("shown", String.valueOf(session.views().shown())),
                    Placeholder.unparsed("pending", Cash.format((long) session.pending()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Sets the map origin to the block you stand on: run it from where you pasted the map. */
    private int origin(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return fail(ctx, "Only players can set the map origin.");
        String problem = tycoon.setOrigin(player.getLocation());
        if (problem != null) return fail(ctx, problem);
        Location at = player.getLocation();
        return reply(ctx, "<green>Map origin set to " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ()
                + ". <gray>Check it with /tycoon admin tp 1: you should land on plot 1's spawn pad.");
    }

    private int edit(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return fail(ctx, "Only players can use edit mode.");
        boolean on = tycoon.toggleEditing(player);
        return reply(ctx, on ? "<yellow>Edit mode on: <gray>you can change blocks in the tycoon world. Run it again to stop."
                : "<green>Edit mode off.");
    }

    /** No slot: the hub (or the world's spawn before the origin is set). A slot: its owner's spawn pad. */
    private int teleport(CommandContext<CommandSourceStack> ctx, int slot) {
        Player player = player(ctx);
        if (player == null) return fail(ctx, "Only players can teleport.");
        if (tycoon.getWorld() == null) return fail(ctx, "The tycoon world isn't loaded.");
        if (slot == 0) {
            player.teleport(tycoon.hub());
            return reply(ctx, tycoon.settings().origin() == null
                    ? "<yellow>The map origin isn't set, so this is the empty world's spawn. <gray>Fly, paste the map, then /tycoon admin origin."
                    : "<green>At the hub.");
        }
        Location spawn = tycoon.slotSpawn(slot - 1);
        if (spawn == null) {
            return fail(ctx, tycoon.settings().origin() == null ? "Set the map origin first (/tycoon admin origin)."
                    : "There are " + tycoon.settings().slots().size() + " plots.");
        }
        player.teleport(spawn);
        return reply(ctx, "<green>At plot " + slot + "'s spawn pad. <gray>You should be on its sea lantern, facing the belt.");
    }

    private int fire(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        PlotSession session = player == null ? null : tycoon.session(player.getUniqueId());
        if (session == null) return fail(ctx, "You need a tycoon plot (run /tycoon) to fire a dropper.");
        String id = StringArgumentType.getString(ctx, "dropper");
        if (!session.sim().fireNow(id)) return fail(ctx, "'" + id + "' isn't a dropper you own.");
        return reply(ctx, "<green>Fired " + id + ".");
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String miniMessage) {
        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm(miniMessage)));
        return Command.SINGLE_SUCCESS;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm("<red><message>", Placeholder.unparsed("message", message))));
        return 0;
    }
}
