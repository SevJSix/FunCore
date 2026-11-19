package me.sevj6.funCore.arena;

import java.util.Set;

/**
 * Which mob spawns players cause (spawn eggs, thrown eggs, building golems, breeding, buckets, ...) as opposed to the
 * world (natural spawning, spawners, raids, patrols, lightning, ...), for {@link Arena#isPlayerMobsAllowed()}. By the
 * spawn reason's name; anything not listed here counts as the world's, so a new or unclear reason stays blocked.
 */
public final class MobSpawnRules {

    /** Spawn reasons (by name) that a player's own action causes. */
    static final Set<String> PLAYER_MADE = Set.of(
            "SPAWNER_EGG", "DISPENSE_EGG", "EGG", "BUCKET",
            "BUILD_SNOWMAN", "BUILD_IRONGOLEM", "BUILD_COPPERGOLEM", "BUILD_WITHER",
            "BREEDING", "OCELOT_BABY", "SLIME_SPLIT", "REINFORCEMENTS", "INFECTION", "CURED", "DROWNED", "FROZEN",
            "PIGLIN_ZOMBIFIED", "METAMORPHOSIS", "SHEARED", "SPELL", "DUPLICATION", "SHOULDER_ENTITY",
            "ENDER_PEARL", "SILVERFISH_BLOCK", "POTION_EFFECT", "ENCHANTMENT", "COMMAND");

    private MobSpawnRules() {
    }

    /** Whether a spawn with this reason (e.g. {@code "SPAWNER_EGG"}) comes from something a player did. */
    public static boolean isPlayerMade(String reason) {
        return PLAYER_MADE.contains(reason);
    }
}
