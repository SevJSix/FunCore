package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.game.GameManager;
import me.sevj6.funCore.game.GameState;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.game.JoinResult;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.game.GameSettings;
import me.sevj6.funCore.game.PlayMode;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/**
 * {@code /game}: play minigames.
 *
 * <pre>
 * /game list                        every game, its modes and its matches (one per arena)
 * /game join &lt;game&gt; [mode]          join a match; mode: singleplayer, ffa, duos, trios or squads
 * /game leave
 *
 * Admin:
 * /game start &lt;game&gt; [arena]        start a match now, skipping the countdown (the fullest one by default)
 * /game stop &lt;game&gt; [arena]         stop a match and send its players back (all of the game's by default)
 * /game config &lt;game&gt;               show the game's settings
 * /game config &lt;game&gt; mode &lt;mode&gt; &lt;true|false&gt;
 * /game config &lt;game&gt; dynamic &lt;true|false&gt;      FFA starts with 1 player; others join during the countdown
 * /game config &lt;game&gt; latejoin &lt;true|false&gt;     players can join running matches
 * /game config &lt;game&gt; pvp &lt;ffa|teams&gt; &lt;true|false&gt;
 * /game config &lt;game&gt; minplayers|maxplayers &lt;number&gt;
 * /game config &lt;game&gt; countdown &lt;seconds&gt;
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
                .then(Commands.literal("join").executes(usage("join <game> [mode]"))
                        .then(gameArgument().executes(ctx -> join(ctx, false))
                                .then(modeArgument().executes(ctx -> join(ctx, true)))))
                .then(Commands.literal("leave").executes(this::leave))
                .then(Commands.literal("start").requires(source -> source.getSender().hasPermission(PERMISSION_ADMIN))
                        .executes(usage("start <game> [arena]"))
                        .then(gameArgument().executes(ctx -> start(ctx, false))
                                .then(arenaArgument().executes(ctx -> start(ctx, true)))))
                .then(Commands.literal("stop").requires(source -> source.getSender().hasPermission(PERMISSION_ADMIN))
                        .executes(usage("stop <game> [arena]"))
                        .then(gameArgument().executes(ctx -> stop(ctx, false))
                                .then(arenaArgument().executes(ctx -> stop(ctx, true)))))
                .then(Commands.literal("config").requires(source -> source.getSender().hasPermission(PERMISSION_ADMIN))
                        .executes(usage("config <game> [setting] [value]"))
                        .then(gameArgument().executes(this::showConfig)
                                .then(Commands.literal("mode").executes(usage("config <game> mode <mode> <true|false>"))
                                        .then(modeArgument().then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(this::configMode))))
                                .then(booleanSetting("dynamic", GameSettings::setDynamic))
                                .then(booleanSetting("latejoin", GameSettings::setLateJoin))
                                .then(Commands.literal("pvp").executes(usage("config <game> pvp <ffa|teams> <true|false>"))
                                        .then(Commands.literal("ffa").then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> configure(ctx, settings -> settings.setPvpFfa(BoolArgumentType.getBool(ctx, "value"))))))
                                        .then(Commands.literal("teams").then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(ctx -> configure(ctx, settings -> settings.setPvpTeams(BoolArgumentType.getBool(ctx, "value")))))))
                                .then(numberSetting("minplayers", 2, 1000, GameSettings::setMinPlayers))
                                .then(numberSetting("maxplayers", 2, 1000, GameSettings::setMaxPlayers))
                                .then(numberSetting("countdown", 0, GameSettings.MAX_COUNTDOWN_SECONDS, GameSettings::setCountdownSeconds))))
                .build();
    }

    // ------------------------------------------------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------------------------------------------------

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (games.getTypes().isEmpty()) return reply(ctx, "<gray>There are no games right now.");
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Games <gray>(join with /game join <game> [mode])")));
        for (GameType type : games.getTypes()) {
            GameSettings settings = games.getSettings(type);
            sender.sendMessage(Utils.mm("<gray> - <name> <dark_gray>(<id>)</dark_gray> <white><modes></white><dark_gray><extra>",
                    Placeholder.component("name", type.getDisplayName()),
                    Placeholder.unparsed("id", type.getId()),
                    Placeholder.unparsed("modes", String.join(", ", settings.getEnabledModes().stream().map(PlayMode::id).toList())),
                    Placeholder.unparsed("extra", describeExtras(settings))));
            List<Minigame> matches = games.getMatches(type.getId());
            if (matches.isEmpty()) {
                sender.sendMessage(Utils.mm("<dark_gray>     no arenas yet"));
                continue;
            }
            for (Minigame match : matches) {
                sender.sendMessage(Utils.mm("<dark_gray>     <arena>: <state> <dark_gray><count>/<max><mode>",
                        Placeholder.unparsed("arena", arenaName(match)),
                        Placeholder.parsed("state", describe(match)),
                        Placeholder.unparsed("count", String.valueOf(match.getPlayerCount())),
                        Placeholder.unparsed("max", String.valueOf(match.getMaxPlayers())),
                        Placeholder.component("mode", settings.getEnabledModes().size() > 1 && match.getPlayerCount() > 0
                                ? Component.text(" " + match.getMode().id()) : Component.empty())));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    /** ", 2-16 players, dynamic, late join" */
    private static String describeExtras(GameSettings settings) {
        StringBuilder extras = new StringBuilder();
        if (settings.getEnabledModes().stream().anyMatch(PlayMode::isMultiplayer)) {
            extras.append(", ").append(settings.getMinPlayers()).append("-").append(settings.getMaxPlayers()).append(" players");
        }
        if (settings.isDynamic()) extras.append(", dynamic");
        if (settings.isLateJoin()) extras.append(", late join");
        return extras.toString();
    }

    private static String describe(Minigame match) {
        if (!match.isAvailable()) return "<red>world not loaded</red>";
        if (match.isArenaRestoring() && match.getState() == GameState.WAITING && match.getPlayerCount() == 0) {
            return "<yellow>resetting arena</yellow>";
        }
        return switch (match.getState()) {
            case WAITING -> match.getPlayerCount() == 0 ? "<green>open</green>" : "<green>waiting, needs " + match.getMinPlayers() + "</green>";
            case STARTING -> "<yellow>starting in " + match.getCountdownRemaining() + "s</yellow>";
            case RUNNING -> match.acceptsLateJoin() && !match.isFull() ? "<yellow>in progress, open to join</yellow>" : "<red>in progress</red>";
            case ENDING -> "<red>finishing</red>";
        };
    }

    private static String arenaName(Minigame match) {
        return match.getArena() == null ? "anywhere" : match.getArena().getId();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Join / leave
    // ------------------------------------------------------------------------------------------------------------

    private int join(CommandContext<CommandSourceStack> ctx, boolean withMode) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) return fail(ctx, "Only players can join games.");
        GameType type = type(ctx);
        if (type == null) return 0;
        PlayMode mode = null;
        if (withMode) {
            mode = mode(ctx);
            if (mode == null) return 0;
            if (!games.getSettings(type).isEnabled(mode)) {
                return fail(ctx, type.getId() + " can't be played in " + mode.id() + ". Its modes: "
                        + String.join(", ", games.getSettings(type).getEnabledModes().stream().map(PlayMode::id).toList()) + ".");
            }
        }
        JoinResult result = games.join(player, type, mode);
        if (!result.isSuccess()) {
            player.sendMessage(Utils.prefixed(result.message()));
            return 0;
        }
        return Command.SINGLE_SUCCESS; // the match announces the join itself
    }

    private int leave(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) return fail(ctx, "Only players can leave games.");
        Minigame game = games.getGame(player);
        if (game == null) return fail(ctx, "You're not in a game.");
        game.leave(player, LeaveReason.LEFT);
        return reply(ctx, "<gray>You left <name>. Your items and location are back.", Placeholder.component("name", game.getDisplayName()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Admin: start / stop
    // ------------------------------------------------------------------------------------------------------------

    private int start(CommandContext<CommandSourceStack> ctx, boolean withArena) {
        GameType type = type(ctx);
        if (type == null) return 0;
        Minigame match;
        if (withArena) {
            match = match(ctx, type);
            if (match == null) return 0;
        } else {
            // The match with the most players waiting.
            match = null;
            for (Minigame candidate : games.getMatches(type.getId())) {
                if (candidate.getState().isJoinable() && candidate.getPlayerCount() > 0
                        && (match == null || candidate.getPlayerCount() > match.getPlayerCount())) {
                    match = candidate;
                }
            }
            if (match == null) return fail(ctx, "Nobody is waiting in a " + type.getId() + " match.");
        }
        if (!match.getState().isJoinable()) return fail(ctx, "That match is already running.");
        if (match.getPlayerCount() == 0) return fail(ctx, "Nobody has joined that match yet.");
        if (match.isArenaRestoring()) return fail(ctx, "The arena is still being reset. Try again in a moment.");
        match.start();
        return reply(ctx, "<green>Started <name> in <arena>.", Placeholder.component("name", type.getDisplayName()),
                Placeholder.unparsed("arena", arenaName(match)));
    }

    private int stop(CommandContext<CommandSourceStack> ctx, boolean withArena) {
        GameType type = type(ctx);
        if (type == null) return 0;
        List<Minigame> targets = new ArrayList<>();
        if (withArena) {
            Minigame match = match(ctx, type);
            if (match == null) return 0;
            targets.add(match);
        } else {
            targets.addAll(games.getMatches(type.getId()));
        }
        int stopped = 0;
        for (Minigame match : targets) {
            if (match.getState() == GameState.WAITING && match.getPlayerCount() == 0) continue;
            match.forceStop();
            stopped++;
        }
        if (stopped == 0) return fail(ctx, withArena ? "That match isn't running." : "No " + type.getId() + " match is running.");
        return reply(ctx, "<green>Stopped <count> <unit> of <name>. Everyone in <them> was sent back.",
                Placeholder.unparsed("count", String.valueOf(stopped)),
                Placeholder.unparsed("unit", Utils.plural(stopped, "match", "matches")),
                Placeholder.component("name", type.getDisplayName()),
                Placeholder.unparsed("them", stopped == 1 ? "it" : "them"));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Admin: config
    // ------------------------------------------------------------------------------------------------------------

    private int showConfig(CommandContext<CommandSourceStack> ctx) {
        GameType type = type(ctx);
        if (type == null) return 0;
        GameSettings settings = games.getSettings(type);
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Settings of <name> <dark_gray>(games/<id>.yml)",
                Placeholder.component("name", type.getDisplayName()), Placeholder.unparsed("id", type.getId()))));
        List<String> modes = new ArrayList<>();
        for (PlayMode mode : type.getSupportedModes()) {
            modes.add((settings.isEnabled(mode) ? "<green>" : "<dark_gray><st>") + mode.id() + "<reset>");
        }
        line(sender, "Modes", String.join("<gray>, ", modes) + " <dark_gray>(crossed out = disabled)");
        line(sender, "Dynamic", !type.supportsDynamic() ? "<dark_gray>not supported" : onOff(settings.isDynamic())
                + " <dark_gray>(FFA starts with 1 player)");
        line(sender, "Late join", !type.supportsLateJoin() ? "<dark_gray>not supported" : onOff(settings.isLateJoin())
                + " <dark_gray>(join running matches)");
        line(sender, "PvP", "ffa " + onOff(settings.isPvpFfa()) + "<white>, teams " + onOff(settings.isPvpTeams())
                + " <dark_gray>(never between teammates)");
        line(sender, "Players", settings.getMinPlayers() + "-" + settings.getMaxPlayers()
                + " <dark_gray>(multiplayer; teams wait for 2 teams)");
        line(sender, "Countdown", settings.getCountdownSeconds() + "s");
        return Command.SINGLE_SUCCESS;
    }

    private static String onOff(boolean on) {
        return on ? "<green>on</green>" : "<red>off</red>";
    }

    private static void line(CommandSender sender, String label, String miniMessageValue) {
        sender.sendMessage(Utils.mm("<gray>  <label>: <white><value>", Placeholder.unparsed("label", label),
                Placeholder.parsed("value", miniMessageValue)));
    }

    private int configMode(CommandContext<CommandSourceStack> ctx) {
        PlayMode mode = mode(ctx);
        if (mode == null) return 0;
        boolean on = BoolArgumentType.getBool(ctx, "value");
        return configure(ctx, settings -> settings.setEnabled(mode, on));
    }

    private LiteralArgumentBuilder<CommandSourceStack> booleanSetting(
            String name, BiConsumer<GameSettings, Boolean> setter) {
        return Commands.literal(name).executes(usage("config <game> " + name + " <true|false>"))
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> configure(ctx, settings -> setter.accept(settings, BoolArgumentType.getBool(ctx, "value")))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> numberSetting(
            String name, int min, int max, ObjIntConsumer<GameSettings> setter) {
        return Commands.literal(name).executes(usage("config <game> " + name + " <number>"))
                .then(Commands.argument("value", IntegerArgumentType.integer(min, max))
                        .executes(ctx -> configure(ctx, settings -> setter.accept(settings, IntegerArgumentType.getInteger(ctx, "value")))));
    }

    /** Applies a change to the game's settings, saves them and shows the result. */
    private int configure(CommandContext<CommandSourceStack> ctx, Consumer<GameSettings> change) {
        GameType type = type(ctx);
        if (type == null) return 0;
        try {
            change.accept(games.getSettings(type));
        } catch (IllegalArgumentException e) {
            return fail(ctx, e.getMessage());
        }
        if (!games.saveSettings(type)) return fail(ctx, "Changed, but couldn't save the file. See the console.");
        reply(ctx, "<green>Saved. It applies to new and waiting matches.");
        return showConfig(ctx);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Arguments and helpers
    // ------------------------------------------------------------------------------------------------------------

    private RequiredArgumentBuilder<CommandSourceStack, String> gameArgument() {
        return Commands.argument("game", StringArgumentType.word()).suggests((ctx, builder) -> {
            for (GameType type : games.getTypes()) {
                if (type.getId().startsWith(builder.getRemainingLowerCase())) builder.suggest(type.getId());
            }
            return builder.buildFuture();
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> modeArgument() {
        return Commands.argument("mode", StringArgumentType.word()).suggests((ctx, builder) -> {
            GameType type = games.getType(StringArgumentType.getString(ctx, "game").toLowerCase());
            if (type != null) {
                // Joining offers the enabled modes; configuring offers every mode the game supports.
                boolean configuring = ctx.getNodes().stream().anyMatch(node -> node.getNode().getName().equals("config"));
                List<PlayMode> modes = configuring ? type.getSupportedModes() : games.getSettings(type).getEnabledModes();
                for (PlayMode mode : modes) {
                    if (mode.id().startsWith(builder.getRemainingLowerCase())) builder.suggest(mode.id());
                }
            }
            return builder.buildFuture();
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> arenaArgument() {
        return Commands.argument("arena", StringArgumentType.word()).suggests((ctx, builder) -> {
            for (Minigame match : games.getMatches(StringArgumentType.getString(ctx, "game").toLowerCase())) {
                if (match.getArena() != null && match.getArena().getId().startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(match.getArena().getId());
                }
            }
            return builder.buildFuture();
        });
    }

    private @Nullable GameType type(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "game").toLowerCase();
        GameType type = games.getType(id);
        if (type == null) fail(ctx, "There is no game called '" + id + "'. See /game list.");
        return type;
    }

    private static @Nullable PlayMode mode(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "mode");
        PlayMode mode = PlayMode.byId(id);
        if (mode == null) fail(ctx, "There is no mode called '" + id + "'. Modes: singleplayer, ffa, duos, trios, squads.");
        return mode;
    }

    /** The game's match in the arena named by the {@code arena} argument. */
    private @Nullable Minigame match(CommandContext<CommandSourceStack> ctx, GameType type) {
        String arena = StringArgumentType.getString(ctx, "arena").toLowerCase();
        for (Minigame match : games.getMatches(type.getId())) {
            if (match.getArena() != null && match.getArena().getId().equals(arena)) return match;
        }
        fail(ctx, type.getId() + " isn't played in an arena called '" + arena + "'.");
        return null;
    }

    private static Command<CommandSourceStack> usage(String usage) {
        return ctx -> fail(ctx, "Usage: /game " + usage);
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
