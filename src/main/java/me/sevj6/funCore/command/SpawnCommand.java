package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * {@code /spawn}: back to the server spawn from anywhere. In a minigame, it leaves the game first (getting back what
 * the player had before it, as {@code /game leave} does); in the tycoon, leaving the world ends the session as usual
 * (progress saved).
 */
public final class SpawnCommand {

    public static final String PERMISSION = "funcore.command.spawn";

    private final FunCore plugin;

    public SpawnCommand(FunCore plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("spawn")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::spawn)
                .build();
    }

    private int spawn(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm("<red>Only players can go to spawn.")));
            return 0;
        }
        Minigame game = plugin.getGameManager().getGame(player);
        if (game != null) {
            game.leave(player, LeaveReason.LEFT);
            // Leaving puts back where they were before the game; spawn comes after that.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) sendToSpawn(player);
            });
        } else {
            sendToSpawn(player);
        }
        return Command.SINGLE_SUCCESS;
    }

    private void sendToSpawn(Player player) {
        Location spawn = plugin.getServerSpawn().getLocation();
        if (spawn == null) spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
        player.teleport(spawn);
        player.sendMessage(Utils.prefixed(Utils.mm("<gray>Teleported to spawn.")));
    }
}
