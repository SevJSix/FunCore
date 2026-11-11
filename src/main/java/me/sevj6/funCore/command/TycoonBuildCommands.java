package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.arena.worldedit.WorldEditHook;
import me.sevj6.funCore.tycoon.TycoonManager;
import me.sevj6.funCore.tycoon.core.template.Offset;
import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.tycoon.core.template.TemplateEdits;
import me.sevj6.funCore.tycoon.core.template.TemplateLoader;
import me.sevj6.funCore.tycoon.plot.PlotSlot;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The tycoon builder tools under {@code /tycoon admin}. They edit the template every plot is made from, working on the
 * real map: positions are where you stand, from the origin of the plot you're on. Every change is saved in
 * {@code template-edits.yml} (laid over {@code template.yml}) and the template reloads: if it's valid, every plot
 * uses it straight away; if not, the change stays saved but isn't used until the problems listed are fixed.
 *
 * <pre>
 * where                                       your plot and position on it
 * set spawn | rebirth-pad [x y z]             base positions: where you stand (nudged by x y z)
 * set collector &lt;id&gt; [x y z]                  the bed block under you
 * set trophy &lt;id&gt; [x y z]
 * set &lt;component&gt; pad | spout [x y z]
 * belt &lt;id&gt; add [x y z] | remove &lt;n&gt; | clear   waypoints: the bed block under you
 * upgrader &lt;id&gt; at &lt;belt&gt; &lt;waypoint&gt;
 * capture &lt;component&gt; [solid]                your WorldEdit selection becomes its parts (and barriers, if solid)
 * new &lt;id&gt; dropper|conveyor|upgrader|wall     a new component, its pad where you stand
 * edits                                       whether the edits are in use, or what's stopping them
 * rebuild                                     rebuilds your plot from scratch
 * rebirths &lt;n&gt;                                 sets your rebirths (test mode)
 * </pre>
 */
final class TycoonBuildCommands {

    /** The most blocks one capture can take (each becomes a display entity). */
    private static final int MAX_CAPTURE = 2048;

    private final TycoonManager tycoon;

    TycoonBuildCommands(TycoonManager tycoon) {
        this.tycoon = tycoon;
    }

    /** Adds the builder tools to {@code /tycoon admin}. */
    void attach(LiteralArgumentBuilder<CommandSourceStack> admin) {
        admin.then(Commands.literal("where").executes(this::where));
        admin.then(Commands.literal("edits").executes(this::edits));
        admin.then(Commands.literal("rebuild").executes(this::rebuild));
        admin.then(Commands.literal("rebirths").then(Commands.argument("n", IntegerArgumentType.integer(0, 1000))
                .executes(this::rebirths)));
        admin.then(Commands.literal("set")
                .then(nudged(Commands.literal("spawn"), (ctx, at) -> setPosition(ctx, List.of("base", "spawn"), at, 0)))
                .then(nudged(Commands.literal("rebirth-pad"), (ctx, at) -> setPosition(ctx, List.of("base", "rebirth", "pad"), at, 0)))
                .then(Commands.literal("collector").then(nudged(Commands.argument("id", StringArgumentType.word()),
                        (ctx, at) -> setPosition(ctx, List.of("base", "collectors", StringArgumentType.getString(ctx, "id")), at, -1))))
                .then(Commands.literal("trophy").then(nudged(Commands.argument("id", StringArgumentType.word()),
                        (ctx, at) -> setPosition(ctx, List.of("base", "trophies", StringArgumentType.getString(ctx, "id")), at, 0))))
                .then(Commands.argument("component", StringArgumentType.word()).suggests(components())
                        .then(nudged(Commands.literal("pad"), (ctx, at) -> setPosition(ctx, componentPath(ctx, "pad"), at, 0)))
                        .then(nudged(Commands.literal("spout"), (ctx, at) -> setPosition(ctx, componentPath(ctx, "spout"), at, 0)))));
        admin.then(Commands.literal("belt").then(Commands.argument("belt", StringArgumentType.word()).suggests(belts())
                .then(nudged(Commands.literal("add"), this::addWaypoint))
                .then(Commands.literal("remove").then(Commands.argument("n", IntegerArgumentType.integer(0)).executes(this::removeWaypoint)))
                .then(Commands.literal("clear").executes(this::clearWaypoints))));
        admin.then(Commands.literal("upgrader").then(Commands.argument("component", StringArgumentType.word()).suggests(components())
                .then(Commands.literal("at").then(Commands.argument("belt", StringArgumentType.word()).suggests(belts())
                        .then(Commands.argument("waypoint", IntegerArgumentType.integer(0)).executes(this::upgraderAt))))));
        admin.then(Commands.literal("capture").then(Commands.argument("component", StringArgumentType.word()).suggests(components())
                .executes(ctx -> capture(ctx, false))
                .then(Commands.literal("solid").executes(ctx -> capture(ctx, true)))));
        admin.then(Commands.literal("new").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("type", StringArgumentType.word()).suggests((ctx, builder) -> {
                    for (String type : List.of("dropper", "conveyor", "upgrader", "wall")) {
                        if (type.startsWith(builder.getRemainingLowerCase())) builder.suggest(type);
                    }
                    return builder.buildFuture();
                }).executes(this::newComponent))));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Where you are
    // ------------------------------------------------------------------------------------------------------------

    /** A position command that also takes an optional {@code x y z} nudge from where you stand. */
    private <T extends ArgumentBuilder<CommandSourceStack, T>> T nudged(T node,
            BiFunction<CommandContext<CommandSourceStack>, Offset, Integer> run) {
        return node.executes(ctx -> run.apply(ctx, Offset.ZERO))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> run.apply(ctx, new Offset(IntegerArgumentType.getInteger(ctx, "x"),
                                                IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")))))));
    }

    /** Where the player stands, from their plot's origin (plus {@code nudge}, plus {@code dy}), or {@code null}. */
    private @Nullable Offset here(CommandContext<CommandSourceStack> ctx, Offset nudge, int dy) {
        Player player = player(ctx);
        if (player == null) {
            fail(ctx, "Only players can use the builder tools.");
            return null;
        }
        PlotSlot plot = tycoon.plotAt(player.getLocation());
        if (plot == null) {
            fail(ctx, "Stand on one of the plots: positions are taken from the plot you're on.");
            return null;
        }
        Offset at = TycoonManager.offsetOn(plot, player.getLocation());
        return at.add(nudge.x(), nudge.y() + dy, nudge.z());
    }

    private int where(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return fail(ctx, "Only players have a position.");
        PlotSlot plot = tycoon.plotAt(player.getLocation());
        if (plot == null) return fail(ctx, "You're not on a plot.");
        Offset at = TycoonManager.offsetOn(plot, player.getLocation());
        return reply(ctx, "<gray>Plot <white>" + (plot.index() + 1) + "</white>, standing at <white>" + at
                + "</white> <dark_gray>(the bed or floor under you is " + at.add(0, -1, 0) + ")");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------------------------------------------------

    private List<String> componentPath(CommandContext<CommandSourceStack> ctx, String field) {
        return List.of("components", StringArgumentType.getString(ctx, "component"), field);
    }

    /** @param dy added to where you stand (-1: the block under you) */
    private int setPosition(CommandContext<CommandSourceStack> ctx, List<String> path, Offset nudge, int dy) {
        Offset at = here(ctx, nudge, dy);
        if (at == null) return 0;
        return applied(ctx, String.join(".", path) + " = " + at, tycoon.edit(path, TemplateEdits.position(at)));
    }

    /** Where a belt's path lives: a component's, or a base belt's. */
    private @Nullable List<String> beltPath(CommandContext<CommandSourceStack> ctx, String belt) {
        Object data = templateData(ctx);
        if (data == null) return null;
        if (TemplateEdits.get(data, List.of("components", belt)) != null) return List.of("components", belt, "path");
        if (TemplateEdits.get(data, List.of("base", "belts", belt)) != null) return List.of("base", "belts", belt, "path");
        fail(ctx, "There's no belt '" + belt + "' (a conveyor component, or one of base.belts).");
        return null;
    }

    private List<Object> waypoints(Object data, List<String> path) {
        return TemplateEdits.get(data, path) instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    private int addWaypoint(CommandContext<CommandSourceStack> ctx, Offset nudge) {
        String belt = StringArgumentType.getString(ctx, "belt");
        Offset at = here(ctx, nudge, -1); // the bed block under you
        List<String> path = at == null ? null : beltPath(ctx, belt);
        Object data = path == null ? null : templateData(ctx);
        if (data == null) return 0;
        List<Object> list = waypoints(data, path);
        list.add(TemplateEdits.position(at));
        return applied(ctx, belt + " waypoint " + (list.size() - 1) + " = " + at, tycoon.edit(path, list));
    }

    private int removeWaypoint(CommandContext<CommandSourceStack> ctx) {
        String belt = StringArgumentType.getString(ctx, "belt");
        int n = IntegerArgumentType.getInteger(ctx, "n");
        List<String> path = beltPath(ctx, belt);
        Object data = path == null ? null : templateData(ctx);
        if (data == null) return 0;
        List<Object> list = waypoints(data, path);
        if (n >= list.size()) return fail(ctx, belt + " has waypoints 0 to " + (list.size() - 1) + ".");
        list.remove(n);
        return applied(ctx, belt + " waypoint " + n + " removed", tycoon.edit(path, list));
    }

    private int clearWaypoints(CommandContext<CommandSourceStack> ctx) {
        String belt = StringArgumentType.getString(ctx, "belt");
        List<String> path = beltPath(ctx, belt);
        if (path == null) return 0;
        return applied(ctx, belt + " waypoints cleared", tycoon.edit(path, new ArrayList<>()));
    }

    private int upgraderAt(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "component");
        Map<String, Object> at = new LinkedHashMap<>();
        at.put("belt", StringArgumentType.getString(ctx, "belt"));
        at.put("waypoint", IntegerArgumentType.getInteger(ctx, "waypoint"));
        return applied(ctx, id + " at " + at, tycoon.edit(List.of("components", id, "at"), at));
    }

    /** Your WorldEdit selection (on one plot) becomes a component's parts: one display per block, as the blocks are. */
    private int capture(CommandContext<CommandSourceStack> ctx, boolean solid) {
        Player player = player(ctx);
        if (player == null) return fail(ctx, "Only players can capture.");
        if (!WorldEditHook.isAvailable()) return fail(ctx, "Capturing needs WorldEdit (or FAWE).");
        String id = StringArgumentType.getString(ctx, "component");
        WorldEditHook.Selection selection;
        try {
            selection = WorldEditHook.getSelection(player);
        } catch (IllegalStateException e) {
            return fail(ctx, e.getMessage());
        }
        World world = org.bukkit.Bukkit.getWorld(selection.worldName());
        if (world == null || !tycoon.isTycoonWorld(world)) return fail(ctx, "Select blocks in the tycoon world, on a plot.");
        PlotSlot plot = tycoon.plotAt(new Location(world, selection.min().x() + 0.5, selection.min().y(), selection.min().z() + 0.5));
        PlotSlot other = tycoon.plotAt(new Location(world, selection.max().x() + 0.5, selection.max().y(), selection.max().z() + 0.5));
        if (plot == null || plot != other) return fail(ctx, "The selection has to be on one plot (inside its footprint).");

        List<Object> parts = new ArrayList<>();
        List<Object> barriers = new ArrayList<>();
        for (int x = selection.min().x(); x <= selection.max().x(); x++) {
            for (int y = selection.min().y(); y <= selection.max().y(); y++) {
                for (int z = selection.min().z(); z <= selection.max().z(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir() || block.getType() == Material.BARRIER) continue;
                    if (parts.size() >= MAX_CAPTURE) {
                        return fail(ctx, "That's more than " + MAX_CAPTURE + " blocks; select less (each block becomes an entity).");
                    }
                    Offset at = TycoonManager.offsetOn(plot, block.getLocation());
                    Map<String, Object> part = new LinkedHashMap<>();
                    part.put("block", block.getBlockData().getAsString());
                    part.put("at", TemplateEdits.position(at));
                    parts.add(part);
                    if (solid) barriers.add(TemplateEdits.position(at));
                }
            }
        }
        if (parts.isEmpty()) return fail(ctx, "There are no blocks in the selection.");
        List<String> problems = tycoon.edit(List.of("components", id, "parts"), parts);
        if (solid) problems = tycoon.edit(List.of("components", id, "barriers"), barriers);
        reply(ctx, "<gray>Captured <white>" + parts.size() + "</white> block(s) into <white>" + id + "</white>"
                + (solid ? " (solid)" : "") + ". <dark_gray>Remove the real blocks now (e.g. //set air): its displays show it.");
        return applied(ctx, id + " parts", problems);
    }

    private int newComponent(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        String type = StringArgumentType.getString(ctx, "type").toLowerCase(Locale.ROOT);
        if (!List.of("dropper", "conveyor", "upgrader", "wall").contains(type)) {
            return fail(ctx, "The type is dropper, conveyor, upgrader or wall.");
        }
        Template template = tycoon.template();
        if (template != null && template.component(id) != null) return fail(ctx, "There's already a component '" + id + "'.");
        Offset pad = here(ctx, Offset.ZERO, 0);
        if (pad == null) return 0;
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", type);
        component.put("name", id);
        component.put("price", 0);
        component.put("pad", TemplateEdits.position(pad));
        reply(ctx, "<gray>New " + type + " <white>" + id + "</white>, its pad where you stand. "
                + "<dark_gray>Fill in the rest with these tools or in template-edits.yml (price, requires, ...).");
        return applied(ctx, id, tycoon.edit(List.of("components", id), component));
    }

    /** Rebuilds your plot from scratch, to see changes from the start. */
    private int rebuild(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return fail(ctx, "Only players have a plot.");
        if (!tycoon.rebuild(player.getUniqueId())) return fail(ctx, "You need a plot first (/tycoon).");
        return reply(ctx, "<green>Your plot was rebuilt.");
    }

    /** Sets your rebirth count, in test mode (it goes back when you leave), to see rebirth-gated components. */
    private int rebirths(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return fail(ctx, "Only players have rebirths.");
        int n = IntegerArgumentType.getInteger(ctx, "n");
        String problem = tycoon.testRebirths(player, n);
        if (problem != null) return fail(ctx, problem);
        return reply(ctx, "<green>Rebirths set to " + n + " <gray>(test mode: it goes back when you leave).");
    }

    /** Whether the edits are in use, or what's stopping them. */
    private int edits(CommandContext<CommandSourceStack> ctx) {
        Object data = templateData(ctx);
        if (data == null) return 0;
        TemplateLoader.Result result = TemplateLoader.load(data);
        if (result.ok()) return reply(ctx, "<green>The template (with template-edits.yml) is valid and in use.");
        reply(ctx, "<yellow>template-edits.yml has changes that aren't in use yet. Fix these:");
        result.errors().forEach(error -> ctx.getSource().getSender().sendMessage(Utils.mm("<gray> - <e>", Placeholder.unparsed("e", error))));
        return 0;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    private @Nullable Object templateData(CommandContext<CommandSourceStack> ctx) {
        try {
            return tycoon.templateData();
        } catch (Exception e) {
            fail(ctx, "Couldn't read the template: " + e.getMessage());
            return null;
        }
    }

    /** Reports an edit: in use, or saved with what's stopping it. */
    private int applied(CommandContext<CommandSourceStack> ctx, String what, List<String> problems) {
        if (problems.isEmpty()) return reply(ctx, "<green>" + what + ". <gray>Every plot has it now.");
        reply(ctx, "<yellow>" + what + " saved, but not in use until these are fixed:");
        problems.forEach(problem -> ctx.getSource().getSender().sendMessage(Utils.mm("<gray> - <p>", Placeholder.unparsed("p", problem))));
        return Command.SINGLE_SUCCESS;
    }

    private SuggestionProvider<CommandSourceStack> components() {
        return (ctx, builder) -> {
            Template template = tycoon.template();
            if (template != null) {
                for (String id : template.components().keySet()) {
                    if (id.startsWith(builder.getRemainingLowerCase())) builder.suggest(id);
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> belts() {
        return (ctx, builder) -> {
            Template template = tycoon.template();
            if (template != null) {
                List<String> ids = new ArrayList<>(template.baseBelts().keySet());
                template.components().forEach((id, component) -> {
                    if (component instanceof me.sevj6.funCore.tycoon.core.template.Component.Conveyor) ids.add(id);
                });
                for (String id : ids) if (id.startsWith(builder.getRemainingLowerCase())) builder.suggest(id);
            }
            return builder.buildFuture();
        };
    }

    private static @Nullable Player player(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getExecutor() instanceof Player player ? player : null;
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
