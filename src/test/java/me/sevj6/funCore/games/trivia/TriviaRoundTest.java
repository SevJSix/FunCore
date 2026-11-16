package me.sevj6.funCore.games.trivia;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriviaRoundTest {

    private static final TriviaApi.Question QUESTION =
            new TriviaApi.Question("Capital of France?", List.of("Berlin", "Paris", "Rome", "Madrid"), 1, false, "easy", "test");
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();

    @Test
    void answersOnlyCountWhileOpen() {
        TriviaRound round = new TriviaRound(1, QUESTION, null);
        assertEquals(TriviaRound.Pick.NOT_OPEN, round.pick(A, 1), "still reading");
        round.open();
        assertEquals(TriviaRound.Pick.OK, round.pick(A, 1));
        round.close();
        assertEquals(TriviaRound.Pick.NOT_OPEN, round.pick(B, 1), "revealed");
        round.open();
        assertFalse(round.isOpen(), "a revealed question never opens again");
    }

    @Test
    void theFirstPickIsFinal() {
        TriviaRound round = new TriviaRound(1, QUESTION, null);
        round.open();
        assertEquals(TriviaRound.Pick.OK, round.pick(A, 0));
        assertEquals(TriviaRound.Pick.ALREADY_ANSWERED, round.pick(A, 1));
        assertEquals(0, round.pickOf(A));
        assertFalse(round.isCorrect(A));
        assertEquals(TriviaRound.Pick.NO_SUCH_ANSWER, round.pick(B, 4));
        assertNull(round.pickOf(B));
    }

    @Test
    void onlyTheTiedAnswerATiebreaker() {
        TriviaRound round = new TriviaRound(2, QUESTION, Set.of(A, B));
        round.open();
        assertTrue(round.isTiebreak());
        assertEquals(TriviaRound.Pick.NOT_IN_TIEBREAK, round.pick(C, 1));
        assertEquals(TriviaRound.Pick.OK, round.pick(A, 1));
        assertTrue(round.isCorrect(A));
    }

    @Test
    void everyoneAnsweredCountsOnlyWhoMayAndIsStillHere() {
        TriviaRound round = new TriviaRound(3, QUESTION, Set.of(A, B));
        round.open();
        round.pick(A, 1);
        assertFalse(round.everyoneAnswered(List.of(A, B, C)), "B hasn't");
        assertTrue(round.everyoneAnswered(List.of(A, C)), "B left; C isn't in the tiebreaker");
        assertFalse(round.everyoneAnswered(List.of(C)), "nobody left who may answer");

        TriviaRound everyone = new TriviaRound(4, QUESTION, null);
        everyone.open();
        everyone.pick(A, 2);
        everyone.pick(B, 1);
        assertTrue(everyone.everyoneAnswered(List.of(A, B)));
    }

    @Test
    void nobodyAnsweredIgnoresOutsidersAndCountsWrongPicks() {
        TriviaRound round = new TriviaRound(5, QUESTION, Set.of(A, B));
        round.open();
        round.pick(C, 1);
        assertTrue(round.nobodyAnswered(List.of(A, B, C)), "C isn't in the tiebreaker");
        round.pick(A, 0);
        assertFalse(round.nobodyAnswered(List.of(A, B, C)), "a wrong pick is still an answer");
        assertTrue(round.nobodyAnswered(List.of(B, C)), "A left");
    }
}
