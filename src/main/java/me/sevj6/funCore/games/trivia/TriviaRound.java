package me.sevj6.funCore.games.trivia;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One question being asked: who may answer, and what each player locked in. Answers only count while it's open (after
 * the reading pause, before the reveal), and a player's first pick is final. Bukkit-free, so it's unit-tested.
 */
final class TriviaRound {

    /** What happened to a pick. */
    enum Pick {
        /** Locked in. */
        OK,
        /** Too early (still reading) or too late (revealed). */
        NOT_OPEN,
        /** They already locked in an answer. */
        ALREADY_ANSWERED,
        /** A tiebreaker, and they aren't one of the tied players. */
        NOT_IN_TIEBREAK,
        /** Not one of this question's answers. */
        NO_SUCH_ANSWER
    }

    /** Counts up for every round of a match, so a click on an old question's answer is recognised and ignored. */
    final int id;
    final TriviaApi.Question question;
    /** Who may answer: everyone in the match ({@code null}), or the tied players in a tiebreaker. */
    private final @Nullable Set<UUID> eligible;
    private boolean open, closed;
    private final Map<UUID, Integer> picks = new LinkedHashMap<>();

    TriviaRound(int id, TriviaApi.Question question, @Nullable Set<UUID> eligible) {
        this.id = id;
        this.question = question;
        this.eligible = eligible == null ? null : Set.copyOf(eligible);
    }

    /** The reading pause is over: answers count from now. */
    void open() {
        if (!closed) open = true;
    }

    /** Revealed: no more answers. */
    void close() {
        open = false;
        closed = true;
    }

    boolean isOpen() {
        return open;
    }

    boolean isTiebreak() {
        return eligible != null;
    }

    boolean mayAnswer(UUID player) {
        return eligible == null || eligible.contains(player);
    }

    Pick pick(UUID player, int answer) {
        if (!mayAnswer(player)) return Pick.NOT_IN_TIEBREAK;
        if (answer < 0 || answer >= question.answers().size()) return Pick.NO_SUCH_ANSWER;
        if (!open) return Pick.NOT_OPEN;
        if (picks.containsKey(player)) return Pick.ALREADY_ANSWERED;
        picks.put(player, answer);
        return Pick.OK;
    }

    /** What a player picked, or {@code null} if they didn't. */
    @Nullable Integer pickOf(UUID player) {
        return picks.get(player);
    }

    boolean isCorrect(UUID player) {
        Integer pick = picks.get(player);
        return pick != null && pick == question.correct();
    }

    Map<UUID, Integer> picks() {
        return Collections.unmodifiableMap(picks);
    }

    /**
     * Whether everyone still here who may answer has answered (so the answer can be revealed early). At least one
     * player must be able to answer.
     *
     * @param present the players still in the match
     */
    boolean everyoneAnswered(Collection<UUID> present) {
        boolean anyone = false;
        for (UUID player : present) {
            if (!mayAnswer(player)) continue;
            anyone = true;
            if (!picks.containsKey(player)) return false;
        }
        return anyone;
    }

    /**
     * Whether nobody still here who may answer picked anything (a wrong pick is still an answer).
     *
     * @param present the players still in the match
     */
    boolean nobodyAnswered(Collection<UUID> present) {
        for (UUID player : present) {
            if (mayAnswer(player) && picks.containsKey(player)) return false;
        }
        return true;
    }
}
