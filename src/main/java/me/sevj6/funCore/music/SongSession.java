package me.sevj6.funCore.music;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Songs playing for some players: one song, or a playlist (in order or shuffled, once or looping).
 *
 * <p>Made with {@link MusicManager#session} and started with {@link #start()}. Two kinds:
 * <ul>
 *     <li><b>Soundtrack</b> (the default): its {@linkplain #addListener listeners} hear it wherever they are.</li>
 *     <li><b>From a point</b> ({@link #from}): the sound comes from a place, fading with distance. Either its
 *     listeners hear it, or with {@link #everyoneInRange()}, whoever is within range.</li>
 * </ul>
 *
 * <h2>Timing</h2>
 * Each server tick, the session works out where the song should be by the real clock and plays every note up to
 * there, all in one go. So the tempo doesn't drift when the server lags, every note lands on a server tick (the
 * steadiest rhythm the server can send), and after a long freeze it skips ahead instead of firing a burst of old
 * notes.
 */
public final class SongSession {

    /** Silence between songs in a playlist. */
    private static final long GAP_NANOS = 1_000_000_000L;
    /** Further behind than this (in seconds), missed notes are dropped instead of played late. */
    private static final double MAX_CATCH_UP_SECONDS = 0.25;
    /** Vanilla sounds carry 16 blocks at full volume. */
    private static final double SOUND_RANGE = 16;

    private final MusicManager music;
    private final String origin;
    private final List<Song> songs;
    private final List<Song> order = new ArrayList<>();
    private final Set<UUID> listeners = new LinkedHashSet<>();
    private boolean shuffle, loop;
    private float volume = 1f;
    private @Nullable Location source;
    private double range = SOUND_RANGE;
    private boolean everyoneInRange;
    private @Nullable Runnable onFinish;

    private boolean started, stopped;
    private int index;
    private long songStart;
    private int lastTick = -1;
    private long fadeStart = -1, fadeNanos;

    SongSession(MusicManager music, String origin, List<Song> songs) {
        if (songs.isEmpty()) throw new IllegalArgumentException("a session needs at least one song");
        this.music = music;
        this.origin = origin;
        this.songs = List.copyOf(songs);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Setting up (before start)
    // ------------------------------------------------------------------------------------------------------------

    /** Plays the songs in a random order (a new one each time round, with {@link #loop}). */
    public SongSession shuffle(boolean shuffle) {
        this.shuffle = shuffle;
        return this;
    }

    /** Starts over after the last song, forever (until {@link #stop()}). */
    public SongSession loop(boolean loop) {
        this.loop = loop;
        return this;
    }

    /** 0-1; multiplies every note's volume. */
    public SongSession volume(float volume) {
        this.volume = Math.clamp(volume, 0f, 1f);
        return this;
    }

    /** Makes the sound come from a place, heard up to {@code range} blocks away. */
    public SongSession from(Location source, double range) {
        this.source = source.clone();
        this.range = Math.max(1, range);
        return this;
    }

    /** With {@link #from}: everyone within range hears it, rather than just the listeners. */
    public SongSession everyoneInRange() {
        this.everyoneInRange = true;
        return this;
    }

    /** Runs once the session ends by itself (the last song finished; not on {@link #stop()}). */
    public SongSession onFinish(Runnable onFinish) {
        this.onFinish = onFinish;
        return this;
    }

    /** Starts playing. Does nothing if it already started. */
    public SongSession start() {
        if (started) return this;
        started = true;
        nextRound();
        songStart = System.nanoTime();
        music.register(this);
        return this;
    }

    // ------------------------------------------------------------------------------------------------------------
    // While it plays
    // ------------------------------------------------------------------------------------------------------------

    public void addListener(Player player) {
        listeners.add(player.getUniqueId());
    }

    public void addListeners(Collection<? extends Player> players) {
        for (Player player : players) addListener(player);
    }

    public void removeListener(UUID player) {
        listeners.remove(player);
    }

    public Set<UUID> getListeners() {
        return Collections.unmodifiableSet(listeners);
    }

    /** Who or what started it, e.g. {@code "command"}, {@code "game:bridge"}, {@code "npc:greeter"}. */
    public String getOrigin() {
        return origin;
    }

    public @Nullable Location getSource() {
        return source == null ? null : source.clone();
    }

    public double getRange() {
        return range;
    }

    /** The song playing now. */
    public Song getSong() {
        return order.get(Math.min(index, order.size() - 1));
    }

    public boolean isStopped() {
        return stopped;
    }

    /** Stops right away. */
    public void stop() {
        stopped = true;
    }

    /** Fades out over this many seconds, then stops. */
    public void fadeOut(double seconds) {
        if (stopped || fadeStart >= 0) return;
        if (seconds <= 0) {
            stop();
            return;
        }
        fadeStart = System.nanoTime();
        fadeNanos = (long) (seconds * 1_000_000_000L);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Playback (MusicManager calls tick every server tick)
    // ------------------------------------------------------------------------------------------------------------

    /** Plays what's due. Returns {@code false} once the session is over. */
    boolean tick(long now) {
        if (stopped) return false;
        float fade = 1f;
        if (fadeStart >= 0) {
            fade = 1f - (float) (now - fadeStart) / fadeNanos;
            if (fade <= 0) {
                stopped = true;
                return false;
            }
        }
        if (now < songStart) return true; // the gap between songs

        Song song = order.get(index);
        int due = (int) ((now - songStart) / 1_000_000_000.0 * song.ticksPerSecond());
        int maxBehind = Math.max(2, (int) Math.ceil(song.ticksPerSecond() * MAX_CATCH_UP_SECONDS));
        if (due - lastTick > maxBehind) lastTick = due - maxBehind;
        List<Song.Note> notes = new ArrayList<>();
        for (int tick = lastTick + 1; tick <= due && tick < song.length(); tick++) notes.addAll(song.ticks().get(tick));
        lastTick = Math.max(lastTick, due);
        if (!notes.isEmpty()) play(notes, volume * fade);

        if (lastTick >= song.length()) return nextSong(now);
        return true;
    }

    /** On to the next song; {@code false} when there isn't one. */
    private boolean nextSong(long now) {
        index++;
        if (index >= order.size()) {
            if (!loop) {
                stopped = true;
                if (onFinish != null) onFinish.run();
                return false;
            }
            nextRound();
        }
        lastTick = -1;
        songStart = now + (order.size() > 1 || loop ? GAP_NANOS : 0);
        return true;
    }

    /** The order for the next time through the songs. A shuffle never starts with the song that just played. */
    private void nextRound() {
        Song last = order.isEmpty() ? null : order.getLast();
        order.clear();
        order.addAll(songs);
        if (shuffle) {
            Collections.shuffle(order);
            if (order.size() > 1 && order.getFirst() == last) Collections.swap(order, 0, order.size() - 1);
        }
        index = 0;
    }

    private void play(List<Song.Note> notes, float volume) {
        NoteSender sender = music.sender();
        if (source == null) {
            for (UUID uuid : listeners) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && !music.isMuted(uuid)) sender.sendToSelf(player, notes, volume, music.category());
            }
            return;
        }
        // From a point: a volume over 1 makes the client hear it further away (16 blocks per 1), not louder.
        float carry = volume * (float) Math.max(1, range / SOUND_RANGE);
        double rangeSquared = range * range;
        Collection<? extends Player> hearing = everyoneInRange ? source.getWorld().getPlayers() : listenersOnline();
        for (Player player : hearing) {
            if (music.isMuted(player.getUniqueId()) || !player.getWorld().equals(source.getWorld())) continue;
            if (everyoneInRange && player.getLocation().distanceSquared(source) > rangeSquared) continue;
            sender.sendFrom(player, source, notes, carry, music.category());
        }
    }

    private List<Player> listenersOnline() {
        List<Player> players = new ArrayList<>(listeners.size());
        for (UUID uuid : listeners) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) players.add(player);
        }
        return players;
    }
}
