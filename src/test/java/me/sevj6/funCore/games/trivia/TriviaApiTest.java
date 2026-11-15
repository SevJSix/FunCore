package me.sevj6.funCore.games.trivia;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriviaApiTest {

    /** A real reply (url3986 encoding), plus a true/false question. */
    private static final String REPLY = """
            {"response_code":0,"results":[
             {"type":"multiple","difficulty":"easy","category":"General%20Knowledge",
              "question":"What%20nuts%20are%20used%20in%20the%20production%20of%20marzipan%3F",
              "correct_answer":"Almonds","incorrect_answers":["Peanuts","Walnuts","Pistachios"]},
             {"type":"boolean","difficulty":"medium","category":"General%20Knowledge",
              "question":"%22Rock%20%26%20Roll%22%20isn%27t%20a%20genre%2B%20of%20music.",
              "correct_answer":"False","incorrect_answers":["True"]}]}
            """;

    @Test
    void readsMultipleChoice() {
        TriviaApi.Question question = TriviaApi.parse(REPLY, new Random(1)).getFirst();
        assertEquals("What nuts are used in the production of marzipan?", question.text());
        assertEquals(4, question.answers().size());
        assertEquals("Almonds", question.answers().get(question.correct()), "the right answer is where it says");
        assertTrue(question.answers().containsAll(List.of("Almonds", "Peanuts", "Walnuts", "Pistachios")));
        assertFalse(question.trueFalse());
        assertEquals("easy", question.difficulty());
    }

    @Test
    void answersAreShuffled() {
        boolean moved = false;
        for (int seed = 0; seed < 20 && !moved; seed++) {
            moved = TriviaApi.parse(REPLY, new Random(seed)).getFirst().correct() != 0;
        }
        assertTrue(moved, "the right answer isn't always first");
    }

    @Test
    void readsTrueFalseInAFixedOrder() {
        TriviaApi.Question question = TriviaApi.parse(REPLY, new Random(1)).get(1);
        assertTrue(question.trueFalse());
        assertEquals(List.of("True", "False"), question.answers());
        assertEquals(1, question.correct(), "False");
        assertEquals("\"Rock & Roll\" isn't a genre+ of music.", question.text(), "decoded, a + included");
    }

    @Test
    void readsResponseCodes() {
        assertEquals(0, TriviaApi.responseCode(REPLY));
        assertEquals(5, TriviaApi.responseCode("{\"response_code\":5,\"result\":[]}"), "rate limited (sent with HTTP 429)");
        assertEquals(-1, TriviaApi.responseCode("<html>Bad Gateway</html>"));
        assertEquals(-1, TriviaApi.responseCode("{}"));
    }

    @Test
    void buildsTheUrl() {
        assertEquals("https://opentdb.com/api.php?amount=25&category=9&encode=url3986",
                TriviaApi.questionsUrl(25, 9, "any", "any", null));
        assertEquals("https://opentdb.com/api.php?amount=5&difficulty=hard&type=boolean&encode=url3986&token=abc",
                TriviaApi.questionsUrl(5, 0, "hard", "boolean", "abc"));
    }
}
