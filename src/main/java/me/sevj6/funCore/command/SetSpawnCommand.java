package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.arena.BlockPos;
import me.sevj6.funCore.arena.Region;
import me.sevj6.funCore.arena.worldedit.WorldEditHook;
import me.sevj6.funCore.spawn.ServerSpawn;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /setspawn}: makes where you stand the server's spawn point (saved to config.yml).
 * <pre>
 * /setspawn                        the spawn point, where you stand
 * /setspawn area                   show the spawn area (the safe lobby: no damage, no PvP, no hunger)
 * /setspawn area pos1|pos2         a corner of it: the block you're standing in
 * /setspawn area selection         all of it: your WorldEdit selection (//wand)
 * /setspawn area clear             remove it
 * </pre>
 */
public final class SetSpawnCommand {

    public static final String PERMISSION = "funcore.command.setspawn";

    private final ServerSpawn spawn;

    public SetSpawnCommand(ServerSpawn spawn) {
        this.spawn = spawn;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("setspawn")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::setSpawn)
                .then(Commands.literal("area").executes(this::showArea)
                        .then(Commands.literal("pos1").executes(ctx -> corner(ctx, true)))
                        .then(Commands.literal("pos2").executes(ctx -> corner(ctx, false)))
                        .then(Commands.literal("selection").executes(this::selection))
                        .then(Commands.literal("clear").executes(this::clear)))
                .build();
    }

    private int setSpawn(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return 0;
        Location at = player.getLocation();
        spawn.set(at);
        player.sendMessage(Utils.prefixed(Utils.mm("<green>Server spawn set to <white><x>, <y>, <z></white> in <white><world></white>.",
                Placeholder.unparsed("x", String.format("%.1f", at.getX())),
                Placeholder.unparsed("y", String.format("%.1f", at.getY())),
                Placeholder.unparsed("z", String.format("%.1f", at.getZ())),
                Placeholder.unparsed("world", at.getWorld().getName()))));
        if (spawn.getArea() == null) {
            reply(ctx, "<gray>Tip: make the lobby safe with /setspawn area pos1 and pos2 (or selection).");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int showArea(CommandContext<CommandSourceStack> ctx) {
        Region area = spawn.getArea();
        if (area == null) {
            BlockPos one = spawn.getAreaCorner(true), two = spawn.getAreaCorner(false);
            if (one == null && two == null) {
                return reply(ctx, "<gray>No spawn area yet. Stand at two opposite corners of the lobby and run /setspawn area pos1 "
                        + "and /setspawn area pos2, or select it with //wand and run /setspawn area selection.");
            }
            return reply(ctx, "<yellow>The spawn area is half set in <white><world></white>: pos1 <one>, pos2 <two>.",
                    Placeholder.unparsed("world", String.valueOf(spawn.getAreaWorld())),
                    Placeholder.unparsed("one", one == null ? "not set" : one.toString()),
                    Placeholder.unparsed("two", two == null ? "not set" : two.toString()));
        }
        return reply(ctx, "<green>Spawn area: " + describe(area) + ". <gray>Inside it there's no damage, no PvP and no hunger.",
                areaPlaceholders(area));
    }

    private int corner(CommandContext<CommandSourceStack> ctx, boolean first) {
        Player player = player(ctx);
        if (player == null) return 0;
        String before = spawn.getAreaWorld();
        spawn.setAreaCorner(first, player.getLocation());
        BlockPos pos = BlockPos.of(player.getLocation());
        if (before != null && !before.equals(player.getWorld().getName())) {
            reply(ctx, "<yellow>The spawn area was in <white><old></white>; it starts over here.", Placeholder.unparsed("old", before));
        }
        Region area = spawn.getArea();
        if (area == null) {
            return reply(ctx, "<green>Set the spawn area's <name> to <pos>. Now set the other corner.",
                    Placeholder.unparsed("name", first ? "pos1" : "pos2"), Placeholder.unparsed("pos", pos.toString()));
        }
        return reply(ctx, "<green>Set the spawn area's <name> to <pos>. It's now " + describe(area) + ".",
                TagResolver.resolver(areaPlaceholders(area), Placeholder.unparsed("name", first ? "pos1" : "pos2"),
                        Placeholder.unparsed("pos", pos.toString())));
    }

    private int selection(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return 0;
        if (!WorldEditHook.isAvailable()) return fail(ctx, "WorldEdit isn't installed; use /setspawn area pos1 and pos2.");
        WorldEditHook.Selection selection;
        try {
            selection = WorldEditHook.getSelection(player);
        } catch (IllegalStateException e) {
            return fail(ctx, e.getMessage());
        }
        spawn.setArea(selection.worldName(), selection.min(), selection.max());
        Region area = Region.of(selection.min(), selection.max());
        reply(ctx, "<green>Set the spawn area to your selection: " + describe(area) + ".", areaPlaceholders(area));
        if (!selection.cuboid()) reply(ctx, "<yellow>Your selection isn't a cuboid, so the box around it was used.");
        return Command.SINGLE_SUCCESS;
    }

    private int clear(CommandContext<CommandSourceStack> ctx) {
        if (spawn.getAreaWorld() == null) return fail(ctx, "There's no spawn area to remove.");
        spawn.clearArea();
        return reply(ctx, "<green>Removed the spawn area: players at spawn can take damage and get hungry again.");
    }

    private static String describe(Region area) {
        return "<white><x> x <y> x <z></white> in <white><world></white>, from <white><min></white> to <white><max></white>";
    }

    private TagResolver areaPlaceholders(Region area) {
        return TagResolver.resolver(
                Placeholder.unparsed("x", String.valueOf(area.sizeX())), Placeholder.unparsed("y", String.valueOf(area.sizeY())),
                Placeholder.unparsed("z", String.valueOf(area.sizeZ())),
                Placeholder.unparsed("world", String.valueOf(spawn.getAreaWorld())),
                Placeholder.unparsed("min", area.minX() + ", " + area.minY() + ", " + area.minZ()),
                Placeholder.unparsed("max", area.maxX() + ", " + area.maxY() + ", " + area.maxZ()));
    }

    private static @Nullable Player player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player player) return player;
        fail(ctx, "Only players can do that; it uses where you are.");
        return null;
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
