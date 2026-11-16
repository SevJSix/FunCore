package me.sevj6.funCore.tablist;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.game.GameTeam;
import me.sevj6.funCore.game.Minigame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The tab list: a header and footer for each player, and how each player's name shows in the list (e.g. with their
 * ping next to it). Everything is set in {@code plugins/FunCore/tablist.yml} and updates every {@code refresh-ticks};
 * {@code /tablist reload} reads the file again. Text is MiniMessage with FunCore's {@code {placeholders}}, plus
 * PlaceholderAPI's {@code %placeholders%} when that plugin is installed.
 * <p>
 * Only what changed is sent to players, so a refresh where nothing changed costs no packets.
 */
public final class TabListManager implements Listener {

    public static final String FILE = "tablist.yml";

    /** The settings in {@code tablist.yml}. */
    public record Settings(boolean enabled, int refreshTicks, List<String> header, List<String> footer, String name,
                    int pingGood, int pingOk) {
    }

    /** What was last sent to a player (or set as their name), so it's only sent again when it changes. */
    private static final class Sent {
        @Nullable Component header, footer, name;
    }

    private final FunCore plugin;
    private final Map<UUID, Sent> sent = new HashMap<>();
    private Settings settings;
    private @Nullable BukkitTask task;
    private boolean placeholderApi;

    public TabListManager(FunCore plugin) {
        this.plugin = plugin;
        this.settings = load();
    }

    public void start() {
        placeholderApi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        plugin.getSLF4JLogger().info(placeholderApi
                ? "Tab list: PlaceholderAPI found; %placeholders% work."
                : "Tab list: PlaceholderAPI isn't installed; only FunCore's {placeholders} work.");
        schedule();
    }

    /** Reads {@code tablist.yml} again and applies it straight away. */
    public Settings reload() {
        settings = load();
        placeholderApi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        clearAll();
        schedule();
        return settings;
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        clearAll();
    }

    public boolean hasPlaceholderApi() {
        return placeholderApi;
    }

    private void schedule() {
        if (task != null) task.cancel();
        task = null;
        if (!settings.enabled()) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 1L, settings.refreshTicks());
    }

    private Settings load() {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        try (Reader defaults = new InputStreamReader(Objects.requireNonNull(plugin.getResource(FILE)), StandardCharsets.UTF_8)) {
            config.setDefaults(YamlConfiguration.loadConfiguration(defaults));
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Tab list: couldn't read the default {}", FILE, e);
        }
        return new Settings(
                config.getBoolean("enabled", true),
                Math.clamp(config.getInt("refresh-ticks", 20), 1, 20 * 60),
                config.getStringList("header"),
                config.getStringList("footer"),
                config.getString("name", ""),
                config.getInt("ping.good", 100),
                config.getInt("ping.ok", 180));
    }

    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!settings.enabled()) return;
        // Next tick, once joining (spawn, a pending game state) has settled; then everyone sees the new count.
        Bukkit.getScheduler().runTask(plugin, this::refreshAll);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sent.remove(event.getPlayer().getUniqueId());
        if (settings.enabled()) Bukkit.getScheduler().runTask(plugin, this::refreshAll);
    }

    private void refreshAll() {
        if (!settings.enabled()) return;
        Map<String, Component> server = serverValues();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, Component> values = new HashMap<>(server);
            values.putAll(playerValues(player));
            UnaryOperator<String> papi = placeholderApi ? text -> PlaceholderHook.apply(player, text) : null;
            Sent last = sent.computeIfAbsent(player.getUniqueId(), id -> new Sent());

            Component header = TabTemplate.lines(settings.header(), values, papi);
            Component footer = TabTemplate.lines(settings.footer(), values, papi);
            if (!header.equals(last.header) || !footer.equals(last.footer)) {
                player.sendPlayerListHeaderAndFooter(header, footer);
                last.header = header;
                last.footer = footer;
            }
            if (!settings.name().isBlank()) {
                Component name = TabTemplate.render(settings.name(), values, papi);
                if (!name.equals(last.name)) {
                    player.playerListName(name);
                    last.name = name;
                }
            }
        }
    }

    /** Puts every player's tab list back to how Minecraft shows it. */
    private void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Sent last = sent.get(player.getUniqueId());
            if (last == null) continue;
            if (last.header != null) player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            if (last.name != null) player.playerListName(null);
        }
        sent.clear();
    }

    // ---- placeholders ----

    private Map<String, Component> serverValues() {
        double tps = Math.min(20.0, Bukkit.getServer().getTPS()[0]);
        NamedTextColor tpsColor = tps >= 18 ? NamedTextColor.GREEN : tps >= 15 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        String tpsText = String.format(Locale.ROOT, "%.1f", tps);
        return Map.of(
                "online", Component.text(Bukkit.getOnlinePlayers().size()),
                "max", Component.text(Bukkit.getMaxPlayers()),
                "tps", Component.text(tpsText),
                "tps_colored", Component.text(tpsText, tpsColor));
    }

    private Map<String, Component> playerValues(Player player) {
        int ping = player.getPing();
        Minigame game = plugin.getGameManager().getGame(player);
        GameTeam team = game == null ? null : game.getTeam(player);
        Component name = team == null ? player.name() : player.name().color(team.getColor());
        return Map.of(
                "player", name,
                "display_name", player.displayName(),
                "ping", Component.text(ping),
                "ping_colored", Component.text(ping + " ms", pingColor(ping, settings)),
                "world", Component.text(player.getWorld().getName()),
                "game", game == null ? Component.empty() : game.getDisplayName());
    }

    static NamedTextColor pingColor(int ping, Settings settings) {
        return ping < settings.pingGood() ? NamedTextColor.GREEN : ping < settings.pingOk() ? NamedTextColor.YELLOW : NamedTextColor.RED;
    }
}
