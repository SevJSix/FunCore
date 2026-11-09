package me.sevj6.funCore.music;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.music.nbs.NbsLoader;
import me.sevj6.funCore.music.nms.NmsNoteSender;
import me.sevj6.funCore.util.Utils;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Songs from {@code .nbs} files, and everything playing them.
 *
 * <ul>
 *     <li>Songs are the {@code .nbs} files in {@code plugins/FunCore/songs/}, named after the file
 *     ({@code Lobby Theme.nbs} is {@code lobby_theme}). {@code /music reload} picks up new ones.</li>
 *     <li>Reading them needs the NoteBlockAPI plugin. Without it, {@link #isAvailable()} is false and nothing plays
 *     (games and NPCs just stay quiet).</li>
 *     <li>Playing is done here, not by NoteBlockAPI: one task each server tick plays every {@link SongSession}'s due
 *     notes (see {@link SongSession} for why).</li>
 *     <li>Players can turn all of it off for themselves ({@code /music toggle}); that's saved in {@code music.yml}.</li>
 * </ul>
 *
 * Songs play in the sound category from config.yml ({@code music.category}, Jukebox/Note Blocks by default), so
 * players can turn them down with that volume slider.
 */
public final class MusicManager implements Listener {

    private final FunCore plugin;
    private final File songsFolder;
    private final File mutesFile;
    private final Map<String, Song> songs = new TreeMap<>();
    private final List<SongSession> sessions = new ArrayList<>();
    /** Sessions started while {@link #tick} runs (from an onFinish, say), added once it's done. */
    private final List<SongSession> starting = new ArrayList<>();
    private boolean ticking;
    private final Set<UUID> muted = new HashSet<>();
    private final NoteSender sender = new NmsNoteSender();
    private boolean available;
    private SoundCategory category = SoundCategory.RECORDS;
    private @Nullable BukkitTask task;

    public MusicManager(FunCore plugin) {
        this.plugin = plugin;
        this.songsFolder = new File(plugin.getDataFolder(), "songs");
        this.mutesFile = new File(plugin.getDataFolder(), "music.yml");
    }

    public void enable() {
        String configured = plugin.getConfig().getString("music.category", "records");
        try {
            category = SoundCategory.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getSLF4JLogger().warn("config.yml: music.category '{}' isn't a sound category; using records", configured);
        }
        loadMutes();
        if (!songsFolder.isDirectory() && !songsFolder.mkdirs()) {
            plugin.getSLF4JLogger().warn("Couldn't create {}", songsFolder);
        }
        available = plugin.getServer().getPluginManager().isPluginEnabled("NoteBlockAPI");
        if (!available) {
            plugin.getSLF4JLogger().info("NoteBlockAPI isn't installed; music is off.");
            return;
        }
        reload();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        sessions.forEach(SongSession::stop);
        sessions.clear();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Songs
    // ------------------------------------------------------------------------------------------------------------

    /** Whether songs can play (NoteBlockAPI is installed). */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Reads every {@code .nbs} file in the songs folder again. Songs already playing carry on with the version they
     * started with. Returns how many songs loaded; files that fail are logged and skipped.
     */
    public int reload() {
        if (!available) return 0;
        songs.clear();
        File[] files = songsFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".nbs"));
        if (files == null) return 0;
        for (File file : files) {
            String id = idOf(file.getName());
            try {
                songs.put(id, NbsLoader.load(file, id));
            } catch (IOException | RuntimeException e) {
                plugin.getSLF4JLogger().warn("Couldn't read song {}: {}", file.getName(), e.getMessage());
            }
        }
        plugin.getSLF4JLogger().info("Loaded {} song(s) from {}", songs.size(), songsFolder.getPath());
        return songs.size();
    }

    /** A file name as a song id: no {@code .nbs}, lower case, and only letters, digits, {@code _} and {@code -}. */
    static String idOf(String fileName) {
        String base = fileName.substring(0, fileName.length() - ".nbs".length()).toLowerCase(Locale.ROOT);
        return base.replaceAll("[^a-z0-9_-]+", "_");
    }

    public @Nullable Song getSong(String id) {
        return songs.get(id.toLowerCase(Locale.ROOT));
    }

    /** Every song, by id. */
    public Collection<Song> getSongs() {
        return Collections.unmodifiableCollection(songs.values());
    }

    public Set<String> getSongIds() {
        return Collections.unmodifiableSet(songs.keySet());
    }

    /** The songs with these ids, skipping any that don't exist. */
    public List<Song> getSongs(Collection<String> ids) {
        List<Song> found = new ArrayList<>();
        for (String id : ids) {
            Song song = getSong(id);
            if (song != null) found.add(song);
        }
        return found;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Playing
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A new session for these songs; set it up, add listeners and {@link SongSession#start() start} it. Nothing plays
     * if music isn't {@link #isAvailable() available}, but the session still works (it just stays silent).
     *
     * @param origin who's playing it, so it can be stopped by that later, e.g. {@code "game:bridge"}
     */
    public SongSession session(String origin, List<Song> songs) {
        return new SongSession(this, origin, songs);
    }

    void register(SongSession session) {
        if (!available) session.stop();
        else if (ticking) starting.add(session);
        else sessions.add(session);
    }

    /** Every session playing now. */
    public List<SongSession> getSessions() {
        return Collections.unmodifiableList(sessions);
    }

    /** Stops (with a short fade) every session that matches. */
    public void stop(Predicate<SongSession> which, double fadeSeconds) {
        for (SongSession session : sessions) {
            if (which.test(session)) session.fadeOut(fadeSeconds);
        }
    }

    private void tick() {
        if (sessions.isEmpty()) return;
        long now = System.nanoTime();
        ticking = true;
        try {
            sessions.removeIf(session -> {
                try {
                    return !session.tick(now);
                } catch (RuntimeException e) {
                    plugin.getSLF4JLogger().warn("A song stopped because of an error", e);
                    session.stop();
                    return true;
                }
            });
        } finally {
            ticking = false;
            sessions.addAll(starting);
            starting.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        for (SongSession session : sessions) session.removeListener(uuid);
    }

    NoteSender sender() {
        return sender;
    }

    SoundCategory category() {
        return category;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Muting
    // ------------------------------------------------------------------------------------------------------------

    public boolean isMuted(UUID player) {
        return muted.contains(player);
    }

    /** Turns all plugin music off (or back on) for a player. Saved right away. */
    public void setMuted(UUID player, boolean mute) {
        if (mute ? muted.add(player) : muted.remove(player)) saveMutes();
    }

    private void loadMutes() {
        muted.clear();
        if (!mutesFile.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(mutesFile);
        for (String id : yaml.getStringList("muted")) {
            try {
                muted.add(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                plugin.getSLF4JLogger().warn("music.yml: ignoring '{}', which isn't a player UUID", id);
            }
        }
    }

    private void saveMutes() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Players who turned music off with /music toggle."));
        yaml.set("muted", muted.stream().map(UUID::toString).sorted().toList());
        try {
            File temp = new File(mutesFile.getPath() + ".tmp");
            yaml.save(temp);
            Utils.moveReplacing(temp, mutesFile);
        } catch (IOException e) {
            plugin.getSLF4JLogger().warn("Couldn't save music.yml: {}", e.getMessage());
        }
    }
}
