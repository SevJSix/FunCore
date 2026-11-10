package me.sevj6.funCore.leaderboard;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records kept forever (until an admin clears them), best first, e.g. Clutch's best completions. Every player keeps
 * their best {@link Definition#perPlayer()} records, so the board can't be filled by one person, and everyone's own best
 * can still be looked up when they're not near the top.
 * <p>
 * A record's score is a list of numbers where lower is better, compared in order: Clutch uses {@code [deaths, ticks]},
 * so the fewest deaths win and the fastest time breaks ties. Its {@code text} is what the board shows after the name,
 * e.g. {@code "0 deaths, 1:30"}. Saved in {@code plugins/FunCore/leaderboards/<id>.yml}.
 */
public final class Leaderboard {

    /**
     * What a leaderboard is.
     *
     * @param id        lowercase, also the file name
     * @param title     MiniMessage, shown at the top of the board
     * @param perPlayer how many of each player's records are kept
     */
    public record Definition(String id, String title, int perPlayer) {
        public Definition {
            if (!id.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid leaderboard id: " + id);
            if (perPlayer < 1) throw new IllegalArgumentException("perPlayer must be at least 1");
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
        for (Map<?, ?> raw : config.getMapList("entries")) {
            try {
                List<Long> score = new ArrayList<>();
                for (Object number : (List<?>) Objects.requireNonNull(raw.get("score"), "score")) {
                    score.add(((Number) number).longValue());
                }
                entries.add(new Entry(UUID.fromString(String.valueOf(raw.get("player"))), String.valueOf(raw.get("name")),
                        score, String.valueOf(raw.get("text")), ((Number) Objects.requireNonNull(raw.get("at"), "at")).longValue()));
            } catch (RuntimeException e) {
                logger.warn("Skipped a broken record in {}: {}", file.getName(), e.toString());
            }
        }
        entries.sort(ORDER);
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry entry : entries) {
            list.add(Map.of("player", entry.player().toString(), "name", entry.name(), "score", entry.score(),
                    "text", entry.text(), "at", entry.achievedAt()));
        }
        config.set("entries", list);
        try {
            config.save(file);
        } catch (IOException e) {
            logger.error("Could not save leaderboard {}", file, e);
        }
    }
}
