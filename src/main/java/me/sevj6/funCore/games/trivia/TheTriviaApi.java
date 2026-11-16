package me.sevj6.funCore.games.trivia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Questions from The Trivia API (the-trivia-api.com). Without a key it gives English multiple-choice questions only (no
 * true/false), up to 50 per request, 20 requests per 5 seconds per IP, free for non-commercial use with credit (CC
 * BY-NC 4.0). It has no free way to avoid repeats, so this client remembers the ids it handed out recently and asks
 * for extra to skip them.
 */
final class TheTriviaApi {

    /** Its name, for the credit. */
    static final String NAME = "The Trivia API";
    private static final String BASE = "https://the-trivia-api.com/v2/questions";
    /** Most questions per request without a key. */
    static final int MAX_PER_REQUEST = 50;
    /** 20 requests per 5 seconds is the limit; this keeps well under it. */
    private static final long SPACING_MS = 300;
    private static final int ATTEMPTS = 3;
    /** How many question ids to remember, to avoid asking them again soon. */
    private static final int REMEMBERED = 2_000;

    /**
     * Its categories for the Open Trivia DB ones that match well enough (the {@code category} option uses Open Trivia
     * DB ids). Any other category is left to Open Trivia DB alone.
     */
    private static final Map<Integer, String> CATEGORIES = Map.ofEntries(
            Map.entry(9, "general_knowledge"),
            Map.entry(10, "arts_and_literature"), // Books
            Map.entry(11, "film_and_tv"),         // Film
            Map.entry(12, "music"),
            Map.entry(14, "film_and_tv"),         // Television
            Map.entry(17, "science"),             // Science & Nature
            Map.entry(21, "sport_and_leisure"),   // Sports
            Map.entry(22, "geography"),
            Map.entry(23, "history"),
            Map.entry(25, "arts_and_literature")  // Art
    );

    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService queue = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
        Thread thread = new Thread(task, "FunCore trivia (the-trivia-api)");
        thread.setDaemon(true);
        return thread;
    });
    private final Random random = new Random();
    /** Only touched on the queue's thread. */
    private final Set<String> recent = new LinkedHashSet<>();
    private long lastRequest;

    TheTriviaApi(Logger logger) {
        this.logger = logger;
    }

    /**
     * Its category for an Open Trivia DB category id: {@code ""} for any, or {@code null} if it has none that matches.
     */
    static @Nullable String category(int openTriviaCategory) {
        return openTriviaCategory <= 0 ? "" : CATEGORIES.get(openTriviaCategory);
    }

    /** Whether it has questions for these settings (it has no true/false ones). */
    static boolean supports(int openTriviaCategory, String type) {
        return !type.equals("boolean") && category(openTriviaCategory) != null;
    }

    /**
     * Gets {@code amount} questions (at most {@link #MAX_PER_REQUEST}) in the background.
     *
     * @param category   an Open Trivia DB category id (see {@link #supports})
     * @param difficulty {@code any}, {@code easy}, {@code medium} or {@code hard}
     */
    CompletableFuture<List<TriviaApi.Question>> fetch(int amount, int category, String difficulty) {
        int count = Math.clamp(amount, 1, MAX_PER_REQUEST);
        return CompletableFuture.supplyAsync(() -> fetchNow(count, category, difficulty), queue);
    }

    private List<TriviaApi.Question> fetchNow(int amount, int category, String difficulty) {
        String slug = category(category);
        if (slug == null) throw new TriviaApi.TriviaException(NAME + " has no questions in that category");
        // Ask for extra, to skip the ones asked recently.
        int ask = Math.min(MAX_PER_REQUEST, amount * 2);
        String reason = "the trivia service didn't answer";
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            waitFor(lastRequest + SPACING_MS);
            HttpResponse<String> response;
            try {
                response = get(questionsUrl(ask, slug, difficulty));
            } catch (IOException e) {
                logger.warn("Trivia: couldn't reach the-trivia-api.com: {}", e.toString());
                reason = "couldn't reach the trivia service";
                continue;
            } finally {
                lastRequest = System.currentTimeMillis();
            }
            if (response.statusCode() == 429) {
                reason = "the trivia service is busy";
                long reset = response.headers().firstValueAsLong("ratelimit-reset").orElse(5);
                waitFor(System.currentTimeMillis() + Math.clamp(reset, 1, 10) * 1000);
                continue;
            }
            if (response.statusCode() != 200) {
                logger.warn("Trivia: the-trivia-api.com answered HTTP {}", response.statusCode());
                reason = "the trivia service sent something unexpected";
                continue;
            }
            List<Parsed> parsed;
            try {
                parsed = parse(response.body(), random);
            } catch (RuntimeException e) {
                logger.warn("Trivia: unexpected reply from the-trivia-api.com: {}", e.toString());
                reason = "the trivia service sent something unexpected";
                continue;
            }
            if (parsed.isEmpty()) throw new TriviaApi.TriviaException("there aren't enough questions for these settings");
            return pick(parsed, amount);
        }
        throw new TriviaApi.TriviaException(reason);
    }

    /** {@code amount} of them, the ones not asked recently first, and remembers them. */
    private List<TriviaApi.Question> pick(List<Parsed> parsed, int amount) {
        List<Parsed> fresh = new ArrayList<>(), seen = new ArrayList<>();
        for (Parsed one : parsed) (recent.contains(one.id()) ? seen : fresh).add(one);
        fresh.addAll(seen);
        List<TriviaApi.Question> questions = new ArrayList<>();
        for (Parsed one : fresh.subList(0, Math.min(amount, fresh.size()))) {
            recent.remove(one.id());
            recent.add(one.id());
            questions.add(one.question());
        }
        for (Iterator<String> oldest = recent.iterator(); recent.size() > REMEMBERED; ) {
            oldest.next();
            oldest.remove();
        }
        return questions;
    }

    static String questionsUrl(int amount, String category, String difficulty) {
        StringBuilder url = new StringBuilder(BASE).append("?limit=").append(amount).append("&types=text_choice");
        if (!category.isEmpty()) url.append("&categories=").append(category);
        if (!difficulty.equals("any")) url.append("&difficulties=").append(difficulty);
        return url.toString();
    }

    private static void waitFor(long until) {
        long wait = until - System.currentTimeMillis();
        if (wait <= 0) return;
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private HttpResponse<String> get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** A question and its id (to avoid repeats). */
    record Parsed(String id, TriviaApi.Question question) {
    }

    /**
     * The questions in a reply (a JSON array), answers in a random order. Questions without exactly 3 wrong answers
     * are skipped, so every question has 4.
     */
    static List<Parsed> parse(String body, Random random) {
        JsonArray results = JsonParser.parseString(body).getAsJsonArray();
        List<Parsed> questions = new ArrayList<>();
        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            JsonArray wrong = result.getAsJsonArray("incorrectAnswers");
            if (wrong == null || wrong.size() != 3) continue;
            String text = result.getAsJsonObject("question").get("text").getAsString().strip();
            String correct = result.get("correctAnswer").getAsString().strip();
            List<String> answers = new ArrayList<>();
            answers.add(correct);
            for (JsonElement answer : wrong) answers.add(answer.getAsString().strip());
            Collections.shuffle(answers, random);
            String difficulty = result.has("difficulty") ? result.get("difficulty").getAsString() : "";
            String id = result.has("id") ? result.get("id").getAsString() : text;
            questions.add(new Parsed(id, new TriviaApi.Question(text, answers, answers.indexOf(correct), false, difficulty, NAME)));
        }
        return questions;
    }
}
