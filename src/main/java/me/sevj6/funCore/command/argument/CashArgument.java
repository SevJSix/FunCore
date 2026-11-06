package me.sevj6.funCore.command.argument;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import me.sevj6.funCore.economy.Cash;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collection;
import java.util.List;

/**
 * A cash amount argument, e.g. {@code 5}, {@code 12.50}, {@code $1,000}. Gives the amount in cents. Typos show up as
 * a normal red command error. It takes the rest of the line, so it must be the last argument.
 */
public final class CashArgument implements CustomArgumentType.Converted<Long, String> {

    private final boolean allowZero;

    private CashArgument(boolean allowZero) {
        this.allowZero = allowZero;
    }

    /** An amount greater than zero. */
    public static CashArgument positive() {
        return new CashArgument(false);
    }

    /** An amount of zero or more (e.g. for setting a balance). */
    public static CashArgument nonNegative() {
        return new CashArgument(true);
    }

    @Override
    public Long convert(String input) throws CommandSyntaxException {
        long cents;
        try {
            cents = Cash.parse(input);
        } catch (IllegalArgumentException e) {
            throw error(e.getMessage());
        }
        if (cents == 0 && !allowZero) throw error("The amount must be more than $0.");
        return cents;
    }

    private static CommandSyntaxException error(String message) {
        return new SimpleCommandExceptionType(
                MessageComponentSerializer.message().serialize(Component.text(message, NamedTextColor.RED))).create();
    }

    @Override
    public ArgumentType<String> getNativeType() {
        return StringArgumentType.greedyString();
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("5", "12.50", "1,000");
    }
}
