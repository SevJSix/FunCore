package me.sevj6.funCore.economy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Append-only, human-readable record of every balance change, in {@code plugins/FunCore/economy/transactions.log}.
 * Useful for answering "where did my money go?". One line per change:
 *
 * <pre>
 * 2026-11-06 14:03:12 | TRANSFER | Notch -> jeb_ | $50 | pay | Notch: $150, jeb_: $75.50
 * </pre>
 */
final class TransactionLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;

    TransactionLog(Path file) {
        this.file = file;
    }

    /** Builds a line now (on the main thread), so the time is when it happened rather than when it's written. */
    static String line(String type, String who, long amount, String reason, String balances) {
        return String.join(" | ", LocalDateTime.now().format(TIME), type, who, Cash.format(amount), reason, balances)
                + System.lineSeparator();
    }

    void write(String line) throws IOException {
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
