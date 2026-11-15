package me.sevj6.funCore.leaderboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardTextTest {

    /** Two records as the server saved them before times had milliseconds. */
    private static final String OLD_FILE = """
            entries:
            - at: 1794456774396
              player: 6433b972-2eda-47fa-b94b-08536e45a748
              name: Alpha
              score:
              - 0
              - 880
              text: 0 deaths, 0:44
            - at: 1794754264770
              player: d488f565-9c77-4253-abb6-b075e54bbba2
              name: Beta
              score:
              - 1
              - 919
              text: 1 death, 0:45
            """;

    private static Leaderboard load(Path dir, Leaderboard.Definition definition) throws IOException {
        File file = dir.resolve(definition.id() + ".yml").toFile();
        Files.writeString(file.toPath(), OLD_FILE);
        Leaderboard board = new Leaderboard(definition, file, NOPLogger.NOP_LOGGER);
        board.load();
        return board;
    }

    @Test
    void oldRecordsAreShownTheNewWay(@TempDir Path dir) throws IOException {
        Leaderboard board = load(dir, new Leaderboard.Definition("clutch", "Best", 2,
                score -> score.get(0) + " deaths, " + me.sevj6.funCore.util.TimeFormat.ticksWithMillis(score.get(1))));
        List<Leaderboard.Entry> entries = board.getEntries();
        assertEquals("0 deaths, 0:44.000", entries.get(0).text());
        assertEquals("1 deaths, 0:45.950", entries.get(1).text());
        assertEquals(List.of(0L, 880L), entries.get(0).score(), "the score is untouched");
    }

    @Test
    void withoutAFormatTheSavedTextStays(@TempDir Path dir) throws IOException {
        Leaderboard board = load(dir, new Leaderboard.Definition("plain", "Best", 2));
        assertEquals("0 deaths, 0:44", board.getEntries().get(0).text());
    }

    @Test
    void aBrokenFormatKeepsTheSavedText(@TempDir Path dir) throws IOException {
        Leaderboard board = load(dir, new Leaderboard.Definition("broken", "Best", 2, score -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(2, board.getEntries().size(), "no record is lost");
        assertEquals("0 deaths, 0:44", board.getEntries().get(0).text());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Score versions: Clutch's scores went from ticks to milliseconds
    // ------------------------------------------------------------------------------------------------------------

    /** Like Clutch's board: version 2 scores are milliseconds, version 1 were ticks. */
    private static final Leaderboard.Definition MILLIS = new Leaderboard.Definition("clutch", "Best", 2,
            score -> score.get(0) + " deaths, " + me.sevj6.funCore.util.TimeFormat.withMillis(score.get(1)), 2,
            (saved, score) -> saved < 2 ? List.of(score.get(0), score.get(1) * 50) : score);

    private static Leaderboard reload(File file) {
        Leaderboard board = new Leaderboard(MILLIS, file, NOPLogger.NOP_LOGGER);
        board.load();
        return board;
    }

    @Test
    void oldScoresAreUpgradedOnceAndTheOldFileKept(@TempDir Path dir) throws IOException {
        Leaderboard board = load(dir, MILLIS);
        assertEquals(List.of(0L, 44_000L), board.getEntries().get(0).score());
        assertEquals("0 deaths, 0:44.000", board.getEntries().get(0).text());
        assertEquals("1 deaths, 0:45.950", board.getEntries().get(1).text());

        File file = dir.resolve("clutch.yml").toFile();
        assertTrue(Files.readString(file.toPath()).contains("version: 2"), "saved as the new kind");
        Path backup = dir.resolve("clutch.yml.v1.bak");
        assertEquals(OLD_FILE, Files.readString(backup), "the old file is kept as it was");

        // Loading again doesn't upgrade again.
        assertEquals(List.of(0L, 44_000L), reload(file).getEntries().get(0).score());
    }

    @Test
    void aNewRecordDoesntUpgradeTheOldOnesAgain(@TempDir Path dir) throws IOException {
        Leaderboard board = load(dir, MILLIS);
        UUID newcomer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        board.submit(new Leaderboard.Entry(newcomer, "Gamma", List.of(0L, 43_217L), "0 deaths, 0:43.217", 1L));

        Leaderboard again = reload(dir.resolve("clutch.yml").toFile());
        List<Leaderboard.Entry> entries = again.getEntries();
        assertEquals(3, entries.size());
        assertEquals("Gamma", entries.get(0).name(), "43.217 s beats 44.000 s");
        assertEquals(List.of(0L, 43_217L), entries.get(0).score());
        assertEquals(List.of(0L, 44_000L), entries.get(1).score(), "converted exactly once");
        assertEquals(List.of(1L, 45_950L), entries.get(2).score());
    }

    @Test
    void boardsWithoutVersionsAreLeftAlone(@TempDir Path dir) throws IOException {
        Leaderboard board = load(dir, new Leaderboard.Definition("plain", "Best", 2));
        assertEquals(List.of(0L, 880L), board.getEntries().get(0).score());
        assertFalse(Files.exists(dir.resolve("plain.yml.v1.bak")), "nothing to back up");
    }
}
