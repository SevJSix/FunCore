package me.sevj6.funCore.npc.behavior;

import me.sevj6.funCore.music.MusicManager;
import me.sevj6.funCore.music.Song;
import me.sevj6.funCore.music.SongSession;
import me.sevj6.funCore.npc.Npc;
import me.sevj6.funCore.npc.event.NpcInteractEvent;
import me.sevj6.funCore.npc.event.NpcProximityLeaveEvent;
import me.sevj6.funCore.util.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code song}: the NPC plays a song ({@code .nbs}, see {@link MusicManager}).
 * <pre>
 * - type: song
 *   song: lobby_theme
 *   mode: click        # click: plays to whoever clicks (just them, once; clicking again starts it over)
 *                      # nearby: plays from the NPC, on a loop, while anyone is within its proximity range
 *   click: any         # click mode only: any | left | right
 * </pre>
 * From a command: {@code /npc behavior <id> add song lobby_theme} or {@code ... add song lobby_theme nearby}.
 */
public final class SongBehavior implements NpcBehavior {

    public static final String TYPE = "song";

    public enum Mode { CLICK, NEARBY }

    private final MusicManager music;
    private final String song;
    private final Mode mode;
    private final ClickFilter click;
    /** Click mode: what each player is hearing from this NPC. */
    private final Map<UUID, SongSession> playing = new HashMap<>();
    /** Nearby mode: the song playing from the NPC, while anyone is near. */
    private @Nullable SongSession nearby;

    public SongBehavior(MusicManager music, String song, Mode mode, ClickFilter click) {
        if (song.isBlank()) throw new IllegalArgumentException("a song behavior needs a song id");
        this.music = music;
        this.song = song.toLowerCase(Locale.ROOT);
        this.mode = mode;
        this.click = click;
    }

    public static BehaviorType definition(MusicManager music) {
        return new BehaviorType(TYPE, "<song> [click|nearby]", config -> {
            String mode = config.getString("mode", "click");
            return new SongBehavior(music, config.getString("song", ""), parseMode(mode),
                    ClickFilter.parse(config.getString("click", "any")));
        }, value -> {
            String[] parts = value.trim().split("\\s+");
            if (parts[0].isBlank() || parts.length > 2) throw new IllegalArgumentException("Give a song id and, optionally, click or nearby.");
            if (music.isAvailable() && music.getSong(parts[0]) == null) {
                throw new IllegalArgumentException("There's no song called '" + parts[0] + "'. See /music list.");
            }
            return new SongBehavior(music, parts[0], parts.length == 2 ? parseMode(parts[1]) : Mode.CLICK, ClickFilter.ANY);
        });
    }

    private static Mode parseMode(String mode) {
        try {
            return Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be click or nearby, not '" + mode + "'");
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String summary() {
        String problem = !music.isAvailable() ? " (NOTEBLOCKAPI NOT INSTALLED)" : music.getSong(song) == null ? " (NO SUCH SONG)" : "";
        return mode == Mode.NEARBY ? "plays '" + song + "' to players nearby, on a loop" + problem
                : click.configName() + " click plays '" + song + "'" + problem;
    }

    @Override
    public void save(ConfigurationSection config) {
        config.set("song", song);
        config.set("mode", mode.name().toLowerCase(Locale.ROOT));
        if (mode == Mode.CLICK) config.set("click", click.configName());
    }

    @Override
    public void onInteract(Npc npc, Player player, NpcInteractEvent.Click click) {
        if (mode != Mode.CLICK || !this.click.matches(click)) return;
        Song found = music.getSong(song);
        if (found == null) return;
        if (music.isMuted(player.getUniqueId())) {
            player.sendActionBar(Utils.mm("<gray>You have music turned off. <white>/music toggle</white> turns it on."));
            return;
        }
        SongSession previous = playing.remove(player.getUniqueId());
        if (previous != null) previous.stop();
        UUID uuid = player.getUniqueId();
        SongSession session = music.session("npc:" + npc.getId(), List.of(found));
        session.onFinish(() -> playing.remove(uuid, session)).start().addListener(player);
        playing.put(uuid, session);
    }

    @Override
    public void onProximityEnter(Npc npc, Player player) {
        if (mode != Mode.NEARBY) return;
        if (nearby == null || nearby.isStopped()) {
            Song found = music.getSong(song);
            if (found == null) return;
            nearby = music.session("npc:" + npc.getId(), List.of(found)).loop(true)
                    .from(npc.getLocation().add(0, 1.5, 0), npc.getProximityRange()).start();
        }
        nearby.addListener(player);
    }

    @Override
    public void onProximityLeave(Npc npc, Player player, NpcProximityLeaveEvent.Reason reason) {
        if (mode == Mode.CLICK) {
            // Their song keeps going if they just walk away; it's theirs. It stops if the NPC goes away.
            if (reason == NpcProximityLeaveEvent.Reason.NPC_DESPAWNED) {
                SongSession session = playing.remove(player.getUniqueId());
                if (session != null) session.fadeOut(1);
            }
            return;
        }
        if (nearby == null) return;
        nearby.removeListener(player.getUniqueId());
        if (nearby.getListeners().isEmpty()) {
            // Nobody left to hear it: it starts from the top for the next visitor.
            nearby.fadeOut(1);
            nearby = null;
        }
    }
}
