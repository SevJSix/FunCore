package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.npc.Npc;
import me.sevj6.funCore.npc.NpcManager;
import me.sevj6.funCore.npc.behavior.BehaviorType;
import me.sevj6.funCore.npc.behavior.NpcBehavior;
import me.sevj6.funCore.npc.skin.MojangSkinFetcher;
import me.sevj6.funCore.npc.skin.Skin;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * {@code /npc}: create, edit, save and load NPCs. Every edit is saved to the NPC's file right away.
 *
 * <pre>
 * /npc create &lt;id&gt; [name]           spawn an NPC where you stand (name is MiniMessage; defaults to the id)
 * /npc remove &lt;id&gt;
 * /npc list
 * /npc info &lt;id&gt;
 * /npc tp &lt;id&gt;                      teleport to an NPC
 * /npc movehere &lt;id&gt;                move an NPC to you (it faces your direction by default)
 * /npc edit &lt;id&gt; name &lt;name&gt;
 * /npc edit &lt;id&gt; nametag &lt;true|false&gt;
 * /npc edit &lt;id&gt; description [text]  no text removes it
 * /npc edit &lt;id&gt; skin [player|uuid]   no player resets to the default skin
 * /npc edit &lt;id&gt; lookatplayers &lt;true|false&gt;
 * /npc edit &lt;id&gt; lookrange &lt;blocks&gt;
 * /npc edit &lt;id&gt; proximityrange &lt;blocks&gt;
 * /npc behavior types               list the behaviour types and what each one expects
 * /npc behavior &lt;id&gt; list
 * /npc behavior &lt;id&gt; add &lt;type&gt; [value]   e.g. add message Hello, &lt;player&gt;!
 * /npc behavior &lt;id&gt; remove &lt;number&gt;
 * /npc behavior &lt;id&gt; clear
 * /npc save [id]                    write NPCs to their files
 * /npc reload [id]                  re-read NPCs from their files (picks up hand edits)
 * </pre>
 */
public final class NpcCommand {

    public static final String PERMISSION = "funcore.command.npc";
    private static final double MAX_RANGE = 64;

    private final NpcManager npcs;

    public NpcCommand(NpcManager npcs) {
        this.npcs = npcs;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("npc")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::help)
                .then(Commands.literal("create")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> create(ctx, null))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("remove")
                        .then(npcArgument().executes(this::remove)))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("info")
                        .then(npcArgument().executes(this::info)))
                .then(Commands.literal("tp")
                        .then(npcArgument().executes(this::teleportTo)))
                .then(Commands.literal("movehere")
                        .then(npcArgument().executes(this::moveHere)))
                .then(Commands.literal("edit")
                        .then(npcArgument()
                                .then(Commands.literal("name")
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> edit(ctx, "Name", (npc, sender) ->
                                                        npc.setName(StringArgumentType.getString(ctx, "name"))))))
                                .then(Commands.literal("nametag")
                                        .then(Commands.argument("visible", BoolArgumentType.bool())
                                                .executes(ctx -> edit(ctx, "Name tag visibility", (npc, sender) ->
                                                        npc.setNameVisible(BoolArgumentType.getBool(ctx, "visible"))))))
                                .then(Commands.literal("description")
                                        .executes(ctx -> edit(ctx, "Description", (npc, sender) -> npc.setDescription(null)))
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(ctx -> edit(ctx, "Description", (npc, sender) ->
                                                        npc.setDescription(StringArgumentType.getString(ctx, "text"))))))
                                .then(Commands.literal("skin")
                                        .executes(ctx -> edit(ctx, "Skin", (npc, sender) -> npc.setSkin(null)))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(this::setSkin)))
                                .then(Commands.literal("lookatplayers")
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> edit(ctx, "Look at players", (npc, sender) ->
                                                        npc.setLookAtPlayers(BoolArgumentType.getBool(ctx, "enabled"))))))
                                .then(Commands.literal("lookrange")
                                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0, MAX_RANGE))
                                                .executes(ctx -> edit(ctx, "Look range", (npc, sender) ->
                                                        npc.setLookRange(DoubleArgumentType.getDouble(ctx, "blocks"))))))
                                .then(Commands.literal("proximityrange")
                                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0, MAX_RANGE))
                                                .executes(ctx -> edit(ctx, "Proximity range", (npc, sender) ->
                                                        npc.setProximityRange(DoubleArgumentType.getDouble(ctx, "blocks"))))))))
                .then(behaviorCommand())
                .then(Commands.literal("save")
                        .executes(this::saveAll)
                        .then(npcArgument().executes(this::saveOne)))
                .then(Commands.literal("reload")
                        .executes(this::reloadAll)
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(npcIds())
                                .executes(this::reloadOne)))
                .build();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Subcommands
    // ------------------------------------------------------------------------------------------------------------

    private int help(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>NPC commands")));
        for (String line : new String[]{
                "create <id> [name]", "remove <id>", "list", "info <id>", "tp <id>", "movehere <id>",
                "edit <id> name <name>", "edit <id> nametag <true|false>", "edit <id> description [text]",
                "edit <id> skin [player|uuid]", "edit <id> lookatplayers <true|false>",
                "edit <id> lookrange <blocks>", "edit <id> proximityrange <blocks>", "behavior types",
                "behavior <id> list", "behavior <id> add <type> [value]", "behavior <id> remove <number>",
                "behavior <id> clear", "save [id]", "reload [id]"}) {
            sender.sendMessage(Utils.mm("<gray> /npc <usage>", Placeholder.unparsed("usage", line)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int create(CommandContext<CommandSourceStack> ctx, String name) {
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        Location location = ctx.getSource().getLocation();
        Npc npc;
        try {
            npc = npcs.create(id, location, name != null ? name : id, true);
        } catch (IllegalArgumentException e) {
            return fail(ctx, e.getMessage());
        }
        return reply(ctx, "<green>Created NPC <white><id></white> (<name><green>).",
                Placeholder.unparsed("id", npc.getId()), Placeholder.component("name", npc.getDisplayName()));
    }

    private int remove(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        if (!npcs.remove(id)) return fail(ctx, "There is no NPC with id '" + id + "'.");
        return reply(ctx, "<green>Removed NPC <white><id></white>.", Placeholder.unparsed("id", id));
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (npcs.getAll().isEmpty()) return reply(ctx, "<gray>There are no NPCs. Make one with /npc create <id>.");
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold><count> NPC(s):", Placeholder.unparsed("count", String.valueOf(npcs.getAll().size())))));
        for (Npc npc : npcs.getAll()) {
            Location loc = npc.getLocation();
            sender.sendMessage(Utils.mm("<gray> - <white><id></white> <name> <dark_gray>(<world> <x>, <y>, <z>)<status>",
                    Placeholder.unparsed("id", npc.getId()),
                    Placeholder.component("name", npc.getDisplayName()),
                    Placeholder.unparsed("world", loc.getWorld().getName()),
                    Placeholder.unparsed("x", String.valueOf(loc.getBlockX())),
                    Placeholder.unparsed("y", String.valueOf(loc.getBlockY())),
                    Placeholder.unparsed("z", String.valueOf(loc.getBlockZ())),
                    Placeholder.unparsed("status", (npc.isSpawned() ? "" : " not spawned") + (npc.isSaved() ? "" : " temporary"))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        Npc npc = npc(ctx);
        if (npc == null) return 0;
        CommandSender sender = ctx.getSource().getSender();
        Location loc = npc.getLocation();
        Skin skin = npc.getSkin();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>NPC <white><id>", Placeholder.unparsed("id", npc.getId()))));
        line(sender, "Name", npc.getDisplayName());
        line(sender, "Name tag", Component.text(npc.isNameVisible() ? "visible" : "hidden"));
        line(sender, "Description", npc.getDescription() == null ? Component.text("none") : Utils.mm(npc.getDescription()));
        line(sender, "Skin", Component.text(skin == null ? "default" : skin.ownerName() + " (" + skin.ownerId() + ")"));
        line(sender, "Location", Component.text("%s %.1f, %.1f, %.1f (yaw %.0f, pitch %.0f)".formatted(
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch())));
        line(sender, "Look at players", Component.text(npc.isLookAtPlayers() ? "yes, within " + npc.getLookRange() + " blocks" : "no"));
        line(sender, "Proximity range", Component.text(npc.getProximityRange() + " blocks"));
        line(sender, "Behaviors", Component.text(npc.getBehaviors().size() + " (see /npc behavior " + npc.getId() + " list)"));
        line(sender, "Status", Component.text((npc.isSpawned() ? "spawned" : "not spawned (chunk not loaded)")
                + ", " + (npc.isSaved() ? "saved" : "temporary") + ", " + npc.getNearbyPlayers().size() + " nearby"));
        return Command.SINGLE_SUCCESS;
    }

    private int teleportTo(CommandContext<CommandSourceStack> ctx) {
        Npc npc = npc(ctx);
        if (npc == null) return 0;
        if (!(ctx.getSource().getExecutor() instanceof Player player)) return fail(ctx, "Only players can do that.");
        player.teleport(npc.getLocation());
        return reply(ctx, "<green>Teleported to <white><id></white>.", Placeholder.unparsed("id", npc.getId()));
    }

    private int moveHere(CommandContext<CommandSourceStack> ctx) {
        return edit(ctx, "Location", (npc, sender) -> npc.setLocation(ctx.getSource().getLocation()));
    }

    private int setSkin(CommandContext<CommandSourceStack> ctx) {
        Npc npc = npc(ctx);
        if (npc == null) return 0;
        String id = npc.getId();
        String target = StringArgumentType.getString(ctx, "player");
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gray>Looking up the skin for <white><target></white>...", Placeholder.unparsed("target", target))));

        npcs.skinFetcher().fetch(target).whenComplete((skin, error) -> Utils.sync(() -> {
            if (error != null) {
                sender.sendMessage(Utils.prefixed(Utils.mm("<red><message>", Placeholder.unparsed("message", MojangSkinFetcher.describe(error)))));
                return;
            }
            // The NPC may have been removed or reloaded while we waited.
            Npc current = npcs.get(id);
            if (current == null) {
                sender.sendMessage(Utils.prefixed(Utils.mm("<red>NPC '<id>' was removed before the skin arrived.", Placeholder.unparsed("id", id))));
                return;
            }
            current.setSkin(skin);
            npcs.save(current);
            sender.sendMessage(Utils.prefixed(Utils.mm("<green>Gave <white><id></white> the skin of <white><owner></white>.",
                    Placeholder.unparsed("id", id), Placeholder.unparsed("owner", skin.ownerName()))));
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int saveAll(CommandContext<CommandSourceStack> ctx) {
        int failed = npcs.saveAll();
        return failed == 0
                ? reply(ctx, "<green>Saved every NPC.")
                : fail(ctx, failed + " NPC(s) failed to save. Check the console.");
    }

    private int saveOne(CommandContext<CommandSourceStack> ctx) {
        Npc npc = npc(ctx);
        if (npc == null) return 0;
        if (!npc.isSaved()) return fail(ctx, "'" + npc.getId() + "' is a temporary NPC and has no file.");
        return npcs.save(npc)
                ? reply(ctx, "<green>Saved <white><id></white>.", Placeholder.unparsed("id", npc.getId()))
                : fail(ctx, "Failed to save '" + npc.getId() + "'. Check the console.");
    }

    private int reloadAll(CommandContext<CommandSourceStack> ctx) {
        int loaded = npcs.loadAll();
        return reply(ctx, "<green>Reloaded <count> NPC(s) from disk. <gray>(Any skipped are listed in the console.)",
                Placeholder.unparsed("count", String.valueOf(loaded)));
    }

    private int reloadOne(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        try {
            npcs.load(id);
        } catch (IOException | IllegalArgumentException e) {
            return fail(ctx, e.getMessage());
        }
        return reply(ctx, "<green>Reloaded <white><id></white> from disk.", Placeholder.unparsed("id", id));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Behaviors
    // ------------------------------------------------------------------------------------------------------------

    private ArgumentBuilder<CommandSourceStack, ?> behaviorCommand() {
        return Commands.literal("behavior")
                .then(Commands.literal("types").executes(this::listBehaviorTypes))
                .then(npcArgument()
                        .then(Commands.literal("list").executes(this::listBehaviors))
                        .then(Commands.literal("add")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (BehaviorType type : npcs.behaviors().types()) {
                                                if (type.id().startsWith(builder.getRemainingLowerCase())) builder.suggest(type.id());
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> addBehavior(ctx, ""))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> addBehavior(ctx, StringArgumentType.getString(ctx, "value"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                        .executes(this::removeBehavior)))
                        .then(Commands.literal("clear").executes(this::clearBehaviors)));
    }

    private int listBehaviorTypes(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Behavior types")));
        for (BehaviorType type : npcs.behaviors().types()) {
            sender.sendMessage(Utils.mm("<gray> /npc behavior <id> add <white><type></white> <usage>",
                    Placeholder.unparsed("type", type.id()), Placeholder.unparsed("usage", type.usage())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int listBehaviors(CommandContext<CommandSourceStack> ctx) {
        Npc npc = npc(ctx);
        if (npc == null) return 0;
        if (npc.getBehaviors().isEmpty()) {
            return reply(ctx, "<gray><white><id></white> has no behaviors. Add one with <white>/npc behavior <id> add <type> [value]</white>.",
                    Placeholder.unparsed("id", npc.getId()));
        }
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Behaviors of <white><id></white> <gray>(run in this order)",
                Placeholder.unparsed("id", npc.getId()))));
        int number = 1;
        for (NpcBehavior behavior : npc.getBehaviors()) {
            sender.sendMessage(Utils.mm("<gray> <number>. <white><type></white> <dark_gray>-</dark_gray> <summary>",
                    Placeholder.unparsed("number", String.valueOf(number++)),
                    Placeholder.unparsed("type", behavior.type()),
                    Placeholder.unparsed("summary", behavior.summary())));
        }
        sender.sendMessage(Utils.mm("<dark_gray> More options (click type, modes, cooldowns) are in npcs/<id>.yml. Run /npc reload <id> after editing it.",
                Placeholder.unparsed("id", npc.getId())));
        return Command.SINGLE_SUCCESS;
    }

    private int addBehavior(CommandContext<CommandSourceStack> ctx, String value) {
        Npc npc = npc(ctx);
        if (npc == null) return 0;
        String typeId = StringArgumentType.getString(ctx, "type").toLowerCase(Locale.ROOT);
        BehaviorType type = npcs.behaviors().get(typeId);
        if (type == null) return fail(ctx, "There is no behavior type '" + typeId + "'. See /npc behavior types.");

        NpcBehavior behavior;
        try {
            behavior = type.fromCommand().create(value);
        } catch (IllegalArgumentException e) {
            return fail(ctx, e.getMessage());
        }
        npc.addBehavior(behavior);
        if (!npcs.save(npc)) return fail(ctx, "Behavior added, but saving failed. Check the console.");
        return reply(ctx, "<green>Added a <white><type></white> behavior to <white><id></white>. <gray>(#<number>: <summary>)",
                Placeholder.unparsed("type", type.id()), Placeholder.unparsed("id", npc.getId()),
                Placeholder.unparsed("number", String.valueOf(npc.getBehaviors().size())),
                Placeholder.unparsed("summary", behavior.summary()));
    }

    private int removeBehavior(CommandContext<CommandSourceStack> ctx) {
        Npc npc = npc(ctx);
        if (npc == null) return 0;
        int number = IntegerArgumentType.getInteger(ctx, "number");
        if (number > npc.getBehaviors().size()) {
            return fail(ctx, npc.getId() + " has " + npc.getBehaviors().size() + " behavior(s). See /npc behavior " + npc.getId() + " list.");
        }
        NpcBehavior removed = npc.removeBehavior(number - 1);
        if (!npcs.save(npc)) return fail(ctx, "Behavior removed, but saving failed. Check the console.");
        return reply(ctx, "<green>Removed behavior #<number> (<type>) from <white><id></white>.",
                Placeholder.unparsed("number", String.valueOf(number)), Placeholder.unparsed("type", removed.type()),
                Placeholder.unparsed("id", npc.getId()));
    }

    private int clearBehaviors(CommandContext<CommandSourceStack> ctx) {
        return edit(ctx, "Behaviors (all removed)", (npc, sender) -> npc.clearBehaviors());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    /** Runs a change on the NPC named by the {@code id} argument, saves it, and confirms. */
    private int edit(CommandContext<CommandSourceStack> ctx, String what, BiConsumer<Npc, CommandSender> change) {
        Npc npc = npc(ctx);
        if (npc == null) return 0;
        change.accept(npc, ctx.getSource().getSender());
        if (!npcs.save(npc)) return fail(ctx, what + " changed, but saving failed. Check the console.");
        return reply(ctx, "<green><what> updated for <white><id></white>.",
                Placeholder.unparsed("what", what), Placeholder.unparsed("id", npc.getId()));
    }

    private ArgumentBuilder<CommandSourceStack, ?> npcArgument() {
        return Commands.argument("id", StringArgumentType.word()).suggests(npcIds());
    }

    private SuggestionProvider<CommandSourceStack> npcIds() {
        return (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (Npc npc : npcs.getAll()) {
                if (npc.getId().startsWith(typed)) builder.suggest(npc.getId());
            }
            return builder.buildFuture();
        };
    }

    /** The NPC named by the {@code id} argument, or {@code null} (after telling the sender) if there isn't one. */
    private Npc npc(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        Npc npc = npcs.get(id);
        if (npc == null) fail(ctx, "There is no NPC with id '" + id + "'.");
        return npc;
    }

    private static void line(CommandSender sender, String label, Component value) {
        sender.sendMessage(Utils.mm("<gray> <label>: <white><value>", Placeholder.unparsed("label", label), Placeholder.component("value", value)));
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
