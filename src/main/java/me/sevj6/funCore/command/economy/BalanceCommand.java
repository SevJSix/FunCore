package me.sevj6.funCore.command.economy;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.sevj6.funCore.economy.Account;
import me.sevj6.funCore.economy.Cash;
import me.sevj6.funCore.economy.Economy;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import static me.sevj6.funCore.command.economy.EconomyCommandUtils.account;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.accountNames;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.fail;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.reply;

/**
 * {@code /balance} (aliases {@code /bal}, {@code /money}): shows your own balance. Admins with
 * {@value #PERMISSION_OTHERS} can also use {@code /balance <player>}.
 */
public final class BalanceCommand {

    public static final String PERMISSION = "funcore.command.balance";
    public static final String PERMISSION_OTHERS = "funcore.command.balance.others";

    private final Economy economy;

    public BalanceCommand(Economy economy) {
        this.economy = economy;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("balance")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::own)
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(source -> source.getSender().hasPermission(PERMISSION_OTHERS))
                        .suggests(accountNames(economy))
                        .executes(this::other))
                .build();
    }

    private int own(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) return fail(ctx, "Use /balance <player> from the console.");
        return reply(ctx, "<gray>Your balance: <gold><balance>",
                Placeholder.unparsed("balance", Cash.format(economy.getBalance(player.getUniqueId()))));
    }

    private int other(CommandContext<CommandSourceStack> ctx) {
        Account account = account(ctx, economy, StringArgumentType.getString(ctx, "player"));
        if (account == null) return 0;
        return reply(ctx, "<gray><player>'s balance: <gold><balance>",
                Placeholder.unparsed("player", account.name()),
                Placeholder.unparsed("balance", Cash.format(account.balance())));
    }
}
