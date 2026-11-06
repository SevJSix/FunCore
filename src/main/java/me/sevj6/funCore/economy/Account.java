package me.sevj6.funCore.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player's account. It's immutable: {@link Economy} swaps in a new copy on every change, so a snapshot of the
 * accounts can be saved off the main thread safely.
 *
 * @param name            the player's last known name (for offline lookups and /baltop)
 * @param balance         in cents
 * @param offlinePayments payments received while offline, shown (and cleared) the next time they join
 */
public record Account(UUID id, String name, long balance, List<OfflinePayment> offlinePayments) {

    /** A payment someone received while they were offline. */
    public record OfflinePayment(String fromName, long amount, long time) {
    }

    public Account {
        offlinePayments = List.copyOf(offlinePayments);
    }

    Account withName(String name) {
        return new Account(id, name, balance, offlinePayments);
    }

    Account withBalance(long balance) {
        return new Account(id, name, balance, offlinePayments);
    }

    Account withOfflinePayment(OfflinePayment payment) {
        List<OfflinePayment> payments = new ArrayList<>(offlinePayments);
        payments.add(payment);
        return new Account(id, name, balance, payments);
    }

    Account withoutOfflinePayments() {
        return new Account(id, name, balance, List.of());
    }
}
