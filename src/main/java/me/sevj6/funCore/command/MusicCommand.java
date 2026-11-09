package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.sevj6.funCore.music.MusicManager;
import me.sevj6.funCore.music.Song;
import me.sevj6.funCore.music.SongSession;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * {@code /music}: playing songs by hand, and turning music off for yourself.
 *
 * <pre>
 * /music toggle                      turn all plugin music off (or on) for yourself; remembered
 * /music list                        every song, with its length
 * /music play &lt;song&gt; [players]       a song only they hear, wherever they go (you by default)
 * /music playhere &lt;song&gt; [range]     a song coming from where you stand, for anyone within range (default 32)
 * /music stop [players]              stop songs started with /music for them, or for you and around you
 * /music stopall                     stop every song started with /music
 * /music reload                      read the songs folder again
 * </pre>
 * Everything but {@code toggle} needs {@link #PERMISSION}. Game music and NPC songs aren't touched by stop.
 */
public final class MusicCommand {

    public static final String PERMISSION = "funcore.command.music";
    public static final String PERMISSION_TOGGLE = "funcore.command.music.toggle";
    /** The origin of sessions started here (see {@link SongSession#getOrigin()}). */
    private static final String ORIGIN = "command";
    private static final int DEFAULT_RANGE = 32, MAX_RANGE = 256;

    private final MusicManager music;

    public MusicCommand(MusicManager music) {
        this.music = music;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("music")
                .requires(source -> source.getSender().hasPermission(PERMISSION) || source.getSender().hasPermission(PERMISSION_TOGGLE))
                .executes(this::help)
                .then(Commands.literal("toggle").requires(source -> source.getSender().hasPermission(PERMISSION_TOGGLE))
                        .executes(this::toggle))
                .then(Commands.literal("list").requires(MusicCommand::isAdmin).executes(this::list))
                .then(Commands.literal("play").requires(MusicCommand::isAdmin)
                        .then(songArgument().executes(ctx -> play(ctx, false))
                                .then(Commands.argument("players", ArgumentTypes.players()).executes(ctx -> play(ctx, true)))))
                .then(Commands.literal("playhere").requires(MusicCommand::isAdmin)
                        .then(songArgument().executes(ctx -> playHere(ctx, DEFAULT_RANGE))
                                .then(Commands.argument("range", IntegerArgumentType.integer(1, MAX_RANGE))
                                        .executes(ctx -> playHere(ctx, IntegerArgumentType.getInteger(ctx, "range"))))))
                .then(Commands.literal("stop").requires(MusicCommand::isAdmin).executes(this::stopNearby)
                        .then(Commands.argument("players", ArgumentTypes.players()).executes(this::stopFor)))
                .then(Commands.literal("stopall").requires(MusicCommand::isAdmin).executes(this::stopAll))
                .then(Commands.literal("reload").requires(MusicCommand::isAdmin).executes(this::reload))
                .build();
    }

    private static boolean isAdmin(CommandSourceStack source) {
        return source.getSender().hasPermission(PERMISSION);
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> songArgument() {
        return Commands.argument("song", StringArgumentType.word()).suggests((ctx, builder) -> {
            for (String id : music.getSongIds()) {
                if (id.startsWith(builder.getRemainingLowerCase())) builder.suggest(id);
            }
            return builder.buildFuture();
        });
    }

    // ------------------------------------------------------------------------------------------------------------

    private int help(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Music commands")));
        if (sender.hasPermission(PERMISSION_TOGGLE)) line(sender, "toggle", "turn music off or on for yourself");
        if (sender.hasPermission(PERMISSION)) {
            line(sender, "list", "every song");
            line(sender, "play <song> [players]", "a song only they hear (you by default)");
            line(sender, "playhere <song> [range]", "a song from where you stand");
            line(sender, "stop [players]", "stop /music songs for them, or around you");
            line(sender, "stopall", "stop every /music song");
            line(sender, "reload", "read the songs folder again");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void line(CommandSender sender, String usage, String what) {
        sender.sendMessage(Utils.mm("<gray> /music <usage> <dark_gray>- <what>", Placeholder.unparsed("usage", usage),
                Placeholder.unparsed("what", what)));
    }

    private int toggle(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) return fail(ctx, "Only players can do that.");
        boolean mute = !music.isMuted(player.getUniqueId());
        music.setMuted(player.getUniqueId(), mute);
        return reply(ctx, mute ? "<gray>Music is now <red>off</red> for you. <white>/music toggle</white> turns it back on."
                : "<gray>Music is now <green>on</green> for you.");
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        if (!music.isAvailable()) return unavailable(ctx);
        if (music.getSongs().isEmpty()) return reply(ctx, "<gray>No songs yet. Put .nbs files in plugins/FunCore/songs/ and run /music reload.");
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold><count> song(s)", Placeholder.unparsed("count", String.valueOf(music.getSongs().size())))));
        for (Song song : music.getSongs()) {
            sender.sendMessage(Utils.mm("<gray> <white><id></white> <dark_gray>-</dark_gray> <name> <dark_gray>(<time>)",
                    Placeholder.unparsed("id", song.id()), Placeholder.unparsed("name", song.displayName()),
                    Placeholder.unparsed("time", Utils.formatTime(song.durationSeconds()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int play(CommandContext<CommandSourceStack> ctx, boolean hasTargets) throws CommandSyntaxException {
        Song song = song(ctx);
        if (song == null) return 0;
        List<Player> targets = targets(ctx, hasTargets);
        if (targets == null) return fail(ctx, "Say who should hear it: /music play <song> <players>.");
        if (targets.isEmpty()) return fail(ctx, "No players matched.");
        // One /music song per player: a new one replaces the last.
        for (Player target : targets) leaveCommandSongs(target.getUniqueId());
        music.session(ORIGIN, List.of(song)).start().addListeners(targets);
        return reply(ctx, "<green>Playing <white><song></white> for <who>.", Placeholder.unparsed("song", song.displayName()),
                Placeholder.unparsed("who", targets.size() == 1 ? targets.getFirst().getName() : targets.size() + " players"));
    }

    private int playHere(CommandContext<CommandSourceStack> ctx, int range) {
        Song song = song(ctx);
        if (song == null) return 0;
        Location at = ctx.getSource().getLocation();
        music.session(ORIGIN, List.of(song)).from(at, range).everyoneInRange().start();
        return reply(ctx, "<green>Playing <white><song></white> here, for anyone within <range> blocks.",
                Placeholder.unparsed("song", song.displayName()), Placeholder.unparsed("range", String.valueOf(range)));
    }

    /** Stops /music songs the sender hears, and ones playing from around where they are. */
    private int stopNearby(CommandContext<CommandSourceStack> ctx) {
        Location at = ctx.getSource().getLocation();
        UUID self = ctx.getSource().getExecutor() instanceof Player player ? player.getUniqueId() : null;
        int[] stopped = {0};
        for (SongSession session : music.getSessions()) {
            if (!session.getOrigin().equals(ORIGIN) || session.isStopped()) continue;
            Location source = session.getSource();
            boolean near = source != null && source.getWorld().equals(at.getWorld())
                    && source.distanceSquared(at) <= session.getRange() * session.getRange();
            if (near || (self != null && session.getListeners().contains(self))) {
                session.fadeOut(1);
                stopped[0]++;
            }
        }
        return stopped[0] == 0 ? fail(ctx, "No /music songs are playing for you or around you.")
                : reply(ctx, "<green>Stopped <count> song(s).", Placeholder.unparsed("count", String.valueOf(stopped[0])));
    }

    private int stopFor(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Player> targets = targets(ctx, true);
        if (targets == null || targets.isEmpty()) return fail(ctx, "No players matched.");
        for (Player target : targets) leaveCommandSongs(target.getUniqueId());
        return reply(ctx, "<green>Stopped /music songs for <who>.",
                Placeholder.unparsed("who", targets.size() == 1 ? targets.getFirst().getName() : targets.size() + " players"));
    }

    private int stopAll(CommandContext<CommandSourceStack> ctx) {
        music.stop(session -> session.getOrigin().equals(ORIGIN), 1);
        return reply(ctx, "<green>Stopped every /music song.");
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        if (!music.isAvailable()) return unavailable(ctx);
        int count = music.reload();
        return reply(ctx, "<green>Loaded <count> song(s). <gray>(Problems are in the console.)", Placeholder.unparsed("count", String.valueOf(count)));
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Takes a player out of every /music soundtrack; one left with nobody stops. */
    private void leaveCommandSongs(UUID player) {
        for (SongSession session : music.getSessions()) {
            if (!session.getOrigin().equals(ORIGIN) || session.getSource() != null || !session.getListeners().contains(player)) continue;
            session.removeListener(player);
            if (session.getListeners().isEmpty()) session.stop();
        }
    }

    private @Nullable Song song(CommandContext<CommandSourceStack> ctx) {
        if (!music.isAvailable()) {
            unavailable(ctx);
            return null;
        }
        String id = StringArgumentType.getString(ctx, "song");
        Song song = music.getSong(id);
        if (song == null) fail(ctx, "There's no song called '" + id + "'. See /music list.");
        return song;
    }

    /** The players named, or the sender if none were named and they're a player; {@code null} if there's nobody. */
    private static @Nullable List<Player> targets(CommandContext<CommandSourceStack> ctx, boolean named) throws CommandSyntaxException {
        if (named) return ctx.getArgument("players", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        return ctx.getSource().getExecutor() instanceof Player player ? List.of(player) : null;
    }

    private static int unavailable(CommandContext<CommandSourceStack> ctx) {
        return fail(ctx, "Music needs the NoteBlockAPI plugin, which isn't installed.");
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
