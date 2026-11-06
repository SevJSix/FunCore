package me.sevj6.funCore.command.economy;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.command.argument.CashArgument;
import me.sevj6.funCore.economy.Account;
import me.sevj6.funCore.economy.Cash;
import me.sevj6.funCore.economy.Economy;
import me.sevj6.funCore.economy.TransactionResult;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import static me.sevj6.funCore.command.economy.EconomyCommandUtils.account;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.accountNames;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.describe;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.fail;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.reply;

/**
 * {@code /pay <player> <amount>}: sends cash to another player, online or offline. Offline players are told about it
 * the next time they join.
 */
public final class PayCommand {

    public static final String PERMISSION = "funcore.command.pay";

    private final Economy economy;

    public PayCommand(Economy economy) {
        this.economy = economy;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("pay")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(accountNames(economy))
                        .then(Commands.argument("amount", CashArgument.positive())
                                .executes(this::pay)))
                .build();
    }

    private int pay(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) return fail(ctx, "Only players can pay.");
        Account target = account(ctx, economy, StringArgumentType.getString(ctx, "player"));
        if (target == null) return 0;
        long amount = ctx.getArgument("amount", Long.class);

        economy.ensureAccount(player);
        TransactionResult result = economy.transfer(player.getUniqueId(), target.id(), amount, "pay");
        if (result == TransactionResult.INSUFFICIENT_FUNDS) {
            return fail(ctx, "You don't have enough cash. Your balance is " + Cash.format(economy.getBalance(player.getUniqueId())) + ".");
        }
        if (!result.isSuccess()) return fail(ctx, describe(result));

        Player receiver = Bukkit.getPlayer(target.id());
        if (receiver != null) {
            receiver.sendMessage(Utils.prefixed(Utils.mm("<white><player></white> <green>paid you <gold><amount></gold>.",
                    Placeholder.unparsed("player", player.getName()), Placeholder.unparsed("amount", Cash.format(amount)))));
        }
        return reply(ctx, "<green>You paid <white><player></white> <gold><amount></gold>.<offline>",
                Placeholder.unparsed("player", target.name()),
                Placeholder.unparsed("amount", Cash.format(amount)),
                Placeholder.parsed("offline", receiver == null ? " <gray>They're offline and will be told when they join." : ""));
    }
}
