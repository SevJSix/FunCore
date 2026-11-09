package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.spawn.ServerSpawn;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** {@code /setspawn}: makes where you stand the server's spawn point (saved to config.yml). */
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
                .build();
    }

    private int setSpawn(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm("<red>Only players can set the spawn.")));
            return 0;
        }
        Location at = player.getLocation();
        spawn.set(at);
        player.sendMessage(Utils.prefixed(Utils.mm("<green>Server spawn set to <white><x>, <y>, <z></white> in <white><world></white>.",
                Placeholder.unparsed("x", String.format("%.1f", at.getX())),
                Placeholder.unparsed("y", String.format("%.1f", at.getY())),
                Placeholder.unparsed("z", String.format("%.1f", at.getZ())),
                Placeholder.unparsed("world", at.getWorld().getName()))));
        return Command.SINGLE_SUCCESS;
    }
}
