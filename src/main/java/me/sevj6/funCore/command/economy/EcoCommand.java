package me.sevj6.funCore.command.economy;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static me.sevj6.funCore.command.economy.EconomyCommandUtils.account;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.accountNames;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.describe;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.fail;
import static me.sevj6.funCore.command.economy.EconomyCommandUtils.reply;

/**
 * {@code /eco}: admin tools.
 *
 * <pre>
 * /eco give &lt;player&gt; &lt;amount&gt;
 * /eco take &lt;player&gt; &lt;amount&gt;
 * /eco set &lt;player&gt; &lt;amount&gt;
 * /eco reset &lt;player&gt;            back to the starting balance
 * /eco resetall [confirm]          hard reset of every balance (backs up first; needs confirming within 30s)
 * </pre>
 */
public final class EcoCommand {

    public static final String PERMISSION = "funcore.command.eco";
    private static final long CONFIRM_MILLIS = 30_000;

    private final Economy economy;
    /** Who has asked for /eco resetall and until when they can confirm, keyed by sender name. */
    private final Map<String, Long> pendingResets = new HashMap<>();

    public EcoCommand(Economy economy) {
        this.economy = economy;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("eco")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::help)
                .then(Commands.literal("give").then(player().then(Commands.argument("amount", CashArgument.positive()).executes(this::give))))
                .then(Commands.literal("take").then(player().then(Commands.argument("amount", CashArgument.positive()).executes(this::take))))
                .then(Commands.literal("set").then(player().then(Commands.argument("amount", CashArgument.nonNegative()).executes(this::set))))
                .then(Commands.literal("reset").then(player().executes(this::reset)))
                .then(Commands.literal("resetall")
                        .executes(this::requestResetAll)
                        .then(Commands.literal("confirm").executes(this::confirmResetAll)))
                .build();
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> player() {
        return Commands.argument("player", StringArgumentType.word()).suggests(accountNames(economy));
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Utils.prefixed(Utils.mm("<gold>Economy admin commands")));
        for (String line : new String[]{"give <player> <amount>", "take <player> <amount>", "set <player> <amount>",
                "reset <player>", "resetall"}) {
            sender.sendMessage(Utils.mm("<gray> /eco <usage>", Placeholder.unparsed("usage", line)));
        }
        return 1;
    }

    private int give(CommandContext<CommandSourceStack> ctx) {
        Account account = target(ctx);
        if (account == null) return 0;
        long amount = ctx.getArgument("amount", Long.class);
        return finish(ctx, account, economy.deposit(account.id(), amount, reason(ctx, "give")),
                "Gave <gold>" + Cash.format(amount) + "</gold> to");
    }

    private int take(CommandContext<CommandSourceStack> ctx) {
        Account account = target(ctx);
        if (account == null) return 0;
        long amount = ctx.getArgument("amount", Long.class);
        TransactionResult result = economy.withdraw(account.id(), amount, reason(ctx, "take"));
        if (result == TransactionResult.INSUFFICIENT_FUNDS) {
            return fail(ctx, account.name() + " only has " + Cash.format(account.balance()) + ". Use /eco set to set an exact amount.");
        }
        return finish(ctx, account, result, "Took <gold>" + Cash.format(amount) + "</gold> from");
    }

    private int set(CommandContext<CommandSourceStack> ctx) {
        Account account = target(ctx);
        if (account == null) return 0;
        long amount = ctx.getArgument("amount", Long.class);
        return finish(ctx, account, economy.setBalance(account.id(), amount, reason(ctx, "set")), "Set the balance of");
    }

    private int reset(CommandContext<CommandSourceStack> ctx) {
        Account account = target(ctx);
        if (account == null) return 0;
        return finish(ctx, account, economy.setBalance(account.id(), economy.getStartingBalance(), reason(ctx, "reset")), "Reset the balance of");
    }

    private int requestResetAll(CommandContext<CommandSourceStack> ctx) {
        pendingResets.put(ctx.getSource().getSender().getName(), System.currentTimeMillis() + CONFIRM_MILLIS);
        return reply(ctx, "<red><bold>This will reset ALL <count> balances to <start>.</bold></red> <gray>A backup is saved first. "
                        + "Type <white>/eco resetall confirm</white> within 30 seconds to go ahead.",
                Placeholder.unparsed("count", String.valueOf(economy.getAccounts().size())),
                Placeholder.unparsed("start", Cash.format(economy.getStartingBalance())));
    }

    private int confirmResetAll(CommandContext<CommandSourceStack> ctx) {
        Long expires = pendingResets.remove(ctx.getSource().getSender().getName());
        if (expires == null || expires < System.currentTimeMillis()) {
            return fail(ctx, "Nothing to confirm. Run /eco resetall first.");
        }
        File backup;
        try {
            backup = economy.resetAll(reason(ctx, "reset all"));
        } catch (IOException e) {
            return fail(ctx, "The backup failed, so nothing was reset: " + e.getMessage());
        }
        return reply(ctx, "<green>Every balance was reset to <gold><start></gold>. <gray>Backup: economy/<file>",
                Placeholder.unparsed("start", Cash.format(economy.getStartingBalance())),
                Placeholder.unparsed("file", backup.getName()));
    }

    // ------------------------------------------------------------------------------------------------------------

    private Account target(CommandContext<CommandSourceStack> ctx) {
        return account(ctx, economy, StringArgumentType.getString(ctx, "player"));
    }

    private int finish(CommandContext<CommandSourceStack> ctx, Account account, TransactionResult result, String action) {
        if (!result.isSuccess()) return fail(ctx, describe(result));
        return reply(ctx, "<green>" + action + " <white><player></white>. <gray>New balance: <gold><balance>",
                Placeholder.unparsed("player", account.name()),
                Placeholder.unparsed("balance", Cash.format(economy.getBalance(account.id()))));
    }

    /** Log reason that records which admin did it, e.g. "admin give by Notch". */
    private static String reason(CommandContext<CommandSourceStack> ctx, String action) {
        CommandSender sender = ctx.getSource().getSender();
        return "admin " + action + " by " + (sender instanceof Player ? sender.getName() : "console");
    }
}
