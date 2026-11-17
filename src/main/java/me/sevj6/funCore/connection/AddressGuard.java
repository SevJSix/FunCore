package me.sevj6.funCore.connection;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Only the server's own address works (config.yml, {@code connections.allowed-addresses}): to anything else, e.g. the
 * raw IP that server scanners sweep through, the server doesn't answer in the server list (so it looks like nothing is
 * there) and nobody can join. Read when the server starts.
 *
 * <p>The address a client used is whatever it says it is, so this keeps out scanners and people who only have the IP,
 * not someone who knows the address; that's the whitelist's job.
 */
public final class AddressGuard implements Listener {

    /** How often refused server-list pings are summed up in the console. */
    private static final long PING_REPORT_TICKS = 20L * 60 * 60;
    /** A refused login from the same IP is logged at most this often. */
    private static final long LOGIN_LOG_MILLIS = 60_000;

    private final FunCore plugin;
    private final AddressRules rules;
    private final Component wrongAddressMessage;
    private final AtomicInteger refusedPings = new AtomicInteger();
    private volatile @Nullable String lastRefusedPingHost;
    private final Map<InetAddress, Long> lastLoggedLogin = new ConcurrentHashMap<>();

    public AddressGuard(FunCore plugin) {
        this.plugin = plugin;
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("connections");
        List<String> allowed = config == null ? List.of() : config.getStringList("allowed-addresses");
        this.rules = new AddressRules(allowed);
        this.wrongAddressMessage = Utils.mm(config == null ? "<red>Connect using the server's address."
                : config.getString("wrong-address-message", "<red>Connect using the server's address."));
    }

    public void start() {
        if (!rules.isActive()) {
            plugin.getSLF4JLogger().info("Connections: any address can be used to reach the server (connections.allowed-addresses is empty).");
            return;
        }
        plugin.getSLF4JLogger().info("Connections: only {} can be used to reach the server; other addresses get no answer.",
                String.join(", ", rules.getAllowed()));
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int count = refusedPings.getAndSet(0);
            if (count > 0) {
                plugin.getSLF4JLogger().info("Connections: ignored {} server-list {} to other addresses in the last hour (e.g. '{}').",
                        count, count == 1 ? "ping" : "pings", lastRefusedPingHost);
            }
            lastLoggedLogin.clear();
        }, PING_REPORT_TICKS, PING_REPORT_TICKS);
    }

    /** No answer at all to a server-list ping on another address: to a scanner it's as if nothing is there. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPing(PaperServerListPingEvent event) {
        InetSocketAddress from = event.getClient().getAddress();
        InetSocketAddress host = event.getClient().getVirtualHost();
        String hostname = host == null ? null : host.getHostString();
        if (rules.allows(from == null ? null : from.getAddress(), hostname)) return;
        event.setCancelled(true);
        refusedPings.incrementAndGet();
        lastRefusedPingHost = hostname == null ? "unknown" : hostname;
    }

    /** Someone joining through another address is turned away before they're in. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String hostname = event.getHostname();
        if (rules.allows(event.getAddress(), hostname.isBlank() ? null : hostname)) return;
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, wrongAddressMessage);
        long now = System.currentTimeMillis();
        Long last = lastLoggedLogin.put(event.getAddress(), now);
        if (last == null || now - last > LOGIN_LOG_MILLIS) {
            plugin.getSLF4JLogger().info("Connections: refused {} ({}): they used '{}', not an allowed address.",
                    event.getName(), event.getAddress().getHostAddress(), AddressRules.normalize(hostname));
        }
    }
}
