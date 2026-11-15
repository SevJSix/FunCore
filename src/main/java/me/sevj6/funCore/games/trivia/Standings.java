package me.sevj6.funCore.games.trivia;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trivia scores: who leads, places with ties shared, and team scores. Generic over what's scored (a player, or a team),
 * Bukkit-free so it's unit-tested.
 */
final class Standings {

    /** Scores this close count as equal (team averages are fractions). */
    private static final double EPSILON = 1e-9;

    private Standings() {
    }

    /**
     * A team's score: its members' correct answers, averaged, so a team that's a player short isn't behind for it.
     * Only members still in the match count: someone who left no longer pulls the average either way. No members: 0.
     */
    static double teamScore(Collection<Integer> membersCorrect) {
        if (membersCorrect.isEmpty()) return 0;
        double total = 0;
        for (int correct : membersCorrect) total += correct;
        return total / membersCorrect.size();
    }

    /** Everyone with the highest score (several if they're tied; none if there are no scores). */
    static <K> List<K> best(Map<K, Double> scores) {
        double top = Double.NEGATIVE_INFINITY;
        for (double score : scores.values()) top = Math.max(top, score);
        List<K> best = new ArrayList<>();
        for (Map.Entry<K, Double> entry : scores.entrySet()) {
            if (entry.getValue() >= top - EPSILON) best.add(entry.getKey());
        }
        return best;
    }

    /**
     * Places, best first: the winners first (place 1), then everyone else by score, sharing a place when tied
     * (two on 1st... then 3rd).
     */
    static <K> Map<K, Integer> places(Map<K, Double> scores, Collection<K> winners) {
        List<K> order = new ArrayList<>(scores.keySet());
        order.sort(Comparator.<K>comparingInt(key -> winners.contains(key) ? 0 : 1)
                .thenComparing(key -> -scores.get(key)));
        Map<K, Integer> places = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            K key = order.get(i);
            if (winners.contains(key)) {
                places.put(key, 1);
                continue;
            }
            // Tied with the one before (and neither won): the same place.
            K previous = i > 0 ? order.get(i - 1) : null;
            boolean tied = previous != null && !winners.contains(previous)
                    && Math.abs(scores.get(previous) - scores.get(key)) < EPSILON;
            places.put(key, tied ? places.get(previous) : i + 1);
        }
        return places;
    }
}
