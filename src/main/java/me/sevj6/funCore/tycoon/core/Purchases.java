package me.sevj6.funCore.tycoon.core;

import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Template;

import java.util.Set;

/** Whether a component can be bought, in the order the checks are made. Bukkit-free, so it's unit-tested. */
public final class Purchases {

    public enum Result {
        /** Buyable: the price can be taken. */
        OK,
        UNKNOWN,
        ALREADY_OWNED,
        /** Something it requires isn't owned. */
        MISSING_PREREQUISITE,
        /** It needs more rebirths. */
        NEEDS_REBIRTHS,
        /** Everything's fine but the money. */
        TOO_POOR
    }

    private Purchases() {
    }

    /**
     * @param owned    what the plot owns now
     * @param balance  the player's tycoon balance, in cents
     */
    public static Result check(Template template, String id, Set<String> owned, int rebirths, long balance) {
        Component component = template.component(id);
        if (component == null) return Result.UNKNOWN;
        if (owned.contains(id)) return Result.ALREADY_OWNED;
        if (!owned.containsAll(component.common().requires())) return Result.MISSING_PREREQUISITE;
        if (rebirths < component.common().minRebirths()) return Result.NEEDS_REBIRTHS;
        if (balance < component.common().price()) return Result.TOO_POOR;
        return Result.OK;
    }
}
