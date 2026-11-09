package me.sevj6.funCore.leaderboard;

import me.sevj6.funCore.FunCore;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every {@link Leaderboard} on the server. Games define theirs when they're registered (see
 * {@link #define(Leaderboard.Definition)}); records are saved in {@code plugins/FunCore/leaderboards/}.
 */
public final class LeaderboardManager {

    private final FunCore plugin;
    private final File directory;
    private final Map<String, Leaderboard> boards = new TreeMap<>();

    public LeaderboardManager(FunCore plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "leaderboards");
        if (!directory.isDirectory() && !directory.mkdirs()) plugin.getSLF4JLogger().error("Could not create {}", directory);
    }

    /** Adds a leaderboard and loads its records. Defining one that exists returns the existing one. */
    public Leaderboard define(Leaderboard.Definition definition) {
        return boards.computeIfAbsent(definition.id(), id -> {
            Leaderboard board = new Leaderboard(definition, new File(directory, id + ".yml"), plugin.getSLF4JLogger());
            board.load();
            return board;
        });
    }

    public @Nullable Leaderboard get(String id) {
        return boards.get(id);
    }

    public Collection<Leaderboard> getAll() {
        return Collections.unmodifiableCollection(boards.values());
    }
}
