package me.sevj6.funCore.games.trivia;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import me.sevj6.funCore.game.GameState;
import me.sevj6.funCore.game.GameTeam;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.game.PlayMode;
import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Trivia: a quiz of questions from the Open Trivia Database (see {@link TriviaApi}), General Knowledge by default.
 *
 * <h2>Rules</h2>
 * <ul>
 *     <li>{@code questions} questions (20), each multiple choice (4 answers) or true/false. A question shows in chat
 *     for {@code read-seconds} (5) before its answers open, then there are {@code answer-seconds} (20) to answer, by
 *     clicking an answer in chat or right-clicking its coloured block in the hotbar. The first pick is final; once
 *     everyone has answered, the answer is revealed straight away. It shows for {@code reveal-seconds} (4).</li>
 *     <li>A right answer is a point; there's no bonus for speed, so ping doesn't matter.</li>
 *     <li>Alone, it's just your score. Otherwise the most points wins; in team modes a team's score is its members'
 *     points averaged (members who left don't count), so a team a player short isn't behind for it.</li>
 *     <li>A tie for first goes to tiebreakers: only the tied players (or teams) answer, one question at a time, and
 *     whoever does best on it goes through (all of them, if they did equally well), until one is left. If the trivia
 *     service fails during the tiebreakers, the tied players share the win.</li>
 * </ul>
 * No arena is needed: players stay where they are (one can be set up, e.g. a quiz room; several matches can share it).
 * Any number of matches can run at once ({@link GameType#hasUnlimitedMatches()}). Players take no damage and don't get
 * hungry while in a match.
 */
public final class TriviaGame extends Minigame {

    public static final String ID = "trivia";
    private static final NamespacedKey ANSWER_KEY = new NamespacedKey("funcore", "trivia_answer");
    private static final NamespacedKey ROUND_KEY = new NamespacedKey("funcore", "trivia_round");
    /** Spare questions fetched with the first ones, for tiebreakers. */
    private static final int SPARES = 5;
    /** Questions fetched when the tiebreakers need more. */
    private static final int MORE = 10;
    /** Shows the standings after every this many questions (with more than one player). */
    private static final int STANDINGS_EVERY = 5;

    private static final String[] LETTERS = {"A", "B", "C", "D"};
    private static final Material[] BLOCKS = {Material.RED_CONCRETE, Material.BLUE_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE};
    private static final NamedTextColor[] COLORS = {NamedTextColor.RED, NamedTextColor.BLUE, NamedTextColor.YELLOW, NamedTextColor.GREEN};

    public static final GameType TYPE = GameType.builder(ID, Utils.mm("<light_purple>Trivia"), TriviaGame::new)
            .modes(PlayMode.SINGLEPLAYER, PlayMode.FFA, PlayMode.DUOS, PlayMode.TRIOS, PlayMode.SQUADS)
            .players(2, 32)
            .dynamicByDefault()
            .pvpByDefault(false)
            .unlimitedMatches()
            .option("questions", 20, 1, 50, "questions per match (tiebreakers come on top)")
            .option("read-seconds", 5, 0, 30, "time to read each question before its answers open")
            .option("answer-seconds", 20, 5, 120, "time to answer, after the reading time")
            .option("reveal-seconds", 4, 1, 15, "how long the right answer shows before the next question")
            .option("category", 9, 0, 32, "Open Trivia DB category: 9 = General Knowledge (ids 9-32), 0 = any")
            .option("difficulty", "any", TriviaGame::checkDifficulty, "any, easy, medium or hard")
            .option("question-type", "any", TriviaGame::checkQuestionType, "any, multiple (4 answers) or boolean (true/false)")
            .build();

    private static @Nullable TriviaApi api;

    /** The one client every match shares (the API's rate limit is per server). */
    private static synchronized TriviaApi api(FunCore plugin) {
        if (api == null) api = new TriviaApi(plugin.getSLF4JLogger());
        return api;
    }

    private enum Phase {
        /** Waiting for questions from the trivia service. */
        LOADING,
        /** The question is up; answers open soon. */
        READING,
        ANSWERING,
        /** The right answer is showing. */
        REVEAL
    }

    // ---- per match ----
    /** Counts matches run by this object, so questions arriving for an earlier one are ignored. */
    private int run;
    private final Deque<TriviaApi.Question> questions = new ArrayDeque<>();
    private int total, asked, roundIds;
    private @Nullable TriviaRound round;
    private Phase phase = Phase.LOADING;
    private int phaseTicks;
    private boolean solo;
    /** Points per player, from the main questions. */
    private final Map<UUID, Integer> points = new HashMap<>();
    /** In a tiebreaker: who's still tied (player ids, or teams), and how many tiebreakers there have been. */
    private final List<Object> contenders = new ArrayList<>();
    private boolean tiebreak;
    private int tiebreaks;
    /** Set once the result is decided (or the questions couldn't be had), for onEnd. */
    private final List<Object> winners = new ArrayList<>();
    private boolean decided, failed;
    private @Nullable BossBar bar;

    public TriviaGame(FunCore plugin, GameType type, @Nullable Arena arena) {
        super(plugin, type, arena);
    }

    private static String checkDifficulty(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (List.of("any", "easy", "medium", "hard").contains(lower)) return lower;
        throw new IllegalArgumentException("difficulty is any, easy, medium or hard.");
    }

    private static String checkQuestionType(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace("-", "").replace("/", "");
        return switch (lower) {
            case "any", "both" -> "any";
            case "multiple", "multiplechoice" -> "multiple";
            case "boolean", "truefalse" -> "boolean";
            default -> throw new IllegalArgumentException("question-type is any, multiple or boolean (true/false).");
        };
    }

    // ------------------------------------------------------------------------------------------------------------
    // Framework hooks
    // ------------------------------------------------------------------------------------------------------------

    /** Several matches can share an arena, and the quiz never changes it. */
    @Override
    protected boolean resetsArena() {
        return false;
    }

    @Override
    protected int getEndDelaySeconds() {
        return 10;
    }

    @Override
    protected boolean canDrop(Player player, ItemStack item) {
        return false;
    }

    @Override
    protected boolean canPickUp(Player player, ItemStack item) {
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        run++;
        total = getIntOption("questions");
        solo = getMode() == PlayMode.SINGLEPLAYER || entrants().size() <= 1;
        for (Player player : getPlayers()) points.put(player.getUniqueId(), 0);
        bar = BossBar.bossBar(Utils.mm("<gray>Getting questions..."), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        for (Player player : getPlayers()) player.showBossBar(bar);
        broadcast(Utils.mm("<light_purple><total> questions. <gray>Click an answer in chat, or right-click its block in your hotbar. "
                        + "Your first pick is final<rule>",
                Placeholder.unparsed("total", String.valueOf(total)),
                Placeholder.unparsed("rule", solo ? "." : getMode().isTeams()
                        ? "; the team with the best average wins." : "; the most right answers wins.")));
        fetch(Math.min(TriviaApi.MAX_PER_REQUEST, total + SPARES));
        runTaskTimer(this::tick, 1L, 1L);
    }

    @Override
    protected void onEnd() {
        if (bar != null) {
            for (Player player : getPlayers()) player.hideBossBar(bar);
        }
        for (Player player : getPlayers()) clearAnswers(player);
        if (failed || points.isEmpty()) return;
        if (!decided) {
            broadcast(Utils.mm("<gray>The quiz ended early."));
            return;
        }
        if (solo) {
            for (Player player : getPlayers()) {
                int score = points.getOrDefault(player.getUniqueId(), 0);
                Utils.title(player, Utils.mm("<light_purple>Quiz over!"), Utils.mm("<white><score></white><gray>/<total> right",
                        Placeholder.unparsed("score", String.valueOf(score)), Placeholder.unparsed("total", String.valueOf(total))), 10, 70, 10);
                player.sendMessage(prefix().append(Utils.mm("<gray>You got <white><score></white> of <total> right.",
                        Placeholder.unparsed("score", String.valueOf(score)), Placeholder.unparsed("total", String.valueOf(total)))));
                Utils.playSound(player, "ui.toast.challenge_complete", 1f, 1f);
            }
            return;
        }
        Map<Object, Double> scores = scores();
        Map<Object, Integer> places = Standings.places(scores, winners);
        Component winnerNames = names(winners);
        broadcast(winners.isEmpty() ? Utils.mm("<gray>Nobody won.")
                : Utils.mm(winners.size() == 1 ? "<gold><name> wins!<after>" : "<gold><name> share the win!<after>",
                Placeholder.component("name", winnerNames),
                Placeholder.unparsed("after", tiebreaks == 0 ? "" : " (after " + tiebreaks + " " + Utils.plural(tiebreaks, "tiebreaker", "tiebreakers") + ")")));
        int shown = 0;
        for (Map.Entry<Object, Integer> entry : places.entrySet()) {
            if (shown++ >= 5) break;
            broadcast(Utils.mm("<gray>#<place> <white><name></white>: <score>",
                    Placeholder.unparsed("place", String.valueOf(entry.getValue())),
                    Placeholder.component("name", nameOf(entry.getKey())),
                    Placeholder.unparsed("score", scoreText(scores.get(entry.getKey())))));
        }
        for (Player player : getPlayers()) {
            Object key = keyOf(player);
            boolean won = key != null && winners.contains(key);
            Integer place = key == null ? null : places.get(key);
            Utils.title(player, won ? Utils.mm("<gold>You win!") : Utils.mm("<yellow>#<place>",
                            Placeholder.unparsed("place", place == null ? "-" : String.valueOf(place))),
                    Utils.mm("<gray><score>", Placeholder.unparsed("score", key == null ? "" : scoreText(scores.getOrDefault(key, 0.0)))),
                    10, 70, 10);
            Utils.playSound(player, won ? "ui.toast.challenge_complete" : "entity.villager.no", 1f, 1f);
        }
    }

    @Override
    protected void onReset() {
        run++;
        questions.clear();
        total = asked = 0;
        round = null;
        phase = Phase.LOADING;
        phaseTicks = 0;
        solo = false;
        points.clear();
        contenders.clear();
        tiebreak = false;
        tiebreaks = 0;
        winners.clear();
        decided = failed = false;
        bar = null;
    }

    @Override
    protected void onPlayerLeave(Player player, LeaveReason reason) {
        if (bar != null) player.hideBossBar(bar);
        clearAnswers(player);
        if (getState() != GameState.RUNNING) return;
        // Next tick: the framework is still taking them out.
        runTaskLater(() -> {
            if (getState() != GameState.RUNNING) return;
            if (tiebreak) {
                contenders.removeIf(key -> membersOf(key).isEmpty());
                if (contenders.size() <= 1) {
                    decide(contenders);
                    return;
                }
            }
            if (phase == Phase.ANSWERING && round != null && round.everyoneAnswered(getPlayerIds())) reveal();
        }, 1L);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Questions
    // ------------------------------------------------------------------------------------------------------------

    /** Gets questions in the background; they're used when they arrive (if this is still the same match). */
    private void fetch(int amount) {
        phase = Phase.LOADING;
        phaseTicks = 0;
        setBar(Utils.mm("<gray>Getting questions..."), 1f, BossBar.Color.PURPLE);
        int thisRun = run;
        api(plugin).fetch(amount, getIntOption("category"), getStringOption("difficulty"), getStringOption("question-type"))
                .whenComplete((list, error) -> {
                    if (!plugin.isEnabled()) return;
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> arrived(thisRun, list, error));
                    } catch (RuntimeException ignored) {
                        // The plugin is shutting down.
                    }
                });
    }

    private void arrived(int forRun, @Nullable List<TriviaApi.Question> list, @Nullable Throwable error) {
        if (forRun != run || getState() != GameState.RUNNING) return;
        if (error != null || list == null || list.isEmpty()) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            String reason = cause instanceof TriviaApi.TriviaException trivia ? trivia.getMessage() : "the trivia service didn't send any";
            if (cause != null && !(cause instanceof TriviaApi.TriviaException)) {
                plugin.getSLF4JLogger().warn("Trivia: couldn't get questions", cause);
            }
            if (tiebreak) {
                broadcast(Utils.mm("<yellow>Couldn't get more questions (<reason>), so the tied players share the win.",
                        Placeholder.unparsed("reason", reason)));
                decide(contenders);
            } else {
                failed = true;
                broadcast(Utils.mm("<red>Couldn't get any questions: <reason>. Try again in a moment.", Placeholder.unparsed("reason", reason)));
                end();
            }
            return;
        }
        questions.addAll(list);
        if (phase == Phase.LOADING) nextQuestion();
    }

    /** The next question: a main one, or a tiebreaker once they've all been asked and it's a tie. */
    private void nextQuestion() {
        if (!tiebreak && asked >= total) {
            finishQuestions();
            return;
        }
        if (questions.isEmpty()) {
            fetch(MORE);
            return;
        }
        TriviaApi.Question question = questions.removeFirst();
        if (!tiebreak) asked++;
        else tiebreaks++;
        Set<UUID> eligible = null;
        if (tiebreak) {
            eligible = new LinkedHashSet<>();
            for (Object key : contenders) eligible.addAll(membersOf(key));
        }
        round = new TriviaRound(++roundIds, question, eligible);
        phase = Phase.READING;
        phaseTicks = 0;
        show(round);
        if (getIntOption("read-seconds") == 0) openAnswers();
    }

    /** Puts the question up: in chat with clickable answers, as a title, and as answer blocks in the hotbar. */
    private void show(TriviaRound round) {
        TriviaApi.Question question = round.question;
        String heading = tiebreak ? "Tiebreaker " + tiebreaks : "Question " + asked + "/" + total;
        String difficulty = question.difficulty().isEmpty() ? "" : Character.toUpperCase(question.difficulty().charAt(0)) + question.difficulty().substring(1);
        audience().sendMessage(Component.empty());
        audience().sendMessage(Utils.mm("<dark_gray>--- <light_purple><bold><heading></bold> <dark_gray>· <gray><difficulty><dark_gray> ---",
                Placeholder.unparsed("heading", heading), Placeholder.unparsed("difficulty", difficulty)));
        audience().sendMessage(Component.text(question.text(), NamedTextColor.WHITE, TextDecoration.BOLD));
        long lifetime = getIntOption("read-seconds") + getIntOption("answer-seconds") + 10L;
        for (int i = 0; i < question.answers().size(); i++) {
            int answer = i;
            Component line = Component.text(" ")
                    .append(Component.text("[" + label(question, i) + "]", color(question, i), TextDecoration.BOLD))
                    .append(Component.text(" " + question.answers().get(i), NamedTextColor.WHITE))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to answer " + label(question, i), NamedTextColor.GRAY)))
                    .clickEvent(ClickEvent.callback(clicker -> clicked(clicker, round.id, answer),
                            ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofSeconds(lifetime)).build()));
            audience().sendMessage(line);
        }
        if (round.isTiebreak()) {
            audience().sendMessage(Utils.mm("<gray>Only <names> answer this one.", Placeholder.component("names", names(contenders))));
        }
        for (Player player : getPlayers()) {
            Utils.title(player, Utils.mm("<light_purple><heading>", Placeholder.unparsed("heading", heading)),
                    Utils.mm(round.mayAnswer(player.getUniqueId()) ? "<gray>Read the question in chat" : "<gray>Watch the tied players answer"), 5, 40, 10);
            if (round.mayAnswer(player.getUniqueId())) giveAnswers(player, round);
            else clearAnswers(player);
        }
        Utils.playSound(audience(), "block.note_block.chime", 0.8f, 1.2f);
    }

    private void openAnswers() {
        if (round == null) return;
        round.open();
        phase = Phase.ANSWERING;
        phaseTicks = 0;
        for (Player player : getPlayers()) {
            if (!round.mayAnswer(player.getUniqueId())) continue;
            player.sendActionBar(Utils.mm("<green>Answer now!"));
            Utils.playPling(player, 1.5f);
        }
    }

    /** An answer clicked in chat. */
    private void clicked(Audience clicker, int roundId, int answer) {
        if (!(clicker instanceof Player player)) return;
        if (Bukkit.isPrimaryThread()) pick(player, roundId, answer);
        else Bukkit.getScheduler().runTask(plugin, () -> pick(player, roundId, answer));
    }

    private void pick(Player player, int roundId, int answer) {
        if (getState() != GameState.RUNNING || !isPlaying(player)) return;
        TriviaRound current = round;
        if (current == null || current.id != roundId) {
            player.sendActionBar(Utils.mm("<gray>That question is over."));
            return;
        }
        switch (current.pick(player.getUniqueId(), answer)) {
            case OK -> {
                player.sendActionBar(Utils.mm("<gray>Locked in: <answer>", Placeholder.component("answer", answerText(current.question, answer))));
                Utils.playSound(player, "ui.button.click", 0.8f, 1.2f);
                lockedIn(player, current, answer);
                if (current.everyoneAnswered(getPlayerIds())) reveal();
            }
            case NOT_OPEN -> {
                if (phase == Phase.READING) {
                    long left = Math.max(1, (getIntOption("read-seconds") * 20L - phaseTicks + 19) / 20);
                    player.sendActionBar(Utils.mm("<yellow>Answers open in <left>s.", Placeholder.unparsed("left", String.valueOf(left))));
                }
            }
            case ALREADY_ANSWERED -> player.sendActionBar(Utils.mm("<gray>You already locked in: <answer>",
                    Placeholder.component("answer", answerText(current.question, current.pickOf(player.getUniqueId())))));
            case NOT_IN_TIEBREAK -> player.sendActionBar(Utils.mm("<gray>Only the tied players answer the tiebreaker."));
            case NO_SUCH_ANSWER -> {
            }
        }
    }

    /** Time's up (or everyone answered): shows the right answer and who got it. */
    private void reveal() {
        TriviaRound current = round;
        if (current == null || phase == Phase.REVEAL) return;
        current.close();
        phase = Phase.REVEAL;
        phaseTicks = 0;
        TriviaApi.Question question = current.question;
        Component answer = answerText(question, question.correct());
        int right = 0, answering = 0;
        List<Component> rightNames = new ArrayList<>();
        for (Player player : getPlayers()) {
            UUID id = player.getUniqueId();
            clearAnswers(player);
            if (!current.mayAnswer(id)) {
                Utils.title(player, Utils.mm("<gray>The answer:"), answer, 0, 50, 10);
                continue;
            }
            answering++;
            boolean correct = current.isCorrect(id);
            if (correct) {
                right++;
                rightNames.add(player.name());
                if (!current.isTiebreak()) points.merge(id, 1, Integer::sum);
            }
            Integer picked = current.pickOf(id);
            Utils.title(player, Utils.mm(correct ? "<green>Correct!" : picked == null ? "<gray>Time's up" : "<red>Wrong"),
                    correct ? Utils.mm(current.isTiebreak() ? "<gray>Nice one." : "<gray>That's <white><points></white> right",
                            Placeholder.unparsed("points", String.valueOf(points.getOrDefault(id, 0))))
                            : Utils.mm("<gray>It was <answer>", Placeholder.component("answer", answer)), 0, 50, 10);
            Utils.playSound(player, correct ? "entity.player.levelup" : "entity.villager.no", 0.8f, 1f);
        }
        audience().sendMessage(Utils.mm("<green>✔ The answer was <answer><gray>.", Placeholder.component("answer", answer)));
        if (!solo) {
            audience().sendMessage(current.isTiebreak()
                    ? Utils.mm(rightNames.isEmpty() ? "<gray>Nobody in the tiebreaker got it." : "<gray>Got it: <names>",
                    Placeholder.component("names", Component.join(JoinConfiguration.commas(true), rightNames)))
                    : Utils.mm("<gray><right> of <count> got it right.", Placeholder.unparsed("right", String.valueOf(right)),
                    Placeholder.unparsed("count", String.valueOf(answering))));
            if (!current.isTiebreak() && asked % STANDINGS_EVERY == 0 && asked < total) showStandings();
        }
    }

    /** After the reveal: the next question, or the tiebreaker's verdict. */
    private void advance() {
        if (tiebreak && round != null) {
            Map<Object, Double> roundScores = new LinkedHashMap<>();
            for (Object key : contenders) {
                List<UUID> members = membersOf(key);
                if (members.isEmpty()) continue;
                int right = 0;
                for (UUID member : members) {
                    if (round.isCorrect(member)) right++;
                }
                roundScores.put(key, (double) right / members.size());
            }
            List<Object> through = Standings.best(roundScores);
            if (through.size() <= 1) {
                decide(through);
                return;
            }
            if (through.size() < contenders.size()) {
                broadcast(Utils.mm("<yellow>Still tied: <names>. Another tiebreaker!", Placeholder.component("names", names(through))));
            } else {
                broadcast(Utils.mm("<yellow>Still tied. Another tiebreaker!"));
            }
            contenders.clear();
            contenders.addAll(through);
        }
        nextQuestion();
    }

    /** Every main question has been asked: a winner, or tiebreakers. */
    private void finishQuestions() {
        round = null;
        if (solo) {
            decide(List.copyOf(entrants()));
            return;
        }
        List<Object> leaders = Standings.best(scores());
        if (leaders.size() <= 1) {
            decide(leaders);
            return;
        }
        tiebreak = true;
        contenders.clear();
        contenders.addAll(leaders);
        broadcast(Utils.mm("<gold>It's a tie between <names> on <score>! <yellow>Tiebreakers until one is ahead.",
                Placeholder.component("names", names(leaders)), Placeholder.unparsed("score", scoreText(scores().get(leaders.getFirst())))));
        nextQuestion();
    }

    private void decide(List<Object> won) {
        if (decided) return;
        decided = true;
        winners.clear();
        winners.addAll(won);
        end();
    }

    private void tick() {
        if (getState() != GameState.RUNNING) return;
        phaseTicks++;
        switch (phase) {
            case LOADING -> {
            }
            case READING -> {
                int read = getIntOption("read-seconds") * 20;
                setBar(Utils.mm("<yellow>Read the question <gray>· answers open in <white><left>s",
                        Placeholder.unparsed("left", String.valueOf(Math.max(1, (read - phaseTicks + 19) / 20)))), 1f, BossBar.Color.YELLOW);
                if (phaseTicks >= read) openAnswers();
            }
            case ANSWERING -> {
                int answer = getIntOption("answer-seconds") * 20;
                int left = answer - phaseTicks;
                setBar(Utils.mm("<green>Answer! <gray>· <white><left>s</white> left",
                                Placeholder.unparsed("left", String.valueOf(Math.max(0, (left + 19) / 20)))),
                        Math.clamp((float) left / answer, 0f, 1f), left <= 100 ? BossBar.Color.RED : BossBar.Color.GREEN);
                if (left <= 0) reveal();
            }
            case REVEAL -> {
                setBar(Utils.mm(tiebreak ? "<gold>Tiebreaker" : "<light_purple>Question <asked>/<total>",
                        Placeholder.unparsed("asked", String.valueOf(asked)), Placeholder.unparsed("total", String.valueOf(total))), 1f, BossBar.Color.PURPLE);
                if (phaseTicks >= getIntOption("reveal-seconds") * 20) advance();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Scores
    // ------------------------------------------------------------------------------------------------------------

    /** What's scored: each player (FFA, singleplayer) or each team, with anyone still in it. */
    private List<Object> entrants() {
        List<Object> list = new ArrayList<>();
        if (getMode().isTeams()) {
            for (GameTeam team : getTeams()) {
                if (!getPlayers(team).isEmpty()) list.add(team);
            }
        } else {
            list.addAll(getPlayerIds());
        }
        return list;
    }

    /** Scores from the main questions: points per player, or each team's average (see {@link Standings#teamScore}). */
    private Map<Object, Double> scores() {
        Map<Object, Double> scores = new LinkedHashMap<>();
        for (Object key : entrants()) {
            List<Integer> members = new ArrayList<>();
            for (UUID member : membersOf(key)) members.add(points.getOrDefault(member, 0));
            scores.put(key, Standings.teamScore(members));
        }
        return scores;
    }

    /** The players still in the match behind an entrant. */
    private List<UUID> membersOf(Object key) {
        List<UUID> members = new ArrayList<>();
        if (key instanceof GameTeam team) {
            for (Player player : getPlayers(team)) members.add(player.getUniqueId());
        } else if (key instanceof UUID id && getPlayerIds().contains(id)) {
            members.add(id);
        }
        return members;
    }

    private @Nullable Object keyOf(Player player) {
        if (!getMode().isTeams()) return player.getUniqueId();
        return getTeam(player);
    }

    private Component nameOf(Object key) {
        if (key instanceof GameTeam team) return team.getName();
        if (key instanceof UUID id) {
            Player player = Bukkit.getPlayer(id);
            String name = player != null ? player.getName() : Bukkit.getOfflinePlayer(id).getName();
            return Component.text(name == null ? "?" : name);
        }
        return Component.text("?");
    }

    private Component names(List<Object> keys) {
        return Component.join(JoinConfiguration.commas(true), keys.stream().map(this::nameOf).toList());
    }

    /** {@code 15/20}, or a team's average, {@code 7.5/20 avg}. */
    private String scoreText(double score) {
        boolean whole = Math.abs(score - Math.rint(score)) < 1e-9;
        String number = whole ? String.valueOf((long) Math.rint(score)) : String.format(Locale.ROOT, "%.1f", score);
        return number + "/" + total + (getMode().isTeams() ? " avg" : "");
    }

    private void showStandings() {
        Map<Object, Double> scores = scores();
        Map<Object, Integer> places = Standings.places(scores, List.of());
        List<Component> lines = new ArrayList<>();
        for (Map.Entry<Object, Integer> entry : places.entrySet()) {
            if (lines.size() >= 3) break;
            lines.add(Utils.mm("<gray>#<place> <white><name></white> <score>", Placeholder.unparsed("place", String.valueOf(entry.getValue())),
                    Placeholder.component("name", nameOf(entry.getKey())), Placeholder.unparsed("score", scoreText(scores.get(entry.getKey())))));
        }
        audience().sendMessage(Utils.mm("<light_purple>Standings: <lines>",
                Placeholder.component("lines", Component.join(JoinConfiguration.separator(Utils.mm("<dark_gray>, ")), lines))));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Answer blocks and display
    // ------------------------------------------------------------------------------------------------------------

    private static String label(TriviaApi.Question question, int index) {
        return question.trueFalse() ? question.answers().get(index) : LETTERS[index];
    }

    private static NamedTextColor color(TriviaApi.Question question, int index) {
        if (question.trueFalse()) return index == 0 ? NamedTextColor.GREEN : NamedTextColor.RED;
        return COLORS[index];
    }

    private static Material block(TriviaApi.Question question, int index) {
        if (question.trueFalse()) return index == 0 ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        return BLOCKS[index];
    }

    /** "B: London" (or just "True"), in the answer's colour. */
    private static Component answerText(TriviaApi.Question question, @Nullable Integer index) {
        if (index == null) return Component.text("?");
        Component label = Component.text(label(question, index), color(question, index), TextDecoration.BOLD);
        if (question.trueFalse()) return label;
        return label.append(Component.text(": " + question.answers().get(index), NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false));
    }

    private static ItemStack answerBlock(TriviaRound round, int index, boolean picked) {
        TriviaApi.Question question = round.question;
        return ItemBuilder.of(block(question, index))
                .name(answerText(question, index).decoration(TextDecoration.ITALIC, false))
                .lore(List.of(Component.text(picked ? "Locked in." : "Right-click to answer.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)))
                .glow(picked)
                .meta(meta -> {
                    PersistentDataContainer data = meta.getPersistentDataContainer();
                    data.set(ANSWER_KEY, PersistentDataType.INTEGER, index);
                    data.set(ROUND_KEY, PersistentDataType.INTEGER, round.id);
                })
                .build();
    }

    /** The answer blocks, in hotbar slots 1-4 (number keys 1-4 for A-D). */
    private void giveAnswers(Player player, TriviaRound round) {
        clearAnswers(player);
        for (int i = 0; i < round.question.answers().size(); i++) player.getInventory().setItem(i, answerBlock(round, i, false));
    }

    /** Only the picked block stays, glowing. */
    private void lockedIn(Player player, TriviaRound round, int answer) {
        clearAnswers(player);
        player.getInventory().setItem(answer, answerBlock(round, answer, true));
    }

    private static void clearAnswers(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(ANSWER_KEY)) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    private void setBar(Component name, float progress, BossBar.Color color) {
        if (bar == null) return;
        bar.name(name);
        bar.progress(progress);
        bar.color(color);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Events (they fire for every player on the server, so each checks isPlaying first)
    // ------------------------------------------------------------------------------------------------------------

    /** Right-clicking an answer block answers. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!isPlaying(player) || item == null || !item.hasItemMeta() || event.getHand() != EquipmentSlot.HAND) return;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        Integer answer = data.get(ANSWER_KEY, PersistentDataType.INTEGER);
        Integer roundId = data.get(ROUND_KEY, PersistentDataType.INTEGER);
        if (answer == null || roundId == null) return;
        event.setCancelled(true);
        if (event.getAction() != Action.PHYSICAL) pick(player, roundId, answer);
    }

    /** Nothing hurts during a quiz, wherever the players are standing. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isPlaying(player)
                && event.getCause() != EntityDamageEvent.DamageCause.KILL && event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHunger(FoodLevelChangeEvent event) {
        if (isPlaying(event.getEntity()) && event.getFoodLevel() < event.getEntity().getFoodLevel()) event.setCancelled(true);
    }
}
