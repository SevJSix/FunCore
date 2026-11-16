package me.sevj6.funCore.games.trivia;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Where questions come from: Open Trivia DB ({@link TriviaApi}), The Trivia API ({@link TheTriviaApi}), or both. With
 * both, each request is split between them and the questions mixed; if one fails, the other makes up its share.
 * Settings The Trivia API has nothing for (true/false only, or a category it doesn't have) use Open Trivia DB alone.
 */
final class QuestionSources {

    static final String BOTH = "both", OPEN_TRIVIA_DB = "opentdb", THE_TRIVIA_API = "the-trivia-api";

    private final TriviaApi openTriviaDb;
    private final TheTriviaApi theTriviaApi;

    QuestionSources(Logger logger) {
        openTriviaDb = new TriviaApi(logger);
        theTriviaApi = new TheTriviaApi(logger);
    }

    /** Checks the {@code source} option. */
    static String checkSource(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "-");
        return switch (lower) {
            case "both", "any", "mixed" -> BOTH;
            case "opentdb", "opentriviadb", "open-trivia-db" -> OPEN_TRIVIA_DB;
            case "the-trivia-api", "thetriviaapi", "trivia-api", "triviaapi" -> THE_TRIVIA_API;
            default -> throw new IllegalArgumentException("source is both, opentdb or the-trivia-api.");
        };
    }

    /** The sources these settings actually use (names, for the credit). */
    static List<String> used(String source, int category, String type) {
        boolean other = TheTriviaApi.supports(category, type);
        return switch (source) {
            case OPEN_TRIVIA_DB -> List.of(TriviaApi.NAME);
            case THE_TRIVIA_API -> other ? List.of(TheTriviaApi.NAME) : List.of();
            default -> other ? List.of(TriviaApi.NAME, TheTriviaApi.NAME) : List.of(TriviaApi.NAME);
        };
    }

    /**
     * Gets {@code amount} questions (at most 50) in the background, mixed if both sources are used.
     *
     * @return the questions; fails with a {@link TriviaApi.TriviaException} saying why if it can't
     */
    CompletableFuture<List<TriviaApi.Question>> fetch(int amount, String source, int category, String difficulty, String type) {
        List<String> used = used(source, category, type);
        if (used.isEmpty()) {
            return CompletableFuture.failedFuture(new TriviaApi.TriviaException(
                    TheTriviaApi.NAME + " has no true/false questions, nor any in that category"));
        }
        if (used.size() == 1) {
            return used.getFirst().equals(TriviaApi.NAME)
                    ? openTriviaDb.fetch(amount, category, difficulty, type)
                    : theTriviaApi.fetch(amount, category, difficulty);
        }
        int fromOther = amount / 2 + (amount % 2 == 1 && Math.random() < 0.5 ? 1 : 0);
        int fromOpen = amount - fromOther;
        CompletableFuture<@Nullable List<TriviaApi.Question>> open = fromOpen == 0
                ? CompletableFuture.completedFuture(List.of())
                : openTriviaDb.fetch(fromOpen, category, difficulty, type).exceptionally(error -> null);
        CompletableFuture<@Nullable List<TriviaApi.Question>> other = fromOther == 0
                ? CompletableFuture.completedFuture(List.of())
                : theTriviaApi.fetch(fromOther, category, difficulty).exceptionally(error -> null);
        return open.thenCompose(a -> other.thenCompose(b -> {
            if (a != null && b != null) return CompletableFuture.completedFuture(mix(a, b));
            if (a == null && b == null) {
                // Both failed: say why Open Trivia DB did, by asking it for everything.
                return openTriviaDb.fetch(amount, category, difficulty, type);
            }
            // One failed: the other makes up its share.
            List<TriviaApi.Question> got = a != null ? a : b;
            int missing = amount - got.size();
            if (missing <= 0) return CompletableFuture.completedFuture(got);
            CompletableFuture<List<TriviaApi.Question>> more = a != null
                    ? openTriviaDb.fetch(missing, category, difficulty, type)
                    : theTriviaApi.fetch(missing, category, difficulty);
            return more.handle((extra, error) -> extra == null ? got : mix(got, extra));
        }));
    }

    private static List<TriviaApi.Question> mix(List<TriviaApi.Question> a, List<TriviaApi.Question> b) {
        List<TriviaApi.Question> all = new ArrayList<>(a);
        all.addAll(b);
        Collections.shuffle(all);
        return all;
    }
}
