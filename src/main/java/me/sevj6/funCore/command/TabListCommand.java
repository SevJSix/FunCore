package me.sevj6.funCore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.tablist.TabListManager;
import me.sevj6.funCore.util.Utils;

/**
 * {@code /tablist reload}: reads {@code plugins/FunCore/tablist.yml} again and applies it to everyone straight away.
 */
public final class TabListCommand {

    public static final String PERMISSION = "funcore.command.tablist";

    private final TabListManager tabList;

    public TabListCommand(TabListManager tabList) {
        this.tabList = tabList;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("tablist")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::usage)
                .then(Commands.literal("reload").executes(this::reload))
                .build();
    }

    private int usage(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm(
                "<gray>Edit <white>plugins/FunCore/" + TabListManager.FILE + "</white>, then <white>/tablist reload</white>.")));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = tabList.reload().enabled();
        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm(enabled
                ? "<green>Tab list reloaded." + (tabList.hasPlaceholderApi() ? "" : " <gray>(PlaceholderAPI isn't installed, so %placeholders% stay as written.)")
                : "<yellow>Tab list reloaded; it's off (enabled: false), so Minecraft's own shows.")));
        return Command.SINGLE_SUCCESS;
    }
}
