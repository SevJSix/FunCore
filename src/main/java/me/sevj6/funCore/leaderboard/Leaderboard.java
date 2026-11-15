package me.sevj6.funCore.leaderboard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Records kept forever (until an admin clears them), best first, e.g. Clutch's best completions. Every player keeps
 * their best {@link Definition#perPlayer()} records, so the board can't be filled by one person, and everyone's own best
 * can still be looked up when they're not near the top.
 * <p>
 * A record's score is a list of numbers where lower is better, compared in order: Clutch uses
 * {@code [deaths, milliseconds]}, so the fewest deaths win and the fastest time breaks ties. Its {@code text} is what the
 * board shows after the name, e.g. {@code "0 deaths, 1:30.452"}. Saved in {@code plugins/FunCore/leaderboards/<id>.yml}
 * with the {@link Definition#version() version} of its scores.
 */
public final class Leaderboard {

    /** Turns a score saved by an older version of a board into the current kind. */
    @FunctionalInterface
    public interface ScoreUpgrade {
        List<Long> upgrade(int savedVersion, List<Long> score);
    }

    /**
     * What a leaderboard is.
     *
     * @param id        lowercase, also the file name
     * @param title     MiniMessage, shown at the top of the board
     * @param perPlayer how many of each player's records are kept
     * @param text      how a record's score is shown, or {@code null} to show the text it was saved with. With one, the
     *                  text of every record, old ones too, is made from its score when the board loads, so a change to
     *                  how records look applies to all of them.
     * @param version   what kind of scores the board keeps (from 1); raised when their meaning changes
     * @param upgrade   turns scores saved with an older {@code version} into the current kind, once: the board is then
     *                  saved with the new version, and the old file kept as {@code <id>.yml.v<old version>.bak}
     */
    public record Definition(String id, String title, int perPlayer, @Nullable Function<List<Long>, String> text,
                             int version, @Nullable ScoreUpgrade upgrade) {
        public Definition {
            if (!id.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid leaderboard id: " + id);
            if (perPlayer < 1) throw new IllegalArgumentException("perPlayer must be at least 1");
            if (version < 1) throw new IllegalArgumentException("version must be at least 1");
        }

        /** A board whose records show the text they were saved with. */
        public Definition(String id, String title, int perPlayer) {
            this(id, title, perPlayer, null);
        }

        /** A board whose scores have always meant the same thing. */
        public Definition(String id, String title, int perPlayer, @Nullable Function<List<Long>, String> text) {
            this(id, title, perPlayer, text, 1, null);
        }
    }

    /**
     * One record.
     *
     * @param score      lower is better, compared number by number
     * @param text       what the board shows after the name
     * @param achievedAt when, in epoch milliseconds (an earlier record wins an exact tie)
     */
    public record Entry(UUID player, String name, List<Long> score, String text, long achievedAt) {
        public Entry {
            score = List.copyOf(score);
        }
    }

    /** Best first. */
    public static final Comparator<Entry> ORDER = ((Comparator<Entry>) (a, b) -> {
        for (int i = 0; i < Math.min(a.score().size(), b.score().size()); i++) {
            int compared = Long.compare(a.score().get(i), b.score().get(i));
            if (compared != 0) return compared;
        }
        return Integer.compare(a.score().size(), b.score().size());
    }).thenComparingLong(Entry::achievedAt);

    private final Definition definition;
    private final File file;
    private final Logger logger;
    /** Every record kept, best first. */
    private final List<Entry> entries = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    Leaderboard(Definition definition, File file, Logger logger) {
        this.definition = definition;
        this.file = file;
        this.logger = logger;
    }

    public Definition getDefinition() {
        return definition;
    }

    public String getId() {
        return definition.id();
    }

    public String getTitle() {
        return definition.title();
    }

    /** The best {@code count} records (fewer if there aren't that many). */
    public List<Entry> top(int count) {
        return List.copyOf(entries.subList(0, Math.min(count, entries.size())));
    }

    /** Every record kept, best first. */
    public List<Entry> getEntries() {
        return List.copyOf(entries);
    }

    /** A player's best record, or {@code null} if they have none. */
    public @Nullable Entry best(UUID player) {
        for (Entry entry : entries) {
            if (entry.player().equals(player)) return entry;
        }
        return null;
    }

    /** A record's place on the board, from 1; 0 if it isn't on it. */
    public int placeOf(Entry entry) {
        return entries.indexOf(entry) + 1;
    }

    /**
     * Adds a record. It's kept if it's one of the player's best {@link Definition#perPlayer()}; the board is then saved
     * and anything showing it is refreshed.
     *
     * @return whether it was kept
     */
    public boolean submit(Entry entry) {
        List<Entry> theirs = new ArrayList<>();
        for (Entry existing : entries) {
            if (existing.player().equals(entry.player())) theirs.add(existing);
        }
        theirs.add(entry);
        theirs.sort(ORDER);
        if (theirs.indexOf(entry) >= definition.perPlayer()) return false;

        entries.add(entry);
        // Their worst records beyond the limit go; their name is brought up to date on every one.
        if (theirs.size() > definition.perPlayer()) {
            entries.removeAll(theirs.subList(definition.perPlayer(), theirs.size()));
        }
        entries.replaceAll(existing -> existing.player().equals(entry.player()) && !existing.name().equals(entry.name())
                ? new Entry(existing.player(), entry.name(), existing.score(), existing.text(), existing.achievedAt()) : existing);
        entries.sort(ORDER);
        changed();
        return true;
    }

    /** Removes every record of one player. @return whether they had any */
    public boolean remove(UUID player) {
        if (!entries.removeIf(entry -> entry.player().equals(player))) return false;
        changed();
        return true;
    }

    /** Removes one record. @return whether it was there */
    public boolean remove(Entry entry) {
        if (!entries.remove(entry)) return false;
        changed();
        return true;
    }

    /** Removes every record. */
    public void clear() {
        entries.clear();
        changed();
    }

    /** Called (on the main thread) whenever the records change. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        save();
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                logger.error("Error refreshing leaderboard '{}'", getId(), e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------------------------------------------------

    void load() {
        entries.clear();
        if (!file.isFile()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        // Files from before boards had versions are version 1.
        int saved = config.getInt("version", 1);
        boolean upgrading = saved < definition.version() && definition.upgrade() != null;
        if (saved > definition.version()) {
            logger.warn("Leaderboard {} was saved by a newer version ({}, this one knows {}); its scores may read wrong",
                    file.getName(), saved, definition.version());
        }
        for (Map<?, ?> raw : config.getMapList("entries")) {
            try {
                List<Long> score = new ArrayList<>();
                for (Object number : (List<?>) Objects.requireNonNull(raw.get("score"), "score")) {
                    score.add(((Number) number).longValue());
                }
                if (upgrading) score = List.copyOf(Objects.requireNonNull(definition.upgrade().upgrade(saved, score), "upgraded score"));
                entries.add(new Entry(UUID.fromString(String.valueOf(raw.get("player"))), String.valueOf(raw.get("name")),
                        score, textFor(score, String.valueOf(raw.get("text"))), ((Number) Objects.requireNonNull(raw.get("at"), "at")).longValue()));
            } catch (RuntimeException e) {
                logger.warn("Skipped a broken record in {}: {}", file.getName(), e.toString());
            }
        }
        entries.sort(ORDER);
        if (upgrading) {
            // Once: keep the old file (an older plugin would misread the new scores), then save the new kind.
            Path backup = file.toPath().resolveSibling(file.getName() + ".v" + saved + ".bak");
            try {
                if (!Files.exists(backup)) Files.copy(file.toPath(), backup);
                save();
                logger.info("Upgraded leaderboard {} from version {} to {} ({} records; the old file is kept as {})",
                        file.getName(), saved, definition.version(), entries.size(), backup.getFileName());
            } catch (IOException e) {
                // Not saved: it's upgraded again, from the untouched file, next time.
                logger.error("Could not back up leaderboard {} before upgrading it; it's upgraded in memory only", file, e);
            }
        }
    }

    /** A record's text: made from its score if the board says how, else (or if that fails) the saved text. */
    private String textFor(List<Long> score, String saved) {
        if (definition.text() == null) return saved;
        try {
            return Objects.requireNonNull(definition.text().apply(List.copyOf(score)));
        } catch (RuntimeException e) {
            logger.warn("Kept the saved text of a record in {} ({}): {}", file.getName(), saved, e.toString());
            return saved;
        }
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry entry : entries) {
            list.add(Map.of("player", entry.player().toString(), "name", entry.name(), "score", entry.score(),
                    "text", entry.text(), "at", entry.achievedAt()));
        }
        config.set("version", definition.version());
        config.set("entries", list);
        try {
            config.save(file);
        } catch (IOException e) {
            logger.error("Could not save leaderboard {}", file, e);
        }
    }
}
