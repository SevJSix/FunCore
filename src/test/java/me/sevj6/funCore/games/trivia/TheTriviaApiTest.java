package me.sevj6.funCore.games.trivia;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TheTriviaApiTest {

    /** A real reply (trimmed), plus one with too few wrong answers. */
    private static final String REPLY = """
            [{"category":"general_knowledge","id":"6239f80b9129041bf3925cc1","correctAnswer":"Echo",
              "incorrectAnswers":["Elton","Elbow","Enable"],
              "question":{"text":"What word is used in the NATO Phonetic Alphabet for the letter E?"},
              "tags":["category:general-knowledge"],"type":"text_choice","difficulty":"easy","regions":[],"isNiche":false},
             {"category":"general_knowledge","id":"x","correctAnswer":"Yes","incorrectAnswers":["No"],
              "question":{"text":"Odd one?"},"type":"text_choice","difficulty":"hard"}]
            """;

    @Test
    void readsQuestionsWithFourAnswers() {
        List<TheTriviaApi.Parsed> parsed = TheTriviaApi.parse(REPLY, new Random(1));
        assertEquals(1, parsed.size(), "the one with only 2 answers is skipped");
        TriviaApi.Question question = parsed.getFirst().question();
        assertEquals("6239f80b9129041bf3925cc1", parsed.getFirst().id());
        assertEquals("What word is used in the NATO Phonetic Alphabet for the letter E?", question.text());
        assertEquals("Echo", question.answers().get(question.correct()));
        assertTrue(question.answers().containsAll(List.of("Echo", "Elton", "Elbow", "Enable")));
        assertFalse(question.trueFalse());
        assertEquals("easy", question.difficulty());
        assertEquals(TheTriviaApi.NAME, question.source());
    }

    @Test
    void mapsOpenTriviaCategories() {
        assertEquals("", TheTriviaApi.category(0), "any");
        assertEquals("general_knowledge", TheTriviaApi.category(9));
        assertEquals("film_and_tv", TheTriviaApi.category(14));
        assertNull(TheTriviaApi.category(15), "video games: Open Trivia DB alone");
        assertTrue(TheTriviaApi.supports(9, "any"));
        assertTrue(TheTriviaApi.supports(9, "multiple"));
        assertFalse(TheTriviaApi.supports(9, "boolean"), "it has no true/false questions");
        assertFalse(TheTriviaApi.supports(31, "any"));
    }

    @Test
    void buildsTheUrl() {
        assertEquals("https://the-trivia-api.com/v2/questions?limit=20&types=text_choice&categories=general_knowledge",
                TheTriviaApi.questionsUrl(20, "general_knowledge", "any"));
        assertEquals("https://the-trivia-api.com/v2/questions?limit=5&types=text_choice&difficulties=hard",
                TheTriviaApi.questionsUrl(5, "", "hard"));
    }

    @Test
    void choosesSources() {
        assertEquals(List.of(TriviaApi.NAME, TheTriviaApi.NAME), QuestionSources.used(QuestionSources.BOTH, 9, "any"));
        assertEquals(List.of(TriviaApi.NAME), QuestionSources.used(QuestionSources.BOTH, 9, "boolean"), "true/false: Open Trivia DB only");
        assertEquals(List.of(TriviaApi.NAME), QuestionSources.used(QuestionSources.BOTH, 15, "any"));
        assertEquals(List.of(TriviaApi.NAME), QuestionSources.used(QuestionSources.OPEN_TRIVIA_DB, 9, "any"));
        assertEquals(List.of(TheTriviaApi.NAME), QuestionSources.used(QuestionSources.THE_TRIVIA_API, 9, "multiple"));
        assertEquals(List.of(), QuestionSources.used(QuestionSources.THE_TRIVIA_API, 9, "boolean"));
    }

    @Test
    void checksTheSourceOption() {
        assertEquals(QuestionSources.BOTH, QuestionSources.checkSource("Mixed"));
        assertEquals(QuestionSources.OPEN_TRIVIA_DB, QuestionSources.checkSource("OpenTDB"));
        assertEquals(QuestionSources.THE_TRIVIA_API, QuestionSources.checkSource("the_trivia_api"));
        assertThrows(IllegalArgumentException.class, () -> QuestionSources.checkSource("wikipedia"));
    }
}
