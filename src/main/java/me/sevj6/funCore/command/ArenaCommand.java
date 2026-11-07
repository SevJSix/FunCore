package me.sevj6.funCore.command;

import com.destroystokyo.paper.ParticleBuilder;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.arena.Arena;
import me.sevj6.funCore.arena.ArenaManager;
import me.sevj6.funCore.arena.ArenaPoint;
import me.sevj6.funCore.arena.BlockPos;
import me.sevj6.funCore.arena.Region;
import me.sevj6.funCore.arena.worldedit.WorldEditHook;
import me.sevj6.funCore.game.GameManager;
import me.sevj6.funCore.game.GameState;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * {@code /arena}: set up where minigames are played. Positions come from where you stand.
 *
 * <pre>
 * /arena list
 * /arena info &lt;arena&gt;
 * /arena create &lt;arena&gt;            new arena in your world
 * /arena delete &lt;arena&gt;
 * /arena pos1|pos2 &lt;arena&gt;         region corners (the block you're standing in)
 * /arena setlobby &lt;arena&gt;          where players wait after joining
 * /arena addspawn &lt;arena&gt;          where players go when the game starts (add as many as you like)
 * /arena clearspawns &lt;arena&gt;
 * /arena setpoint &lt;arena&gt; &lt;name&gt;   any other position a game uses
 * /arena delpoint &lt;arena&gt; &lt;name&gt;
 * /arena area &lt;arena&gt; &lt;name&gt; pos1|pos2|delete   a named area inside the arena, e.g. a goal
 * /arena setgame &lt;arena&gt; &lt;game&gt;    the game played here (a game can have many arenas, each running its own match)
 * /arena cleargame &lt;arena&gt;
 * /arena set &lt;arena&gt; break|place &lt;true|false&gt;   let players in a match break / place blocks in the region
 * /arena set &lt;arena&gt; pickup|drop &lt;true|false&gt;   let players in its game pick up / drop items
 * /arena set &lt;arena&gt; mobs &lt;true|false&gt;          let mobs spawn in the region (off by default)
 * /arena set &lt;arena&gt; environment &lt;true|false&gt;   let fire, snowfall, melting, growth, ... change it (off by default)
 * /arena snapshot &lt;arena&gt;          save the region's blocks; they're put back after every match
 * /arena restore &lt;arena&gt;           put the blocks back now
 * /arena tp &lt;arena&gt;
 * /arena show &lt;arena&gt;              outline the region and mark the points for a while
 *
 * With WorldEdit / FastAsyncWorldEdit installed:
 * /arena setregion &lt;arena&gt;         set the region to your wand selection (//wand)
 * /arena select &lt;arena&gt;            make the region your selection, to see or adjust it
 * /arena area &lt;arena&gt; &lt;name&gt; selection        set a named area to your selection
 * </pre>
 */
public final class ArenaCommand {

    public static final String PERMISSION = "funcore.command.arena";
    private static final int SHOW_SECONDS = 15;

    private final ArenaManager arenas;
    private final GameManager games;

    public ArenaCommand(ArenaManager arenas, GameManager games) {
        this.arenas = arenas;
        this.games = games;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("arena")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::list)
                .then(Commands.literal("list").executes(this::list))
                // Every subcommand also runs when arguments are missing, to show its usage instead of the vanilla
                // "Unknown or incomplete command" error.
                .then(Commands.literal("info").executes(usage("info <arena>")).then(arenaArgument().executes(this::info)))
                .then(Commands.literal("create").executes(usage("create <arena>"))
                        .then(Commands.argument("arena", StringArgumentType.word()).executes(this::create)))
                .then(Commands.literal("delete").executes(usage("delete <arena>")).then(arenaArgument().executes(this::delete)))
                .then(Commands.literal("pos1").executes(usage("pos1 <arena>")).then(arenaArgument().executes(ctx -> corner(ctx, true))))
                .then(Commands.literal("pos2").executes(usage("pos2 <arena>")).then(arenaArgument().executes(ctx -> corner(ctx, false))))
                .then(Commands.literal("setlobby").executes(usage("setlobby <arena>")).then(arenaArgument().executes(this::setLobby)))
                .then(Commands.literal("addspawn").executes(usage("addspawn <arena>")).then(arenaArgument().executes(this::addSpawn)))
                .then(Commands.literal("clearspawns").executes(usage("clearspawns <arena>")).then(arenaArgument().executes(this::clearSpawns)))
                .then(Commands.literal("setpoint").executes(usage("setpoint <arena> <name>"))
                        .then(arenaArgument().executes(usage("setpoint <arena> <name>")).then(pointArgument(false).executes(this::setPoint))))
                .then(Commands.literal("delpoint").executes(usage("delpoint <arena> <name>"))
                        .then(arenaArgument().executes(usage("delpoint <arena> <name>")).then(pointArgument(true).executes(this::delPoint))))
                .then(Commands.literal("area").executes(usage("area <arena> <name> <pos1|pos2|selection|delete>"))
                        .then(arenaArgument().executes(usage("area <arena> <name> <pos1|pos2|selection|delete>"))
                                .then(areaArgument().executes(usage("area <arena> <name> <pos1|pos2|selection|delete>"))
                                        .then(Commands.literal("pos1").executes(ctx -> areaCorner(ctx, true)))
                                        .then(Commands.literal("pos2").executes(ctx -> areaCorner(ctx, false)))
                                        .then(Commands.literal("selection").executes(this::areaFromSelection))
                                        .then(Commands.literal("delete").executes(this::deleteArea)))))
                .then(Commands.literal("setgame").executes(usage("setgame <arena> <game>"))
                        .then(arenaArgument().executes(usage("setgame <arena> <game>")).then(gameArgument().executes(this::setGame))))
                .then(Commands.literal("set").executes(usage("set <arena> <break|place|pickup|drop|mobs|environment> <true|false>"))
                        .then(arenaArgument().executes(usage("set <arena> <break|place|pickup|drop|mobs|environment> <true|false>"))
                                .then(setting("break"))
                                .then(setting("place"))
                                .then(setting("pickup"))
                                .then(setting("drop"))
                                .then(setting("mobs"))
                                .then(setting("environment"))))
                .then(Commands.literal("cleargame").executes(usage("cleargame <arena>")).then(arenaArgument().executes(this::clearGame)))
                .then(Commands.literal("snapshot").executes(usage("snapshot <arena>")).then(arenaArgument().executes(this::snapshot)))
                .then(Commands.literal("restore").executes(usage("restore <arena>")).then(arenaArgument().executes(this::restore)))
                .then(Commands.literal("tp").executes(usage("tp <arena>")).then(arenaArgument().executes(this::teleport)))
                .then(Commands.literal("show").executes(usage("show <arena>")).then(arenaArgument().executes(this::show)));
        if (WorldEditHook.isAvailable()) {
            root.then(Commands.literal("setregion").executes(usage("setregion <arena>")).then(arenaArgument().executes(this::setRegion)))
                    .then(Commands.literal("select").executes(usage("select <arena>")).then(arenaArgument().executes(this::select)));
        }
        return root.build();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Info
    // ------------------------------------------------------------------------------------------------------------

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (arenas.getArenas().isEmpty()) return reply(ctx, "<gray>There are no arenas yet. Make one with /arena create <name>.");
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Arenas")));
        for (Arena arena : arenas.getArenas()) {
            sender.sendMessage(Utils.mm("<gray> - <white><id></white> <dark_gray>in <world></dark_gray> <game>",
                    Placeholder.unparsed("id", arena.getId()),
                    Placeholder.unparsed("world", arena.getWorldName()),
                    Placeholder.parsed("game", arena.getGame() == null ? "<dark_gray>(no game)" : "<yellow>" + arena.getGame())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        Arena arena = arena(ctx);
        if (arena == null) return 0;
        CommandSender sender = ctx.getSource().getSender();
        Region region = arena.getRegion();
        String regionText = region != null
                ? region + " <dark_gray>(" + region.sizeX() + " x " + region.sizeY() + " x " + region.sizeZ() + ", " + region.volume() + " blocks)"
                : "<red>not set</red> <dark_gray>(pos1 " + orNone(arena.getCorner1()) + ", pos2 " + orNone(arena.getCorner2()) + ")";
        String snapshot = !arena.hasSnapshot() ? "<red>none</red> <dark_gray>(blocks won't be reset)"
                : arena.isSnapshotCurrent() ? "<green>saved</green>"
                : "<red>out of date</red> <dark_gray>(the region changed; run /arena snapshot)";
        String status = arena.isRestoring() ? "<yellow>restoring blocks" : arena.isDirty() ? "<yellow>needs a restore" : "<green>ready";

        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Arena <white><id>", Placeholder.unparsed("id", arena.getId()))));
        line(sender, "World", arena.getWorldName() + (arena.isAvailable() ? "" : " <red>(not loaded)"));
        line(sender, "Region", regionText);
        line(sender, "Lobby", orNone(arena.getLobbyPoint()));
        line(sender, "Spawns", arena.getSpawnPoints().isEmpty() ? "<dark_gray>none" : String.valueOf(arena.getSpawnPoints().size()));
        line(sender, "Points", arena.getPoints().isEmpty() ? "<dark_gray>none" : String.join(", ", arena.getPoints().keySet()));
        line(sender, "Areas", arena.getAreas().isEmpty() ? "<dark_gray>none" : String.join(", ", arena.getAreas().keySet()));
        line(sender, "Game", arena.getGame() == null ? "<dark_gray>none" : arena.getGame()
                + (games.getType(arena.getGame()) == null ? " <red>(no such game)" : ""));
        line(sender, "Breaking", arena.isBreakAllowed() ? "<green>allowed in the region" : "<red>not allowed");
        line(sender, "Placing", arena.isPlaceAllowed() ? "<green>allowed in the region" : "<red>not allowed");
        line(sender, "Item pickup", arena.isPickupAllowed() ? "<green>allowed" : "<red>not allowed");
        line(sender, "Item drops", arena.isDropAllowed() ? "<green>allowed" : "<red>not allowed");
        line(sender, "Mob spawning", arena.isMobSpawningAllowed() ? "<green>allowed" : "<red>not allowed");
        line(sender, "Environment", arena.isEnvironmentAllowed() ? "<green>changes allowed"
                : "<red>frozen</red> <dark_gray>(no fire spread, snowfall, melting, growth, ...)");
        line(sender, "Snapshot", snapshot);
        line(sender, "Status", status);
        GameType type = arena.getGame() == null ? null : games.getType(arena.getGame());
        if (type != null) {
            List<String> problems = type.checkArena(arena);
            line(sender, "Ready", problems.isEmpty() ? "<green>yes, " + type.getId() + " can be played here"
                    : "<red>no, " + type.getId() + " needs: " + String.join("; ", problems));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void line(CommandSender sender, String label, String miniMessageValue) {
        sender.sendMessage(Utils.mm("<gray>  <label>: <white><value>", Placeholder.unparsed("label", label),
                Placeholder.parsed("value", miniMessageValue)));
    }

    private static String orNone(@Nullable Object value) {
        return value == null ? "<dark_gray>none</dark_gray>" : value.toString();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Create / delete
    // ------------------------------------------------------------------------------------------------------------

    private int create(CommandContext<CommandSourceStack> ctx) {
        Player player = player(ctx);
        if (player == null) return 0;
        String id = StringArgumentType.getString(ctx, "arena").toLowerCase();
        try {
            arenas.create(id, player.getWorld());
        } catch (IllegalArgumentException e) {
            return fail(ctx, e.getMessage());
        }
        return reply(ctx, "<green>Created arena <white><id></white> in <world>. Next: stand at two opposite corners and run /arena pos1 <id> and /arena pos2 <id>.",
                Placeholder.unparsed("id", id), Placeholder.unparsed("world", player.getWorld().getName()));
    }

    private int delete(CommandContext<CommandSourceStack> ctx) {
        Arena arena = editableArena(ctx);
        if (arena == null) return 0;
        arenas.delete(arena.getId());
        if (arena.getGame() != null) games.sync(arena.getGame());
        return reply(ctx, "<green>Deleted arena <white><id></white> and its snapshot.", Placeholder.unparsed("id", arena.getId()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Region and points
    // ------------------------------------------------------------------------------------------------------------

    private int corner(CommandContext<CommandSourceStack> ctx, boolean first) {
        Arena arena = editableArena(ctx);
        Player player = arena == null ? null : playerInWorld(ctx, arena);
        if (player == null) return 0;
        BlockPos pos = BlockPos.of(player.getLocation());
        if (first) arena.setCorner1(pos);
        else arena.setCorner2(pos);
        arenas.save(arena);

        String name = first ? "pos1" : "pos2";
        Region region = arena.getRegion();
        if (region == null) {
            return reply(ctx, "<green>Set <name> to <pos>. Now set the other corner.", Placeholder.unparsed("name", name),
                    Placeholder.unparsed("pos", pos.toString()));
        }
        reply(ctx, "<green>Set <name> to <pos>. The region is <x> x <y> x <z> (<volume> blocks).",
                Placeholder.unparsed("name", name), Placeholder.unparsed("pos", pos.toString()),
                Placeholder.unparsed("x", String.valueOf(region.sizeX())), Placeholder.unparsed("y", String.valueOf(region.sizeY())),
                Placeholder.unparsed("z", String.valueOf(region.sizeZ())), Placeholder.unparsed("volume", String.valueOf(region.volume())));
        if (arena.hasSnapshot() && !arena.isSnapshotCurrent()) {
            reply(ctx, "<yellow>The snapshot no longer matches the region, so blocks won't be reset. Run /arena snapshot <id> when the arena is built.",
                    Placeholder.unparsed("id", arena.getId()));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Both corners from the player's WorldEdit selection. */
    private int setRegion(CommandContext<CommandSourceStack> ctx) {
        Arena arena = editableArena(ctx);
        Player player = arena == null ? null : player(ctx);
        if (player == null) return 0;
        WorldEditHook.Selection selection;
        try {
            selection = WorldEditHook.getSelection(player);
        } catch (IllegalStateException e) {
            return fail(ctx, e.getMessage());
        }
        if (!selection.worldName().equals(arena.getWorldName())) {
            return fail(ctx, "Your selection is in '" + selection.worldName() + "', but " + arena.getId() + " is in '" + arena.getWorldName() + "'.");
        }
        arena.setCorner1(selection.min());
        arena.setCorner2(selection.max());
        arenas.save(arena);
        Region region = Objects.requireNonNull(arena.getRegion());
        reply(ctx, "<green>Set the region of <white><id></white> to your selection: <x> x <y> x <z> (<volume> blocks).",
                Placeholder.unparsed("id", arena.getId()),
                Placeholder.unparsed("x", String.valueOf(region.sizeX())), Placeholder.unparsed("y", String.valueOf(region.sizeY())),
                Placeholder.unparsed("z", String.valueOf(region.sizeZ())), Placeholder.unparsed("volume", String.valueOf(region.volume())));
        if (!selection.cuboid()) reply(ctx, "<yellow>Your selection isn't a cuboid, so the box around it was used.");
        if (arena.hasSnapshot() && !arena.isSnapshotCurrent()) {
            reply(ctx, "<yellow>The snapshot no longer matches the region, so blocks won't be reset. Run /arena snapshot <id> when the arena is built.",
                    Placeholder.unparsed("id", arena.getId()));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Puts the arena's region into the player's WorldEdit selection. */
    private int select(CommandContext<CommandSourceStack> ctx) {
        Arena arena = arena(ctx);
        Player player = arena == null ? null : player(ctx);
        if (player == null) return 0;
        World world = arena.getWorld();
        Region region = arena.getRegion();
        if (world == null) return fail(ctx, "World '" + arena.getWorldName() + "' isn't loaded.");
        if (region == null) return fail(ctx, arena.getId() + " has no region yet.");
        WorldEditHook.select(player, world, region);
        return reply(ctx, "<green>Selected the region of <white><id></white>. Adjust it with the wand, then run /arena setregion <id>.",
                Placeholder.unparsed("id", arena.getId()));
    }

    /** Area corners set with pos1/pos2 but not both yet, by "arena:area". */
    private final Map<String, BlockPos[]> pendingAreas = new HashMap<>();

    private int areaCorner(CommandContext<CommandSourceStack> ctx, boolean first) {
        Arena arena = editableArena(ctx);
        Player player = arena == null ? null : playerInWorld(ctx, arena);
        if (player == null) return 0;
        String name = areaName(ctx);
        if (name == null) return 0;
        String key = arena.getId() + ":" + name;
        BlockPos[] corners = pendingAreas.computeIfAbsent(key, k -> new BlockPos[2]);
        corners[first ? 0 : 1] = BlockPos.of(player.getLocation());
        if (corners[0] == null || corners[1] == null) {
            return reply(ctx, "<green>Set <which> of area <white><name></white>. Now stand at the opposite corner and run /arena area <id> <name> <other>.",
                    Placeholder.unparsed("which", first ? "pos1" : "pos2"), Placeholder.unparsed("name", name),
                    Placeholder.unparsed("id", arena.getId()), Placeholder.unparsed("other", first ? "pos2" : "pos1"));
        }
        pendingAreas.remove(key);
        return saveArea(ctx, arena, name, Region.of(corners[0], corners[1]));
    }

    private int areaFromSelection(CommandContext<CommandSourceStack> ctx) {
        if (!WorldEditHook.isAvailable()) return fail(ctx, "WorldEdit isn't installed. Use pos1 and pos2 instead.");
        Arena arena = editableArena(ctx);
        Player player = arena == null ? null : player(ctx);
        if (player == null) return 0;
        String name = areaName(ctx);
        if (name == null) return 0;
        WorldEditHook.Selection selection;
        try {
            selection = WorldEditHook.getSelection(player);
        } catch (IllegalStateException e) {
            return fail(ctx, e.getMessage());
        }
        if (!selection.worldName().equals(arena.getWorldName())) {
            return fail(ctx, "Your selection is in '" + selection.worldName() + "', but " + arena.getId() + " is in '" + arena.getWorldName() + "'.");
        }
        return saveArea(ctx, arena, name, Region.of(selection.min(), selection.max()));
    }

    private int saveArea(CommandContext<CommandSourceStack> ctx, Arena arena, String name, Region area) {
        arena.setArea(name, area);
        arenas.save(arena);
        reply(ctx, "<green>Set area <white><name></white> of <white><id></white>: <size> blocks.",
                Placeholder.unparsed("name", name), Placeholder.unparsed("id", arena.getId()),
                Placeholder.unparsed("size", area.sizeX() + " x " + area.sizeY() + " x " + area.sizeZ()));
        Region region = arena.getRegion();
        if (region != null && !(region.contains(area.minX(), area.minY(), area.minZ())
                && region.contains(area.maxX(), area.maxY(), area.maxZ()))) {
            reply(ctx, "<yellow>It isn't entirely inside the arena's region, so players may not be able to reach all of it.");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int deleteArea(CommandContext<CommandSourceStack> ctx) {
        Arena arena = editableArena(ctx);
        if (arena == null) return 0;
        String name = areaName(ctx);
        if (name == null) return 0;
        pendingAreas.remove(arena.getId() + ":" + name);
        if (arena.getArea(name) == null) return fail(ctx, arena.getId() + " has no area called '" + name + "'.");
        arena.setArea(name, null);
        arenas.save(arena);
        return reply(ctx, "<green>Removed area <white><name></white>.", Placeholder.unparsed("name", name));
    }

    private static @Nullable String areaName(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "area").toLowerCase();
        if (!ArenaManager.isValidId(name)) {
            fail(ctx, "Area names are 1-32 characters: a-z, 0-9, _ and -.");
            return null;
        }
        return name;
    }

    private int setLobby(CommandContext<CommandSourceStack> ctx) {
        return editPoint(ctx, (arena, here) -> {
            arena.setLobby(here);
            return "<green>Set the lobby of <white>" + arena.getId() + "</white> to where you are.";
        });
    }

    private int addSpawn(CommandContext<CommandSourceStack> ctx) {
        return editPoint(ctx, (arena, here) -> {
            arena.addSpawn(here);
            String warning = arena.getRegion() != null && !arena.getRegion().contains(here.x(), here.y(), here.z())
                    ? " <yellow>It's outside the region, so players who start there won't be kept inside the arena." : "";
            return "<green>Added spawn #" + arena.getSpawnPoints().size() + " to <white>" + arena.getId() + "</white>." + warning;
        });
    }

    private int clearSpawns(CommandContext<CommandSourceStack> ctx) {
        Arena arena = arena(ctx);
        if (arena == null) return 0;
        int count = arena.getSpawnPoints().size();
        arena.clearSpawns();
        arenas.save(arena);
        return reply(ctx, "<green>Removed <count> <unit> from <white><id></white>.", Placeholder.unparsed("count", String.valueOf(count)),
                Placeholder.unparsed("unit", Utils.plural(count, "spawn", "spawns")), Placeholder.unparsed("id", arena.getId()));
    }

    private int setPoint(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name").toLowerCase();
        if (!ArenaManager.isValidId(name)) return fail(ctx, "Point names are 1-32 characters: a-z, 0-9, _ and -.");
        return editPoint(ctx, (arena, here) -> {
            arena.setPoint(name, here);
            return "<green>Set point <white>" + name + "</white> of <white>" + arena.getId() + "</white> to where you are.";
        });
    }

    private int delPoint(CommandContext<CommandSourceStack> ctx) {
        Arena arena = arena(ctx);
        if (arena == null) return 0;
        String name = StringArgumentType.getString(ctx, "name").toLowerCase();
        if (!arena.getPoints().containsKey(name)) return fail(ctx, arena.getId() + " has no point called '" + name + "'.");
        arena.setPoint(name, null);
        arenas.save(arena);
        return reply(ctx, "<green>Removed point <white><name></white>.", Placeholder.unparsed("name", name));
    }

    /** Runs an edit that uses the player's position, saves the arena and replies with the edit's message. */
    private int editPoint(CommandContext<CommandSourceStack> ctx, BiFunction<Arena, ArenaPoint, String> edit) {
        Arena arena = arena(ctx);
        Player player = arena == null ? null : playerInWorld(ctx, arena);
        if (player == null) return 0;
        String message = edit.apply(arena, ArenaPoint.centered(player.getLocation()));
        arenas.save(arena);
        return reply(ctx, message);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Game
    // ------------------------------------------------------------------------------------------------------------

    private int setGame(CommandContext<CommandSourceStack> ctx) {
        Arena arena = editableArena(ctx);
        if (arena == null) return 0;
        String gameId = StringArgumentType.getString(ctx, "game").toLowerCase();
        GameType type = games.getType(gameId);
        if (type == null) return fail(ctx, "There is no game called '" + gameId + "'. See /game list.");
        String previous = arena.getGame();
        if (gameId.equals(previous)) return fail(ctx, arena.getId() + " is already one of " + gameId + "'s arenas.");
        arenas.setGame(arena, gameId);
        if (previous != null) games.sync(previous);
        games.sync(gameId);
        int count = arenas.getArenasFor(gameId).size();
        reply(ctx, "<green><white><id></white> now runs <game> matches. It has <count> <unit>, so up to <count> matches can run at once.",
                Placeholder.unparsed("id", arena.getId()), Placeholder.component("game", type.getDisplayName()),
                Placeholder.unparsed("count", String.valueOf(count)), Placeholder.unparsed("unit", Utils.plural(count, "arena", "arenas")));
        if (previous != null) reply(ctx, "<gray>(It was <previous>'s before.)", Placeholder.unparsed("previous", previous));
        return Command.SINGLE_SUCCESS;
    }

    private int clearGame(CommandContext<CommandSourceStack> ctx) {
        Arena arena = editableArena(ctx);
        if (arena == null) return 0;
        String previous = arena.getGame();
        if (previous == null) return fail(ctx, arena.getId() + " has no game.");
        arenas.setGame(arena, null);
        games.sync(previous);
        return reply(ctx, "<green><white><id></white> has no game now.", Placeholder.unparsed("id", arena.getId()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> setting(String name) {
        return Commands.literal(name).executes(usage("set <arena> " + name + " <true|false>"))
                .then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> set(ctx, name)));
    }

    private int set(CommandContext<CommandSourceStack> ctx, String setting) {
        Arena arena = editableArena(ctx);
        if (arena == null) return 0;
        boolean value = BoolArgumentType.getBool(ctx, "value");
        String what = switch (setting) {
            case "break" -> {
                arena.setBreakAllowed(value);
                yield "break blocks inside the region";
            }
            case "place" -> {
                arena.setPlaceAllowed(value);
                yield "place blocks inside the region";
            }
            case "pickup" -> {
                arena.setPickupAllowed(value);
                yield "pick up items";
            }
            case "mobs" -> {
                arena.setMobSpawningAllowed(value);
                yield "MOBS";
            }
            case "environment" -> {
                arena.setEnvironmentAllowed(value);
                yield "ENVIRONMENT";
            }
            case "drop" -> {
                arena.setDropAllowed(value);
                yield "drop items";
            }
            default -> throw new IllegalArgumentException(setting);
        };
        arenas.save(arena);
        if (what.equals("ENVIRONMENT")) {
            reply(ctx, value ? "<green>Fire, snowfall, melting, plant growth and the like can now change <white>" + arena.getId() + "</white>."
                    : "<green><white>" + arena.getId() + "</white> no longer changes by itself (fire spread, snowfall, melting, growth, ...).");
        } else if (what.equals("MOBS")) {
            reply(ctx, value ? "<green>Mobs can now spawn in <white>" + arena.getId() + "</white>."
                    : "<green>Mobs can no longer spawn in <white>" + arena.getId() + "</white>. Any already there are removed after the next match.");
        } else {
            reply(ctx, "<green>Players in <white>" + arena.getId() + "</white>'s game " + (value ? "can now " : "can no longer ") + what + ".");
        }
        boolean changesBlocks = setting.equals("break") || setting.equals("place");
        if (value && changesBlocks && !arena.isSnapshotCurrent()) {
            reply(ctx, "<yellow>There's no up-to-date snapshot, so changes won't be undone after a match. Run /arena snapshot "
                    + arena.getId() + ".");
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Snapshot / restore
    // ------------------------------------------------------------------------------------------------------------

    private int snapshot(CommandContext<CommandSourceStack> ctx) {
        Arena arena = editableArena(ctx);
        if (arena == null) return 0;
        CommandSender sender = ctx.getSource().getSender();
        try {
            arenas.snapshot(arena).whenComplete((blocks, error) -> {
                if (error != null) {
                    sender.sendMessage(Utils.prefixed(Utils.mm("<red>Couldn't save the snapshot: <error>",
                            Placeholder.unparsed("error", String.valueOf(error.getMessage())))));
                } else {
                    sender.sendMessage(Utils.prefixed(Utils.mm("<green>Saved <count> blocks. <white><id></white> will be reset to this after every match.",
                            Placeholder.unparsed("count", String.valueOf(blocks)), Placeholder.unparsed("id", arena.getId()))));
                }
            });
        } catch (IllegalArgumentException e) {
            return fail(ctx, e.getMessage());
        }
        return reply(ctx, "<gray>Saving the blocks of <white><id></white>...", Placeholder.unparsed("id", arena.getId()));
    }

    private int restore(CommandContext<CommandSourceStack> ctx) {
        Arena arena = editableArena(ctx);
        if (arena == null) return 0;
        if (!arena.hasSnapshot()) return fail(ctx, arena.getId() + " has no snapshot. Save one with /arena snapshot " + arena.getId() + ".");
        if (!arena.isSnapshotCurrent()) return fail(ctx, "The snapshot doesn't match the region any more. Save a new one first.");
        CommandSender sender = ctx.getSource().getSender();
        arenas.restore(arena).whenComplete((changed, error) -> {
            if (error != null) {
                sender.sendMessage(Utils.prefixed(Utils.mm("<red>Couldn't restore: <error>",
                        Placeholder.unparsed("error", String.valueOf(error.getMessage())))));
            } else {
                sender.sendMessage(Utils.prefixed(Utils.mm("<green>Restored <white><id></white> (<count> <unit> changed).",
                        Placeholder.unparsed("id", arena.getId()), Placeholder.unparsed("count", String.valueOf(changed)),
                        Placeholder.unparsed("unit", Utils.plural(changed, "block", "blocks")))));
            }
        });
        return reply(ctx, "<gray>Restoring <white><id></white>...", Placeholder.unparsed("id", arena.getId()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Tp / show
    // ------------------------------------------------------------------------------------------------------------

    private int teleport(CommandContext<CommandSourceStack> ctx) {
        Arena arena = arena(ctx);
        Player player = arena == null ? null : player(ctx);
        if (player == null) return 0;
        World world = arena.getWorld();
        if (world == null) return fail(ctx, "World '" + arena.getWorldName() + "' isn't loaded.");
        Location target = arena.getLobby();
        if (target == null && !arena.getSpawns().isEmpty()) target = arena.getSpawns().getFirst();
        Region region = arena.getRegion();
        if (target == null && region != null) {
            int x = (region.minX() + region.maxX()) / 2, z = (region.minZ() + region.maxZ()) / 2;
            target = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5);
        }
        if (target == null) return fail(ctx, arena.getId() + " has no lobby, spawns or region to go to yet.");
        player.teleport(target);
        return reply(ctx, "<green>Teleported to <white><id></white>.", Placeholder.unparsed("id", arena.getId()));
    }

    /** Outlines the region (orange) and marks the lobby (green), spawns (flames) and points (white) for a while. */
    private int show(CommandContext<CommandSourceStack> ctx) {
        Arena arena = arena(ctx);
        Player player = arena == null ? null : playerInWorld(ctx, arena);
        if (player == null) return 0;
        World world = player.getWorld();
        Region region = arena.getRegion();
        int[] runs = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Utils.runTimer(() -> {
            if (!player.isOnline() || !player.getWorld().equals(world) || runs[0]++ >= SHOW_SECONDS * 2) {
                task[0].cancel();
                return;
            }
            if (region != null) outline(player, world, region, Color.ORANGE);
            mark(player, arena.getLobby(), Particle.HAPPY_VILLAGER);
            for (Location spawn : arena.getSpawns()) mark(player, spawn, Particle.FLAME);
            for (String name : arena.getPoints().keySet()) mark(player, arena.getPoint(name), Particle.END_ROD);
            for (Region area : arena.getAreas().values()) outline(player, world, area, Color.AQUA);
        }, 0L, 10L);
        return reply(ctx, "<gray>Showing <white><id></white> for <seconds> seconds: <gold>region</gold>, <aqua>areas</aqua>, <green>lobby</green>, <red>spawns</red>, <white>points</white>.",
                Placeholder.unparsed("id", arena.getId()), Placeholder.unparsed("seconds", String.valueOf(SHOW_SECONDS)));
    }

    private static void outline(Player player, World world, Region region, Color color) {
        double x1 = region.minX(), y1 = region.minY(), z1 = region.minZ();
        double x2 = region.maxX() + 1, y2 = region.maxY() + 1, z2 = region.maxZ() + 1;
        double perimeter = 4 * ((x2 - x1) + (y2 - y1) + (z2 - z1));
        double step = Math.max(1, perimeter / 1500); // keep it to ~1500 particles however big the region is
        ParticleBuilder dust = new ParticleBuilder(Particle.DUST).receivers(player).color(color, 1.2f).force(true);
        for (double x = x1; x <= x2; x += step) {
            for (double[] yz : new double[][]{{y1, z1}, {y1, z2}, {y2, z1}, {y2, z2}}) dust.location(world, x, yz[0], yz[1]).spawn();
        }
        for (double y = y1; y <= y2; y += step) {
            for (double[] xz : new double[][]{{x1, z1}, {x1, z2}, {x2, z1}, {x2, z2}}) dust.location(world, xz[0], y, xz[1]).spawn();
        }
        for (double z = z1; z <= z2; z += step) {
            for (double[] xy : new double[][]{{x1, y1}, {x1, y2}, {x2, y1}, {x2, y2}}) dust.location(world, xy[0], xy[1], z).spawn();
        }
    }

    private static void mark(Player player, @Nullable Location location, Particle particle) {
        if (location == null) return;
        new ParticleBuilder(particle).receivers(player).location(location.clone().add(0, 1, 0))
                .count(6).offset(0.15, 0.5, 0.15).speed(0).force(true).spawn();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Arguments and helpers
    // ------------------------------------------------------------------------------------------------------------

    private RequiredArgumentBuilder<CommandSourceStack, String> arenaArgument() {
        return Commands.argument("arena", StringArgumentType.word()).suggests((ctx, builder) -> {
            for (Arena arena : arenas.getArenas()) {
                if (arena.getId().startsWith(builder.getRemainingLowerCase())) builder.suggest(arena.getId());
            }
            return builder.buildFuture();
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> pointArgument(boolean existingOnly) {
        return Commands.argument("name", StringArgumentType.word()).suggests((ctx, builder) -> {
            Arena arena = arenas.getArena(StringArgumentType.getString(ctx, "arena"));
            if (arena != null) {
                for (String name : arena.getPoints().keySet()) {
                    if (name.startsWith(builder.getRemainingLowerCase())) builder.suggest(name);
                }
            }
            if (!existingOnly) {
                for (String name : requiredNames(arena, true)) {
                    if (name.startsWith(builder.getRemainingLowerCase())) builder.suggest(name);
                }
            }
            return builder.buildFuture();
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> areaArgument() {
        return Commands.argument("area", StringArgumentType.word()).suggests((ctx, builder) -> {
            Arena arena = arenas.getArena(StringArgumentType.getString(ctx, "arena"));
            if (arena != null) {
                Set<String> names = new TreeSet<>(arena.getAreas().keySet());
                names.addAll(requiredNames(arena, false));
                for (String name : names) {
                    if (name.startsWith(builder.getRemainingLowerCase())) builder.suggest(name);
                }
            }
            return builder.buildFuture();
        });
    }

    /** The point (or area) names the arena's game needs, for suggestions. */
    private List<String> requiredNames(@Nullable Arena arena, boolean points) {
        GameType type = arena == null || arena.getGame() == null ? null : games.getType(arena.getGame());
        if (type == null) return points ? List.of("center") : List.of();
        return points ? type.getRequiredPoints() : type.getRequiredAreas();
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> gameArgument() {
        return Commands.argument("game", StringArgumentType.word()).suggests((ctx, builder) -> {
            for (GameType type : games.getTypes()) {
                if (type.getId().startsWith(builder.getRemainingLowerCase())) builder.suggest(type.getId());
            }
            return builder.buildFuture();
        });
    }

    private @Nullable Arena arena(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "arena").toLowerCase();
        Arena arena = arenas.getArena(id);
        if (arena == null) fail(ctx, "There is no arena called '" + id + "'. See /arena list.");
        return arena;
    }

    /**
     * The arena, unless its match has players (queued or playing) or its blocks are being restored. Changing the
     * region, game or snapshot then would mix up the match.
     */
    private @Nullable Arena editableArena(CommandContext<CommandSourceStack> ctx) {
        Arena arena = arena(ctx);
        if (arena == null) return null;
        if (arena.isRestoring()) {
            fail(ctx, arena.getId() + " is being restored. Try again in a moment.");
            return null;
        }
        Minigame match = games.getMatch(arena);
        if (match != null && (match.getState() != GameState.WAITING || match.getPlayerCount() > 0)) {
            fail(ctx, arena.getId() + " has a " + match.getId() + " match going. Wait for it to end (or /game stop "
                    + match.getId() + " " + arena.getId() + ").");
            return null;
        }
        return arena;
    }

    private static @Nullable Player player(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player player) return player;
        fail(ctx, "Only players can do that; it uses where you are.");
        return null;
    }

    private static @Nullable Player playerInWorld(CommandContext<CommandSourceStack> ctx, Arena arena) {
        Player player = player(ctx);
        if (player != null && !player.getWorld().getName().equals(arena.getWorldName())) {
            fail(ctx, arena.getId() + " is in world '" + arena.getWorldName() + "'. Go there first.");
            return null;
        }
        return player;
    }

    /** Replies with how to use a subcommand. Used when its arguments are missing. */
    private static Command<CommandSourceStack> usage(String usage) {
        return ctx -> fail(ctx, "Usage: /arena " + usage);
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
