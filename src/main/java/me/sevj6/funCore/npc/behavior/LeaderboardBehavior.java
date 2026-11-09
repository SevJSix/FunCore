package me.sevj6.funCore.npc.behavior;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import me.sevj6.funCore.leaderboard.Leaderboard;
import me.sevj6.funCore.leaderboard.LeaderboardManager;
import me.sevj6.funCore.npc.Npc;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code leaderboard}: shows a {@link Leaderboard}'s best records floating above the NPC, and under them, to each viewer
 * who isn't on it, their own best.
 * <pre>
 * - type: leaderboard
 *   board: clutch
 *   size: 5          # how many records it shows
 *   height: 1.5      # gap, in blocks, between the top of the NPC's head and the bottom of the board (clears the name)
 * </pre>
 * From a command: {@code /npc behavior <id> add leaderboard clutch} (or {@code ... clutch 2} for a 2-block gap).
 */
public final class LeaderboardBehavior implements NpcBehavior, Listener {

    public static final String TYPE = "leaderboard";
    public static final int DEFAULT_SIZE = 5;
    public static final double DEFAULT_HEIGHT = 1.5;
    /** Height of one line of text, plus a little room, in blocks. */
    private static final double LINE = 0.3;

    private final Plugin plugin;
    private final LeaderboardManager leaderboards;
    private final String boardId;
    private final int size;
    private final double height;

    /** While the NPC is out: */
    private @Nullable Npc npc;
    private @Nullable Leaderboard board;
    private @Nullable TextDisplay display;
    /** Each viewer's own line, seen only by them. */
    private final Map<UUID, TextDisplay> personal = new HashMap<>();
    private final Runnable refresh = this::refresh;

    public LeaderboardBehavior(Plugin plugin, LeaderboardManager leaderboards, String boardId, int size, double height) {
        if (boardId.isBlank()) throw new IllegalArgumentException("a leaderboard behavior needs a board id");
        if (size < 1 || size > 20) throw new IllegalArgumentException("size must be between 1 and 20");
        if (height < 0 || height > 10) throw new IllegalArgumentException("height must be between 0 and 10");
        this.plugin = plugin;
        this.leaderboards = leaderboards;
        this.boardId = boardId.toLowerCase(Locale.ROOT);
        this.size = size;
        this.height = height;
    }

    public static BehaviorType definition(Plugin plugin, LeaderboardManager leaderboards) {
        return new BehaviorType(TYPE, "<board> [height]", config -> new LeaderboardBehavior(plugin, leaderboards,
                config.getString("board", ""), config.getInt("size", DEFAULT_SIZE), config.getDouble("height", DEFAULT_HEIGHT)),
                value -> {
                    String[] parts = value.trim().split("\\s+");
                    if (parts[0].isBlank() || parts.length > 2) throw new IllegalArgumentException("Give a leaderboard id and, optionally, a height.");
                    if (leaderboards.get(parts[0].toLowerCase(Locale.ROOT)) == null) {
                        List<String> ids = new ArrayList<>();
                        leaderboards.getAll().forEach(board -> ids.add(board.getId()));
                        throw new IllegalArgumentException("There's no leaderboard called '" + parts[0] + "'. There are: " + String.join(", ", ids));
                    }
                    double height = DEFAULT_HEIGHT;
                    if (parts.length == 2) {
                        try {
                            height = Double.parseDouble(parts[1]);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("The height is a number of blocks, like 1.5.");
                        }
                    }
                    return new LeaderboardBehavior(plugin, leaderboards, parts[0], DEFAULT_SIZE, height);
                });
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String summary() {
        return "shows the top " + size + " of leaderboard '" + boardId + "', " + height + " blocks above its head"
                + (leaderboards.get(boardId) == null ? " (NO SUCH LEADERBOARD)" : "");
    }

    @Override
    public void save(ConfigurationSection config) {
        config.set("board", boardId);
        config.set("size", size);
        config.set("height", height);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Showing it
    // ------------------------------------------------------------------------------------------------------------

    @Override
    public void onSpawn(Npc npc, Mannequin entity) {
        onDespawn(npc);
        Leaderboard board = leaderboards.get(boardId);
        if (board == null) return;
        this.npc = npc;
        this.board = board;
        board.addListener(refresh);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Players start tracking it as it spawns, which brings them their own line (see onTrack).
        display = entity.getWorld().spawn(boardLocation(entity), TextDisplay.class, text -> {
            style(text);
            text.text(boardText(board));
        });
    }

    @Override
    public void onDespawn(Npc npc) {
        HandlerList.unregisterAll(this);
        if (board != null) board.removeListener(refresh);
        if (display != null) display.remove();
        personal.values().forEach(TextDisplay::remove);
        personal.clear();
        display = null;
        board = null;
        this.npc = null;
    }

    @Override
    public void onMove(Npc npc, Mannequin entity) {
        if (display == null) return;
        display.teleport(boardLocation(entity));
        for (TextDisplay line : personal.values()) line.teleport(personalLocation(entity));
    }

    /** The bottom of the whole thing: the viewer's own line, {@link #height} above the NPC's head. */
    private Location personalLocation(Mannequin entity) {
        Location at = entity.getLocation();
        at.setYaw(0);
        at.setPitch(0);
        return at.add(0, entity.getHeight() + height, 0);
    }

    /** The records sit one line higher, so the viewer's own line fits underneath. */
    private Location boardLocation(Mannequin entity) {
        return personalLocation(entity).add(0, LINE, 0);
    }

    private static void style(TextDisplay text) {
        text.setPersistent(false); // put back from the NPC's config, never saved with the chunk
        text.setBillboard(Display.Billboard.CENTER);
        text.setAlignment(TextDisplay.TextAlignment.CENTER);
        text.setShadowed(true);
        text.setLineWidth(400);
    }

    private Component boardText(Leaderboard board) {
        List<Component> lines = new ArrayList<>();
        lines.add(Utils.mm(board.getTitle()));
        List<Leaderboard.Entry> top = board.top(size);
        if (top.isEmpty()) lines.add(Utils.mm("<gray>No records yet. Be the first!"));
        for (int i = 0; i < top.size(); i++) {
            Leaderboard.Entry entry = top.get(i);
            String colour = switch (i) {
                case 0 -> "<gold>";
                case 1 -> "<#c0c0c0>";
                case 2 -> "<#cd7f32>";
                default -> "<white>";
            };
            lines.add(Utils.mm(colour + "#<place> <white><name></white> <dark_gray>-</dark_gray> <gray><text>",
                    Placeholder.unparsed("place", String.valueOf(i + 1)), Placeholder.unparsed("name", entry.name()),
                    Placeholder.unparsed("text", entry.text())));
        }
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
    }

    /** The viewer's own line, or {@code null} if they're on the board already. */
    private @Nullable Component personalText(Leaderboard board, UUID player) {
        Leaderboard.Entry best = board.best(player);
        if (best == null) return Utils.mm("<gray>Your best: <white>none yet");
        int place = board.placeOf(best);
        if (place <= size) return null;
        return Utils.mm("<gray>Your best: <yellow>#<place></yellow> <dark_gray>-</dark_gray> <text>",
                Placeholder.unparsed("place", String.valueOf(place)), Placeholder.unparsed("text", best.text()));
    }

    /** Brings the board and every viewer's own line up to date. */
    private void refresh() {
        if (display == null || board == null) return;
        display.text(boardText(board));
        for (UUID uuid : List.copyOf(personal.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) showPersonal(player);
        }
    }

    /** Shows (or updates, or takes away) a viewer's own line. */
    private void showPersonal(Player player) {
        Mannequin entity = npc == null ? null : npc.getEntity();
        if (board == null || entity == null) return;
        Component text = personalText(board, player.getUniqueId());
        TextDisplay line = personal.get(player.getUniqueId());
        if (text == null) {
            hidePersonal(player.getUniqueId());
        } else if (line != null && line.isValid()) {
            line.text(text);
        } else {
            line = entity.getWorld().spawn(personalLocation(entity), TextDisplay.class, created -> {
                style(created);
                created.setVisibleByDefault(false);
                created.text(text);
            });
            player.showEntity(plugin, line);
            personal.put(player.getUniqueId(), line);
        }
    }

    private void hidePersonal(UUID player) {
        TextDisplay line = personal.remove(player);
        if (line != null) line.remove();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Viewers: whoever's client has the board loaded gets their own line
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler
    public void onTrack(PlayerTrackEntityEvent event) {
        if (display == null || !event.getEntity().equals(display)) return;
        Player player = event.getPlayer();
        // Not while the server is in the middle of sending entities to them.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && display != null && display.isValid()) showPersonal(player);
        });
    }

    @EventHandler
    public void onUntrack(PlayerUntrackEntityEvent event) {
        if (display == null || !event.getEntity().equals(display)) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> hidePersonal(uuid));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hidePersonal(event.getPlayer().getUniqueId());
    }
}
