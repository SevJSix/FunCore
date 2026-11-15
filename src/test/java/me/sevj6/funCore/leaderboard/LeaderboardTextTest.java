package me.sevj6.funCore.leaderboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
