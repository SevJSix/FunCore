package me.sevj6.funCore.economy;

import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;

/** Creates accounts when players join, and tells them about payments they got while offline. */
public final class EconomyListener implements Listener {

    /** Above this many payments, show one summary line instead of one line each. */
    private static final int MAX_LISTED_PAYMENTS = 5;
    /** Wait a moment after joining so the message isn't buried under join messages. */
    private static final long NOTIFY_DELAY_TICKS = 40L;

    private final Economy economy;

    public EconomyListener(Economy economy) {
        this.economy = economy;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        economy.ensureAccount(player);
        Account account = economy.getAccount(player.getUniqueId());
        if (account == null || account.offlinePayments().isEmpty()) return;

        Utils.runLater(() -> {
            if (!player.isOnline()) return; // they stay pending and are shown next time
            notifyPayments(player, economy.takeOfflinePayments(player.getUniqueId()));
        }, NOTIFY_DELAY_TICKS);
    }

    private void notifyPayments(Player player, List<Account.OfflinePayment> payments) {
        if (payments.isEmpty()) return;
        if (payments.size() > MAX_LISTED_PAYMENTS) {
            long total = payments.stream().mapToLong(Account.OfflinePayment::amount).sum();
            player.sendMessage(Utils.prefixed(Utils.mm("<green>While you were offline, you received <white><count></white> payments totalling <gold><total></gold>.",
                    Placeholder.unparsed("count", String.valueOf(payments.size())),
                    Placeholder.unparsed("total", Cash.format(total)))));
        } else {
            player.sendMessage(Utils.prefixed(Utils.mm("<green>While you were offline:")));
            for (Account.OfflinePayment payment : payments) {
                player.sendMessage(Utils.mm("<gray> - <white><from></white> paid you <gold><amount></gold>",
                        Placeholder.unparsed("from", payment.fromName()),
                        Placeholder.unparsed("amount", Cash.format(payment.amount()))));
            }
        }
        player.sendMessage(Utils.prefixed(Utils.mm("<gray>Your balance is now <gold><balance></gold>.",
                Placeholder.unparsed("balance", Cash.format(economy.getBalance(player.getUniqueId()))))));
    }
}
