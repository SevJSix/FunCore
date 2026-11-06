package me.sevj6.funCore.command.economy;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.economy.Account;
import me.sevj6.funCore.economy.Cash;
import me.sevj6.funCore.economy.Economy;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

import static me.sevj6.funCore.command.economy.EconomyCommandUtils.fail;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.reply;

/** {@code /baltop [page]} (alias {@code /balancetop}): the richest players, 10 per page. */
public final class BalanceTopCommand {

    public static final String PERMISSION = "funcore.command.baltop";
    private static final int PAGE_SIZE = 10;

    private final Economy economy;

    public BalanceTopCommand(Economy economy) {
        this.economy = economy;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("baltop")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(ctx -> show(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, "page"))))
                .build();
    }

    private int show(CommandContext<CommandSourceStack> ctx, int page) {
        List<Account> top = economy.getTopAccounts();
        if (top.isEmpty()) return reply(ctx, "<gray>Nobody has an account yet.");
        int pages = (top.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page > pages) return fail(ctx, "There " + (pages == 1 ? "is only 1 page." : "are only " + pages + " pages."));

        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Top balances <gray>(page <page>/<pages>)",
                Placeholder.unparsed("page", String.valueOf(page)), Placeholder.unparsed("pages", String.valueOf(pages)))));
        int start = (page - 1) * PAGE_SIZE;
        for (int i = start; i < Math.min(start + PAGE_SIZE, top.size()); i++) {
            Account account = top.get(i);
            sender.sendMessage(Utils.mm("<gray><rank>. <white><player></white> <gold><balance>",
                    Placeholder.unparsed("rank", String.valueOf(i + 1)),
                    Placeholder.unparsed("player", account.name()),
                    Placeholder.unparsed("balance", Cash.format(account.balance()))));
        }

        if (ctx.getSource().getExecutor() instanceof Player player) {
            for (int i = 0; i < top.size(); i++) {
                if (top.get(i).id().equals(player.getUniqueId())) {
                    sender.sendMessage(Utils.mm("<gray>You are #<rank>.", Placeholder.unparsed("rank", String.valueOf(i + 1))));
                    break;
                }
            }
        }
        return 1;
    }
}
