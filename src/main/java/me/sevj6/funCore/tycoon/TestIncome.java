package me.sevj6.funCore.tycoon;

import me.sevj6.funCore.economy.Economy;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Money a plot paid out while it was in test mode (an admin used a testing tool on it, like {@code own}). It's only
 * lent: when the session ends, it's taken back off the player's balance, so testing never leaves money in the real
 * economy.
 *
 * <p>What's owed is saved in {@code tycoon/test-income.yml} as it grows (on a background thread), so a crash can't
 * turn it into real money either: the next start takes it back.
 */
final class TestIncome {

    private final Map<UUID, Long> lent = new HashMap<>();
    private final Path file;
    private final Economy economy;
    private final Logger logger;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "FunCore-Tycoon-TestIncome"));

    TestIncome(Path file, Economy economy, Logger logger) {
        this.file = file;
        this.economy = economy;
        this.logger = logger;
    }

    /** Takes back what an earlier run lent but never got back (a crash). */
    void recoverAfterCrash() {
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            if (root instanceof Map<?, ?> map) {
                map.forEach((id, cents) -> {
                    try {
                        if (cents instanceof Number amount) takeBack(UUID.fromString(String.valueOf(id)), amount.longValue());
                    } catch (IllegalArgumentException e) {
                        logger.warn("Tycoon: test-income.yml has a bad entry '{}'; skipped", id);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read {}", file, e);
        }
        save();
    }

    /** Records money paid to a player while their plot was in test mode. */
    void lend(UUID player, long cents) {
        if (cents <= 0) return;
        lent.merge(player, cents, Long::sum);
        save();
    }

    /** The session ended: takes back everything lent to the player. */
    void settle(UUID player) {
        Long cents = lent.remove(player);
        if (cents == null) return;
        takeBack(player, cents);
        save();
    }

    private void takeBack(UUID player, long cents) {
        long balance = economy.getBalance(player);
        long amount = Math.min(cents, balance);
        if (amount > 0) economy.withdraw(player, amount, "tycoon test income taken back");
        if (amount < cents) {
            logger.warn("Tycoon: {} spent some test income; only {} of {} cents could be taken back", player, amount, cents);
        }
    }

    void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) logger.warn("Tycoon test-income writer did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        write(snapshot());
    }

    private void save() {
        Map<String, Long> snapshot = snapshot();
        if (!writer.isShutdown()) writer.execute(() -> write(snapshot));
    }

    private Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        lent.forEach((id, cents) -> snapshot.put(id.toString(), cents));
        return snapshot;
    }

    private void write(Map<String, Long> snapshot) {
        try {
            String text = "# Money paid out by tycoon plots in test mode, taken back when the session ends (or on the next start\n"
                    + "# after a crash). Player UUID: cents. Don't edit.\n" + (snapshot.isEmpty() ? "{}\n" : new Yaml().dump(snapshot));
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Could not save {}", file, e);
        }
    }
}
