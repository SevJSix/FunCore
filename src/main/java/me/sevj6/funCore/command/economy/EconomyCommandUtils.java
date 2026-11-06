package me.sevj6.funCore.command.economy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.sevj6.funCore.economy.Account;
import me.sevj6.funCore.economy.Economy;
import me.sevj6.funCore.economy.TransactionResult;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Small helpers shared by the economy commands. */
final class EconomyCommandUtils {

    private EconomyCommandUtils() {
    }

    /** Suggests the names of everyone who has an account. */
    static SuggestionProvider<CommandSourceStack> accountNames(Economy economy) {
        return (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (Account account : economy.getAccounts()) {
                if (account.name().toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(account.name());
            }
            return builder.buildFuture();
        };
    }

    /** Finds an account by name, telling the sender if there isn't one. */
    static @Nullable Account account(CommandContext<CommandSourceStack> ctx, Economy economy, String name) {
        Account account = economy.findAccount(name);
        if (account == null) fail(ctx, "No player named '" + name + "' has played on this server.");
        return account;
    }

    /** A player-facing explanation of a failed transaction. */
    static String describe(TransactionResult result) {
        return switch (result) {
            case SUCCESS -> "Done.";
            case INVALID_AMOUNT -> "That amount isn't allowed.";
            case NO_ACCOUNT -> "That player has no account yet.";
            case INSUFFICIENT_FUNDS -> "There isn't enough cash for that.";
            case BALANCE_LIMIT -> "That would put the balance over the maximum.";
            case SAME_ACCOUNT -> "You can't pay yourself.";
        };
    }

    static int reply(CommandContext<CommandSourceStack> ctx, String miniMessage, TagResolver... resolvers) {
        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm(miniMessage, resolvers)));
        return Command.SINGLE_SUCCESS;
    }

    static int fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().getSender().sendMessage(Utils.prefixed(Utils.mm("<red><message>", Placeholder.unparsed("message", message))));
        return 0;
    }
}
