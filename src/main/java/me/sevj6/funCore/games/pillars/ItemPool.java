package me.sevj6.funCore.games.pillars;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which items Pillars hands out: any item in the game (as the server lists them, so only ones it has switched on),
 * minus a ban list. Works on item names ({@code "diamond_sword"}), so it's testable without a server.
 */
final class ItemPool {

    /**
     * Never handed out: admin and creative-only blocks, items survival can't get, boss and world-wrecking spawn eggs,
     * and things that would reach outside the match (ender chests hold players' real items; beds and anchors set
     * spawn points). Names the server doesn't know are just skipped.
     */
    static final Set<String> BUILT_IN_BANS = Set.of(
            "air", "cave_air", "void_air",
            "command_block", "chain_command_block", "repeating_command_block", "command_block_minecart",
            "structure_block", "structure_void", "jigsaw", "barrier", "light", "debug_stick", "knowledge_book",
            "test_block", "test_instance_block",
            "bedrock", "end_portal_frame", "spawner", "trial_spawner", "vault", "reinforced_deepslate",
            "budding_amethyst", "petrified_oak_slab", "farmland", "dirt_path", "chorus_plant", "frogspawn",
            "infested_stone", "infested_cobblestone", "infested_stone_bricks", "infested_mossy_stone_bricks",
            "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks", "infested_deepslate",
            "ender_dragon_spawn_egg", "wither_spawn_egg", "warden_spawn_egg", "elder_guardian_spawn_egg",
            "wither_skeleton_skull", "ender_chest",
            "white_bed", "orange_bed", "magenta_bed", "light_blue_bed", "yellow_bed", "lime_bed", "pink_bed",
            "gray_bed", "light_gray_bed", "cyan_bed", "purple_bed", "blue_bed", "brown_bed", "green_bed", "red_bed",
            "black_bed", "respawn_anchor");

    private ItemPool() {
    }

    /** The extra bans from the {@code banned-items} option: names separated by commas or spaces, any case. */
    static Set<String> parseBans(String option) {
        Set<String> bans = new LinkedHashSet<>();
        for (String part : option.split("[,\\s]+")) {
            String name = part.strip().toLowerCase(Locale.ROOT);
            if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
            if (!name.isEmpty()) bans.add(name);
        }
        return bans;
    }

    /**
     * The items to hand out.
     *
     * @param items   every item name the server has (lower case)
     * @param bans    names to leave out
     * @param unknown filled with the ban names that match no item (so a typo can be reported)
     */
    static List<String> build(Collection<String> items, Set<String> bans, Collection<String> unknown) {
        Set<String> known = new LinkedHashSet<>(items);
        for (String ban : bans) {
            if (!known.contains(ban)) unknown.add(ban);
        }
        List<String> pool = new ArrayList<>();
        for (String item : items) {
            if (!bans.contains(item) && !BUILT_IN_BANS.contains(item)) pool.add(item);
        }
        return pool;
    }
}
