package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.arena.worldedit.WorldEditHook;
import me.sevj6.funCore.schematic.SchematicFiles;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * {@code /schematic}: loading WorldEdit schematics with their names suggested as you type, which {@code //schem load}
 * doesn't do. The loading itself is still FAWE's ({@code //schem load}), so it's the same clipboard, messages and
 * permissions.
 * <pre>
 * /schematic load &lt;name&gt;     into your clipboard (then //paste); names from the schematics folder, subfolders as a/b
 * /schematic list [page]     every schematic, click one to load it
 * /schematic &lt;anything else&gt;  passed on to //schem (save, delete, formats, ...), since this takes over WorldEdit's /schematic
 * </pre>
 */
public final class SchematicCommand {

    public static final String PERMISSION = "funcore.command.schematic";
    private static final int PER_PAGE = 10;
    /** How long a listing of the folder is reused, so suggesting as someone types doesn't read it on every key. */
    private static final long CACHE_MILLIS = 3_000;

    private final Logger logger;
    private List<String> cached = List.of();
    private long cachedAt;

    public SchematicCommand(Logger logger) {
        this.logger = logger;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        SuggestionProvider<CommandSourceStack> names = (ctx, builder) -> {
            for (String name : SchematicFiles.matching(names(), builder.getRemaining())) builder.suggest(name);
            return builder.buildFuture();
        };
        return Commands.literal("schematic")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::usage)
                .then(Commands.literal("load")
                        .executes(this::usage)
                        .then(Commands.argument("name", StringArgumentType.greedyString()).suggests(names).executes(this::load)))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.argument("worldedit", StringArgumentType.greedyString()).executes(this::passOn))
                .build();
    }

    private int usage(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Schematics <gray>(FAWE's schematics folder)")));
        sender.sendMessage(Utils.mm("<gray> /schematic load <white><name></white> <dark_gray>- into your clipboard, then //paste (names are suggested)"));
        sender.sendMessage(Utils.mm("<gray> /schematic list <white>[page]</white> <dark_gray>- click one to load it"));
        sender.sendMessage(Utils.mm("<gray> /schematic <white>save|delete|...</white> <dark_gray>- passed on to //schem"));
        return Command.SINGLE_SUCCESS;
    }

    private int load(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return 0;
        String typed = StringArgumentType.getString(ctx, "name").strip();
        List<String> all = names();
        String name = all.stream().filter(candidate -> candidate.equalsIgnoreCase(typed)).findFirst().orElse(null);
        if (name == null) {
            List<String> close = SchematicFiles.matching(all, typed);
            return fail(ctx, "There's no schematic called " + typed + "."
                    + (close.isEmpty() ? " See /schematic list." : " Did you mean " + String.join(", ", close.stream().limit(5).toList()) + "?"));
        }
        if (name.indexOf('"') >= 0) return fail(ctx, "WorldEdit can't load a schematic with \" in its name; rename the file.");
        // FAWE does the loading, so it's exactly //schem load (its messages say when it's ready to //paste).
        player.performCommand("/schem load " + (name.indexOf(' ') >= 0 ? "\"" + name + "\"" : name));
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx, int page) {
        if (!available(ctx)) return 0;
        CommandSender sender = ctx.getSource().getSender();
        List<String> all = names();
        if (all.isEmpty()) return fail(ctx, "There are no schematics in FAWE's schematics folder yet.");
        int pages = (all.size() + PER_PAGE - 1) / PER_PAGE;
        int shown = Math.min(page, pages);
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Schematics <gray>(<count>, page <page>/<pages>; click one to load it)",
                Placeholder.unparsed("count", String.valueOf(all.size())),
                Placeholder.unparsed("page", String.valueOf(shown)), Placeholder.unparsed("pages", String.valueOf(pages)))));
        for (String name : all.subList((shown - 1) * PER_PAGE, Math.min(all.size(), shown * PER_PAGE))) {
            sender.sendMessage(Utils.mm("<dark_gray> - </dark_gray>").append(Component.text(name)
                    .color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
                    .hoverEvent(HoverEvent.showText(Utils.mm("<gray>Load into your clipboard")))
                    .clickEvent(ClickEvent.runCommand("/schematic load " + name))));
        }
        if (pages > 1) {
            Component nav = Component.empty();
            if (shown > 1) nav = nav.append(Utils.mm("<yellow>[< Previous]").clickEvent(ClickEvent.runCommand("/schematic list " + (shown - 1))));
            if (shown > 1 && shown < pages) nav = nav.append(Component.text("  "));
            if (shown < pages) nav = nav.append(Utils.mm("<yellow>[Next >]").clickEvent(ClickEvent.runCommand("/schematic list " + (shown + 1))));
            sender.sendMessage(nav);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Anything else is WorldEdit's own {@code /schematic} subcommand, since this command took that name over. */
    private int passOn(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return 0;
        player.performCommand("/schem " + StringArgumentType.getString(ctx, "worldedit"));
        return Command.SINGLE_SUCCESS;
    }

    /** The schematics' names, read again if the last read is a few seconds old. Empty without WorldEdit. */
    private List<String> names() {
        if (!WorldEditHook.isAvailable()) return List.of();
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MILLIS) return cached;
        try {
            cached = SchematicFiles.list(WorldEditHook.schematicsFolder());
        } catch (IOException | RuntimeException e) {
            logger.warn("Couldn't read the schematics folder: {}", e.toString());
            cached = List.of();
        }
        cachedAt = now;
        return cached;
    }

    private static boolean available(CommandContext<CommandSourceStack> ctx) {
        if (WorldEditHook.isAvailable()) return true;
        fail(ctx, "Schematics need FastAsyncWorldEdit (or WorldEdit), which isn't installed.");
        return false;
    }

    private static Player player(CommandContext<CommandSourceStack> ctx) {
        if (!available(ctx)) return null;
        if (ctx.getSource().getExecutor() instanceof Player player) return player;
        fail(ctx, "Only players can do that; schematics load into your own clipboard.");
        return null;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm("<red><message>", Placeholder.unparsed("message", message))));
        return 0;
    }
}
