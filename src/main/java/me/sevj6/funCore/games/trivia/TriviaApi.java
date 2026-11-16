package me.sevj6.funCore.games.trivia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Questions from the Open Trivia Database (opentdb.com), its free JSON API. One client is shared by every trivia match
 * on the server, because the API allows each IP one request every 5 seconds: requests wait their turn on a single
 * background thread. A session token (one for the server) keeps questions from repeating until all of them have been
 * asked; it's renewed or reset when the API says so. Text comes URL-encoded (RFC 3986), so nothing is left as HTML
 * entities like {@code &quot;}.
 */
final class TriviaApi {

    /**
     * A question, ready to ask.
     *
     * @param answers   2 (true/false: "True", "False") or 4, the right one somewhere among them
     * @param correct   which of {@code answers} is right
     * @param trueFalse whether it's a true/false question
     * @param source    where it came from, for the credit ({@link #NAME} or {@link TheTriviaApi#NAME})
     */
    record Question(String text, List<String> answers, int correct, boolean trueFalse, String difficulty, String source) {
        Question {
            answers = List.copyOf(answers);
            if (correct < 0 || correct >= answers.size()) throw new IllegalArgumentException("correct answer out of range");
        }
    }

    /** What the API couldn't do, said so a player can read it. */
    static final class TriviaException extends RuntimeException {
        TriviaException(String message) {
            super(message);
        }
    }

    /** Its name, for the credit (questions are CC BY-SA 4.0). */
    static final String NAME = "Open Trivia DB";
    private static final String BASE = "https://opentdb.com/";
    /** The API's limit is one request per 5 seconds per IP; a little more, to be safe. */
    static final long SPACING_MS = 5_300;
    /** Most questions the API returns per request. */
    static final int MAX_PER_REQUEST = 50;
    private static final int ATTEMPTS = 4;

    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** One request at a time; the thread goes away when idle. */
    private final ExecutorService queue = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
        Thread thread = new Thread(task, "FunCore trivia");
        thread.setDaemon(true);
        return thread;
    });
    private final Random random = new Random();
    /** Only touched on the queue's thread. */
    private @Nullable String token;
    private long lastQuestionRequest;

    TriviaApi(Logger logger) {
        this.logger = logger;
    }

    /**
     * Gets {@code amount} questions (at most {@link #MAX_PER_REQUEST}) in the background.
     *
     * @param category   an Open Trivia DB category id (9 = General Knowledge), or 0 for any
     * @param difficulty {@code any}, {@code easy}, {@code medium} or {@code hard}
     * @param type       {@code any}, {@code multiple} or {@code boolean}
     * @return the questions; fails with a {@link TriviaException} saying why if it can't
     */
    CompletableFuture<List<Question>> fetch(int amount, int category, String difficulty, String type) {
        int count = Math.clamp(amount, 1, MAX_PER_REQUEST);
        return CompletableFuture.supplyAsync(() -> fetchNow(count, category, difficulty, type), queue);
    }

    private List<Question> fetchNow(int amount, int category, String difficulty, String type) {
        String reason = "the trivia service didn't answer";
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (token == null) token = requestToken();
            waitForTurn();
            String body;
            try {
                body = get(questionsUrl(amount, category, difficulty, type, token));
            } catch (IOException e) {
                logger.warn("Trivia: couldn't reach opentdb.com: {}", e.toString());
                reason = "couldn't reach the trivia service";
                continue;
            } finally {
                lastQuestionRequest = System.currentTimeMillis();
            }
            int code = responseCode(body);
            switch (code) {
                case 0 -> {
                    return parse(body, random);
                }
                case 1 -> {
                    // Not enough questions left: with a token, its history is what's in the way.
                    if (token == null) throw new TriviaException("there aren't enough questions for these settings");
                    resetToken();
                    reason = "there aren't enough questions for these settings";
                }
                case 2 -> throw new TriviaException("the trivia settings aren't valid (category, difficulty or question type)");
                case 3 -> token = null; // expired after 6 hours unused: get a new one
                case 4 -> resetToken(); // every question asked: start over
                case 5 -> reason = "the trivia service is busy"; // rate limited: the next attempt waits its turn
                default -> {
                    logger.warn("Trivia: unexpected reply from opentdb.com: {}", body.length() > 200 ? body.substring(0, 200) : body);
                    reason = "the trivia service sent something unexpected";
                }
            }
        }
        throw new TriviaException(reason);
    }

    static String questionsUrl(int amount, int category, String difficulty, String type, @Nullable String token) {
        StringBuilder url = new StringBuilder(BASE).append("api.php?amount=").append(amount);
        if (category > 0) url.append("&category=").append(category);
        if (!difficulty.equals("any")) url.append("&difficulty=").append(difficulty);
        if (!type.equals("any")) url.append("&type=").append(type);
        url.append("&encode=url3986");
        if (token != null) url.append("&token=").append(token);
        return url.toString();
    }

    /** Sleeps until the API takes another request from this server. */
    private void waitForTurn() {
        long wait = lastQuestionRequest + SPACING_MS - System.currentTimeMillis();
        if (wait <= 0) return;
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private @Nullable String requestToken() {
        try {
            JsonObject reply = JsonParser.parseString(get(BASE + "api_token.php?command=request")).getAsJsonObject();
            if (reply.has("token") && reply.get("response_code").getAsInt() == 0) return reply.get("token").getAsString();
        } catch (IOException | RuntimeException e) {
            logger.warn("Trivia: couldn't get a session token (questions may repeat): {}", e.toString());
        }
        return null;
    }

    private void resetToken() {
        if (token == null) return;
        try {
            JsonObject reply = JsonParser.parseString(get(BASE + "api_token.php?command=reset&token=" + token)).getAsJsonObject();
            if (reply.get("response_code").getAsInt() != 0) token = null;
        } catch (IOException | RuntimeException e) {
            token = null;
        }
    }

    /** The body, whatever the status (a refusal is a 429 with JSON saying why). */
    private String get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** The API's {@code response_code}, or -1 if the reply isn't what it should be. */
    static int responseCode(String body) {
        try {
            JsonElement code = JsonParser.parseString(body).getAsJsonObject().get("response_code");
            return code == null ? -1 : code.getAsInt();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The questions in a successful reply, with each multiple-choice question's answers in a random order. */
    static List<Question> parse(String body, Random random) {
        JsonArray results = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("results");
        List<Question> questions = new ArrayList<>();
        if (results == null) return questions;
        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            String text = decode(result.get("question").getAsString());
            String correct = decode(result.get("correct_answer").getAsString());
            String difficulty = decode(result.get("difficulty").getAsString());
            if ("boolean".equals(result.get("type").getAsString())) {
                boolean isTrue = correct.equalsIgnoreCase("true");
                questions.add(new Question(text, List.of("True", "False"), isTrue ? 0 : 1, true, difficulty, NAME));
                continue;
            }
            List<String> answers = new ArrayList<>();
            answers.add(correct);
            for (JsonElement wrong : result.getAsJsonArray("incorrect_answers")) answers.add(decode(wrong.getAsString()));
            Collections.shuffle(answers, random);
            questions.add(new Question(text, answers, answers.indexOf(correct), false, difficulty, NAME));
        }
        return questions;
    }

    /** RFC 3986 encoding ({@code %20} for spaces; a {@code +} stays {@code %2B}, so it decodes right). */
    static String decode(String encoded) {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8).strip();
    }
}
