package me.sevj6.funCore.tycoon.plot;

import me.sevj6.funCore.tycoon.core.template.Offset;
import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Every real block the tycoon has put into the map (barriers, pressure plates), by plot slot and offset from the
 * plot origin, saved in {@code tycoon/fixtures.yml}. Normally the plugin removes them itself; the journal is for when
 * it couldn't (a crash), so the next start can sweep them away and leave the map exactly as pasted.
 *
 * <p>Saves run on a background thread, writing a temporary file and moving it into place.
 */
public final class FixtureJournal {

    /** What was placed where in one slot: block offset to material name. */
    private final Map<Integer, Map<Offset, String>> placed = new HashMap<>();
    private final Path file;
    private final Logger logger;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "FunCore-Tycoon-Fixtures"));

    public FixtureJournal(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Reads what an earlier run left in the journal. Slots are 0-based. */
    public Map<Integer, Map<Offset, String>> load() {
        Map<Integer, Map<Offset, String>> result = new HashMap<>();
        if (!Files.exists(file)) return result;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            if (!(root instanceof Map<?, ?> slots)) return result;
            slots.forEach((slot, entries) -> {
                int index;
                try {
                    index = Integer.parseInt(String.valueOf(slot)) - 1;
                } catch (NumberFormatException e) {
                    return;
                }
                if (!(entries instanceof List<?> list)) return;
                for (Object entry : list) {
                    if (entry instanceof List<?> e && e.size() == 4 && e.get(0) instanceof Number x && e.get(1) instanceof Number y
                            && e.get(2) instanceof Number z) {
                        result.computeIfAbsent(index, i -> new HashMap<>()).put(new Offset(x.intValue(), y.intValue(), z.intValue()),
                                String.valueOf(e.get(3)));
                    }
                }
            });
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read {}; leftover tycoon blocks may need removing by hand", file, e);
        }
        return result;
    }

    public void add(int slot, Offset at, String material) {
        placed.computeIfAbsent(slot, i -> new HashMap<>()).put(at, material);
        save();
    }

    public void remove(int slot, Offset at) {
        Map<Offset, String> blocks = placed.get(slot);
        if (blocks == null || blocks.remove(at) == null) return;
        if (blocks.isEmpty()) placed.remove(slot);
        save();
    }

    /** Writes the journal soon, on the writer thread. */
    private void save() {
        Map<String, List<List<Object>>> snapshot = snapshot();
        if (!writer.isShutdown()) writer.execute(() -> write(snapshot));
    }

    /** Finishes pending writes, then writes the journal once more (normally empty: everything was removed). */
    public void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) logger.warn("Tycoon fixture journal writer did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        write(snapshot());
    }

    private Map<String, List<List<Object>>> snapshot() {
        Map<String, List<List<Object>>> snapshot = new LinkedHashMap<>();
        placed.forEach((slot, blocks) -> {
            List<List<Object>> entries = new ArrayList<>();
            blocks.forEach((at, material) -> entries.add(List.of(at.x(), at.y(), at.z(), material)));
            snapshot.put(String.valueOf(slot + 1), entries);
        });
        return snapshot;
    }

    private void write(Map<String, List<List<Object>>> snapshot) {
        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            String text = "# Blocks the tycoon placed in the map, by plot (1-8) and [x, y, z, material] from the plot origin.\n"
                    + "# Swept away on start, so a crash never leaves them behind. Don't edit.\n"
                    + (snapshot.isEmpty() ? "{}\n" : new Yaml(options).dump(snapshot));
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Could not save {}", file, e);
        }
    }
}
