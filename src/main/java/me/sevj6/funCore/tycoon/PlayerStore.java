package me.sevj6.funCore.tycoon;

import me.sevj6.funCore.tycoon.core.data.PlayerData;
import me.sevj6.funCore.tycoon.core.data.PlayerDataCodec;
import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Each player's tycoon progress in {@code tycoon/players/<uuid>.yml} (see {@link PlayerDataCodec}).
 *
 * <p>Loads and saves run on one background thread, in the order they're asked for, so a quick rejoin can never read
 * a file before the save from leaving has landed. A save is a snapshot made on the main thread (never the live data)
 * and is written to a temporary file that's then moved into place, so a file is never half-written.
 */
final class PlayerStore {

    private final Path folder;
    private final Logger logger;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "FunCore-Tycoon-Players"));

    PlayerStore(Path folder, Logger logger) {
        this.folder = folder;
        this.logger = logger;
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            logger.error("Could not create {}", folder, e);
        }
    }

    /** Reads a player's progress (empty if they've never played), after any saves already asked for. */
    CompletableFuture<PlayerData> load(UUID player) {
        return CompletableFuture.supplyAsync(() -> read(player), io);
    }

    private PlayerData read(UUID player) {
        Path file = file(player);
        if (!Files.exists(file)) return new PlayerData();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return PlayerDataCodec.fromMap(new Yaml().load(reader));
        } catch (IOException | RuntimeException e) {
            // Never start them from nothing over a file we couldn't read: keep it, and say so loudly.
            logger.error("Could not read {}; {} starts empty this session and the file is kept as it is", file, player, e);
            Path keep = file.resolveSibling(file.getFileName() + ".unreadable-" + System.currentTimeMillis());
            try {
                Files.copy(file, keep);
            } catch (IOException copy) {
                logger.error("Could not keep a copy of {}", file, copy);
            }
            return new PlayerData();
        }
    }

    /** Saves a snapshot of {@code data} (made now, on this thread), written in the background. */
    void save(UUID player, PlayerData data) {
        Map<String, Object> snapshot = PlayerDataCodec.toMap(data);
        if (io.isShutdown()) {
            write(player, snapshot);
            return;
        }
        io.execute(() -> write(player, snapshot));
    }

    /** Finishes every load and save. */
    void shutdown() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) logger.warn("Tycoon player saves did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(UUID player, Map<String, Object> snapshot) {
        Path file = file(player);
        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            String text = "# Tycoon progress. Money is in cents.\n" + new Yaml(options).dump(snapshot);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Could not save {}", file, e);
        }
    }

    private Path file(UUID player) {
        return folder.resolve(player + ".yml");
    }
}
