package me.sevj6.funCore.games.trivia;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandingsTest {

    @Test
    void teamsScoreTheirAverage() {
        assertEquals(7.5, Standings.teamScore(List.of(5, 10)));
        assertEquals(8.0, Standings.teamScore(List.of(8)), "a team a player short isn't behind for it");
        assertEquals(0.0, Standings.teamScore(List.of()), "everyone left");
    }

    @Test
    void bestFindsTies() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("ann", 15.0);
        scores.put("bob", 17.0);
        scores.put("cat", 17.0);
        assertEquals(List.of("bob", "cat"), Standings.best(scores));
        scores.put("cat", 16.0);
        assertEquals(List.of("bob"), Standings.best(scores));
        assertTrue(Standings.best(Map.<String, Double>of()).isEmpty());
    }

    @Test
    void tiebreakersKeepEveryoneWhenNobodyGetsIt() {
        // Each tied player's result on a tiebreaker: 1 right, 0 wrong.
        assertEquals(List.of("bob", "cat"), Standings.best(ordered("bob", 0.0, "cat", 0.0)), "nobody got it: still tied");
        assertEquals(List.of("cat"), Standings.best(ordered("bob", 0.0, "cat", 1.0)));
        // Teams: the share of members who got it.
        assertEquals(List.of("red"), Standings.best(ordered("red", 1.0, "blue", 0.5)));
    }

    @Test
    void placesPutTheWinnerFirstAndShareTies() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("ann", 12.0);
        scores.put("bob", 17.0);
        scores.put("cat", 17.0);
        scores.put("dan", 12.0);
        scores.put("eve", 9.0);
        // Cat won the tiebreaker against Bob.
        Map<String, Integer> places = Standings.places(scores, List.of("cat"));
        assertEquals(List.of("cat", "bob", "ann", "dan", "eve"), List.copyOf(places.keySet()));
        assertEquals(1, places.get("cat"));
        assertEquals(2, places.get("bob"));
        assertEquals(3, places.get("ann"));
        assertEquals(3, places.get("dan"), "tied with Ann");
        assertEquals(5, places.get("eve"));
    }

    private static Map<String, Double> ordered(String a, double x, String b, double y) {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put(a, x);
        map.put(b, y);
        return map;
    }
}
