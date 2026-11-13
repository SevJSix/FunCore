package me.sevj6.funCore.games.clutch;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import me.sevj6.funCore.arena.ArenaPoint;
import me.sevj6.funCore.arena.Region;
import me.sevj6.funCore.game.FallGrace;
import me.sevj6.funCore.game.GameState;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.game.InventoryLock;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.game.PlayMode;
import me.sevj6.funCore.kit.Kit;
import me.sevj6.funCore.leaderboard.Leaderboard;
import me.sevj6.funCore.kit.KitItem;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Boat;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Clutch: drop off ever higher platforms and survive the landing (a water bucket, a boat, a cobweb, a pearl, ...).
 * Land your clutch and you move up a level. Furthest level wins; ties go to fewest deaths, then fastest.
 *
 * <h2>Arena setup</h2>
 * One map that every player in the match shares, each at their own pace:
 * <ul>
 *     <li>Points {@code level-1}, {@code level-2}, ... (up to {@value #MAX_LEVELS}): where a player stands to jump each
 *     level. Levels are counted up to the first missing one.</li>
 *     <li>Optional areas {@code landing-1}, {@code landing-2}, ...: where a level's clutch must end up (make them a few
 *     blocks tall, so a boat or minecart on the pad is inside). Without one, coming to rest anywhere below the platform
 *     counts.</li>
 * </ul>
 * Nothing else about the map is assumed: heights, items and pads all come from the points, areas and kits.
 *
 * <h2>Rules</h2>
 * A level starts on its platform with that level's kit ({@code /game kit clutch level-N}, which may be empty when the
 * items are hidden in the map). Dropping below the platform starts the clutch; it counts once the player has stopped
 * falling for {@code landed-seconds}, alive, inside the landing area. A hit that would kill them (unless they hold a
 * totem), the void, leaving the arena, standing on a block outside the landing area or running out of time is a miss.
 * <ul>
 *     <li>By default a miss sends them back to the same level's platform to try again, as often as they like.</li>
 *     <li>With {@code hardcore} on, one miss ends their run and they spectate.</li>
 * </ul>
 * The match ends when everyone is done (cleared every level, or out in hardcore) or when {@code match-time-limit-minutes}
 * runs out. Players are ranked by levels cleared, most first; on a tie, by fewest deaths; then by whoever cleared
 * those levels fastest (timed from the start of their own run, to the landing of their last cleared level).
 *
 * <h2>Sharing the map</h2>
 * Players never change it for each other:
 * <ul>
 *     <li>What a player places (blocks, water, boats, minecarts) is removed after each of their attempts.</li>
 *     <li>Chests and other containers open a copy of their contents for each player, refilled every attempt.</li>
 *     <li>Clicking an item frame gives a copy of its item (once per attempt) and leaves the frame as it was.</li>
 *     <li>Water taken from the map with a bucket, or from a cauldron, comes straight back.</li>
 * </ul>
 */
public final class ClutchGame extends Minigame {

    public static final String ID = "clutch";
    /** How many levels the game has kits for. An arena can have fewer. */
    public static final int MAX_LEVELS = 10;
    /** How far below the platform a player must be for the jump to count as started. */
    private static final double DROP_DISTANCE = 3;
    /** Moving less than this per tick (vertically) counts as standing still. */
    private static final double STILL_PER_TICK = 0.05;
    /** Ticks between landing a clutch and being sent up to the next level. */
    private static final long NEXT_LEVEL_DELAY = 15L;

    public static final GameType TYPE = build();
    /** Every player's 2 best completions of all levels, hardcore or not: fewest deaths, then fastest. */
    public static final Leaderboard.Definition LEADERBOARD = new Leaderboard.Definition("clutch", "<gold><bold>Best Clutch Runs", 2);

    private enum Phase {
        /** Between attempts (just landed, or about to be put back on the platform). */
        RESETTING,
        /** On the platform, about to jump. */
        READY,
        /** Dropped off the platform. */
        FALLING,
        /** Cleared every level. */
        FINISHED,
        /** Missed a clutch in hardcore. */
        OUT;

        boolean isPlaying() {
            return this == RESETTING || this == READY || this == FALLING;
        }

        boolean isAttempting() {
            return this == READY || this == FALLING;
        }
    }

    /** One player's run up the levels, and what they've changed in the map this attempt. */
    private static final class Run {
        int level = 1;
        Phase phase = Phase.RESETTING;
        /** Deaths (misses) on the current level; 0 means they're on their first try at it. */
        int levelDeaths;
        /** Deaths on every level, for the results: a perfect run is 0. */
        int deaths;
        /** Match tick their run began (later than 0 for someone who joined late). */
        final long startedAt;
        /** Match tick they cleared every level on; never, until they do. */
        long finishedAt = Long.MAX_VALUE;
        /** Levels cleared so far, counted the moment they land (before they're sent up to the next one). */
        int cleared;
        /** Match tick they landed their last cleared level on; never, until they clear one. */
        long clearedAt = Long.MAX_VALUE;
        /** Whether they've already been shown "You win!" (once nobody left could beat them), so the end doesn't repeat it. */
        boolean celebrated;
        /** Ticks since the attempt started (on the platform). */
        int ticks;
        /** Ticks in a row they've barely moved, after dropping. */
        int stillTicks;
        double lastY;

        /** Blocks they placed, with what was there before (the first change to each block wins). */
        final Map<Block, BlockState> placed = new LinkedHashMap<>();
        /** Boats, minecarts and the like they placed. */
        final List<Entity> entities = new ArrayList<>();
        /** Their own copy of each container they opened. */
        final Map<Block, Inventory> containers = new HashMap<>();
        /** The container whose copy they have open, to close it once they're out of reach. */
        @Nullable Block openContainer;
        /** Item frames they took the item from. */
        final Set<UUID> frames = new HashSet<>();
        /**
         * Whether they've done something this attempt that only works once the server answers (opening a container,
         * filling a bucket, taking from an item frame): it cost them a whole round trip, which their grace gives back.
         */
        boolean waitedOnServer;
        /**
         * Whether they actually clutched this attempt (see {@link #clutched}): a landing without one is a miss, however
         * they got there unhurt.
         */
        boolean clutched;
        /** Fall hits the server saw this attempt, and the biggest (for the log when a landing is refused). */
        int fallHits;
        double biggestFallHit;
        /**
         * The fall distance the server measured, at most this attempt and at its last count before it went back to 0
         * (water resets it, and so does landing): how far it thought they fell before impact.
         */
        double maxFallDistance, lastFallDistance;

        Run(long startedAt) {
            this.startedAt = startedAt;
        }

        /** Ticks from the start of their run to clearing every level; never, if they haven't. */
        long completionTicks() {
            return finishedAt == Long.MAX_VALUE ? Long.MAX_VALUE : finishedAt - startedAt;
        }

        /** Ticks from the start of their run to landing their last cleared level; never, if they haven't cleared one. */
        long progressTicks() {
            return clearedAt == Long.MAX_VALUE ? Long.MAX_VALUE : clearedAt - startedAt;
        }
    }

    private final Map<UUID, Run> runs = new HashMap<>();
    /** Keeps items in place on levels whose inventory option locks slots. */
    private final InventoryLock inventoryLock = new InventoryLock();
    /** Holds fall damage back a moment for players with high ping. */
    private final FallGrace fallGrace = new FallGrace();
    /** How many levels this match's arena has, set when it starts. */
    private int levels;
    /** Ticks since the match started. */
    private long matchTicks;
    private boolean timeUp;

    public ClutchGame(FunCore plugin, GameType type, @Nullable Arena arena) {
        super(plugin, type, arena);
    }

    private static GameType build() {
        GameType.Builder builder = GameType.builder(ID, Utils.mm("<aqua>Clutch"), ClutchGame::new)
                .modes(PlayMode.SINGLEPLAYER, PlayMode.FFA)
                .players(2, 8)
                .dynamicByDefault()
                .supportsLateJoin()
                .pvpByDefault(false)
                // Everyone starts on the same platform; nobody should be knocked off it by someone else.
                .collisionByDefault(false)
                .requiresPoints(levelPoint(1))
                .arenaDefaults(arena -> {
                    arena.setPlaceAllowed(true);
                    arena.setBreakAllowed(false);
                })
                .arenaCheck(ClutchGame::checkArena)
                .option("hardcore", false, "one miss and you're out; otherwise players retry a level until they clear it")
                .option("match-time-limit-minutes", 15, 0, 180, "0 = none; when time's up, the furthest level wins (then fewest deaths, then fastest)")
                .option("level-time-limit-seconds", 15, 0, 600, "0 = none; not landing a clutch in time counts as a miss")
                .option("landed-seconds", 1.0, 0.25, 5, "how long a player must stop falling for the clutch to count")
                .option("max-health-hearts", 1.0, 0.5, 50, "players' health during the match, in hearts (0.5 steps)")
                // Lag compensation (see FallGrace): fall damage waits a moment for a late clutch to reach the server.
                .option("ping-grace-1-tick", 100, 0, 1000, "ping (ms) from which fall damage waits 1 tick, so a clutch "
                        + "that reaches the server late still counts; 0 = never")
                .option("ping-grace-2-ticks", 180, 0, 1000, "ping (ms) from which fall damage waits 2 ticks; 0 = never")
                .option("interaction-grace-max-ticks", 5, 0, 20, "after opening a chest, filling a bucket or taking from an "
                        + "item frame (which wait on the server), players with ping grace get their whole ping back, up to "
                        + "this many ticks; 0 = off");
        List<String> points = new ArrayList<>(), areas = new ArrayList<>();
        for (int level = 1; level <= MAX_LEVELS; level++) {
            int at = level;
            builder.kit(kitName(level), () -> defaultKit(at));
            // Levels 9 and 10 fill the inventory with bedrock to leave one free slot; it mustn't be placeable.
            builder.option(placeOption(level), level < 9, "whether players can place blocks (and empty buckets) on level " + level);
            // ... and only its one free slot and the offhand take what's found in the chests.
            builder.option(inventoryOption(level), level < 9 ? "all" : "hotbar-1, offhand", InventoryLock.Rule::normalize,
                    "slots items can be moved in and out of on level " + level + ": all, none, or e.g. hotbar-1, offhand "
                            + "(slot numbers 0-40 work too); taking items out of chests is always allowed");
            points.add(levelPoint(level));
            areas.add(landingArea(level));
        }
        return builder.suggestsPoints(points.toArray(String[]::new)).suggestsAreas(areas.toArray(String[]::new)).build();
    }

    static String levelPoint(int level) {
        return "level-" + level;
    }

    static String landingArea(int level) {
        return "landing-" + level;
    }

    /** The option saying whether blocks can be placed on a level. */
    static String placeOption(int level) {
        return "level-" + level + "-place-blocks";
    }

    /** The option saying which inventory slots items can be moved in and out of on a level (see {@link InventoryLock}). */
    static String inventoryOption(int level) {
        return "level-" + level + "-inventory";
    }

    static String kitName(int level) {
        return "level-" + level;
    }

    /**
     * The built-in kits, easiest first (admins can change any of them with {@code /game kit clutch level-N}). Levels
     * 8-10 hand out nothing: what they need is meant to be found in the map.
     */
    static Kit defaultKit(int level) {
        Material item = switch (level) {
            case 1 -> Material.WATER_BUCKET;
            case 2 -> Material.COBWEB;
            case 3 -> Material.SWEET_BERRIES;
            case 4 -> Material.SLIME_BLOCK;
            case 5 -> Material.MINECART;
            case 6 -> Material.OAK_BOAT;
            case 7 -> Material.LADDER;
            default -> null;
        };
        Kit.Builder kit = Kit.builder();
        if (item != null) kit.slot(0, new ItemStack(item));
        return kit.build();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Arena layout
    // ------------------------------------------------------------------------------------------------------------

    /** What an arena is missing beyond the required level-1 point. */
    private static List<String> checkArena(Arena arena) {
        List<String> problems = new ArrayList<>();
        Region region = arena.getRegion();
        int levels = countLevels(arena);
        for (int level = 1; level <= MAX_LEVELS; level++) {
            ArenaPoint point = arena.getPoints().get(levelPoint(level));
            if (point != null && level > levels + 1) {
                problems.add("point '" + levelPoint(level) + "' is set but '" + levelPoint(levels + 1) + "' isn't, so levels stop at " + levels);
            }
            if (region == null) continue;
            if (point != null && !region.contains(point.x(), point.y(), point.z())) {
                problems.add("point '" + levelPoint(level) + "' is outside the region");
            }
            Region landing = arena.getArea(landingArea(level));
            if (landing != null && !region.contains(landing)) problems.add("area '" + landingArea(level) + "' goes outside the region");
        }
        return problems;
    }

    /** Levels level-1, level-2, ... up to the first missing one (at most {@link #MAX_LEVELS}). */
    private static int countLevels(Arena arena) {
        int count = 0;
        while (count < MAX_LEVELS && arena.getPoints().containsKey(levelPoint(count + 1))) count++;
        return count;
    }

    /** Where a run's current level starts. */
    private Location platform(Run run) {
        return Objects.requireNonNull(arena().getPoint(levelPoint(run.level)), "level point");
    }

    private static boolean inside(Region region, Location location) {
        return region.contains(location.getX(), location.getY(), location.getZ());
    }

    private boolean hardcore() {
        return getBooleanOption("hardcore");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Framework settings
    // ------------------------------------------------------------------------------------------------------------

    /** Singleplayer starts right away: there's nobody to wait for. Multiplayer uses the game's countdown setting. */
    @Override
    protected int getCountdownSeconds() {
        return getMode() == PlayMode.SINGLEPLAYER ? 0 : super.getCountdownSeconds();
    }

    /** Players start on the level-1 platform, not on the arena's spawns. */
    @Override
    protected boolean teleportToSpawnsOnStart() {
        return false;
    }

    /**
     * Survival, so they can place their clutch blocks (and take fall damage). Levels that don't allow placing switch to
     * adventure for the attempt (see {@link #onPlatform}).
     */
    @Override
    protected GameMode getPlayingGameMode() {
        return GameMode.SURVIVAL;
    }

    /** Only during an attempt, on a level that allows it ({@code level-N-place-blocks}), if the arena allows placing. */
    @Override
    protected boolean canPlace(Player player, Block block) {
        Run run = runs.get(player.getUniqueId());
        return run != null && run.phase.isAttempting() && getBooleanOption(placeOption(run.level))
                && super.canPlace(player, block);
    }

    /**
     * Only during an attempt, inside the arena. Filling a bucket (from water, lava, powder snow or a cauldron) is always
     * fine, so a bucket found on the way down can be used (the map's own liquid comes straight back); breaking anything
     * else needs the arena to allow it.
     */
    @Override
    protected boolean canBreak(Player player, Block block) {
        Run run = runs.get(player.getUniqueId());
        if (run == null || !run.phase.isAttempting() || !arena().contains(block.getLocation())) return false;
        boolean bucketable = block.isLiquid() || block.getType() == Material.POWDER_SNOW || Tag.CAULDRONS.isTagged(block.getType())
                || (block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged());
        return bucketable || arena().isBreakAllowed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        levels = countLevels(arena());
        if (levels == 0) {
            broadcast(Utils.mm("<red>This arena has no levels set up."));
            end();
            return;
        }
        for (Player player : getPlayers()) {
            Run run = new Run(matchTicks);
            runs.put(player.getUniqueId(), run);
            onPlatform(player, run);
        }
        int minutes = getIntOption("match-time-limit-minutes");
        broadcast(Utils.mm("<yellow>Jump off and clutch the landing! <rules>",
                Placeholder.component("rules", Utils.mm(hardcore()
                        ? "<red>Hardcore: one miss and you're out."
                        : "<gray>Missed? Try again, as often as you like."))));
        if (minutes > 0) {
            broadcast(Utils.mm("<gray>You have <white><minutes> minutes</white>. Furthest level wins; ties go to fewest deaths, then fastest.",
                    Placeholder.unparsed("minutes", String.valueOf(minutes))));
        }
        runTaskTimer(this::tick, 1L, 1L);
    }

    /**
     * Someone joining a running match (with {@code latejoin} on) starts at level 1 with a fresh run, on the same match
     * clock as everyone else.
     */
    @Override
    protected void onLateJoin(Player player) {
        if (levels == 0) return; // the match is ending: it had no levels
        Run run = new Run(matchTicks);
        runs.put(player.getUniqueId(), run);
        onPlatform(player, run);
        long matchLimit = getIntOption("match-time-limit-minutes") * 1200L;
        player.sendMessage(Utils.prefixed(Utils.mm(matchLimit > 0
                        ? "<yellow>You joined mid-match: start at level 1. <gray><time> left to get as far as you can."
                        : "<yellow>You joined mid-match: start at level 1.",
                Placeholder.unparsed("time", Utils.formatTime(Math.max(0, matchLimit - matchTicks) / 20)))));
    }

    @Override
    protected void onEnd() {
        if (runs.isEmpty()) return; // stopped before it began
        runs.values().forEach(this::cleanUp);

        List<Player> ranked = new ArrayList<>();
        for (Player player : getPlayers()) {
            if (runs.containsKey(player.getUniqueId())) ranked.add(player);
        }
        // Furthest level first; then fewest deaths; then whoever got that far fastest (from the start of their own run,
        // so joining late doesn't count against anyone).
        ranked.sort(Comparator.<Player>comparingInt(player -> -runs.get(player.getUniqueId()).cleared)
                .thenComparingInt(player -> runs.get(player.getUniqueId()).deaths)
                .thenComparingLong(player -> runs.get(player.getUniqueId()).progressTicks()));
        boolean multiplayer = ranked.size() > 1;
        boolean anyCleared = ranked.stream().anyMatch(player -> cleared(runs.get(player.getUniqueId())) > 0);
        Player winner = anyCleared ? ranked.getFirst() : null;

        if (timeUp) broadcast(Utils.mm("<yellow>Time's up!"));
        if (multiplayer) {
            broadcast(winner == null ? Utils.mm("<gray>Nobody cleared a level.")
                    : Utils.mm("<gold><name> wins!", Placeholder.component("name", winner.name())));
            for (int i = 0; i < ranked.size() && i < 5; i++) {
                Player player = ranked.get(i);
                Run run = runs.get(player.getUniqueId());
                broadcast(Utils.mm("<gray>#<place> <white><name></white>: <cleared>/<levels> levels, <deaths> <noun><time>",
                        Placeholder.unparsed("place", String.valueOf(i + 1)), Placeholder.component("name", player.name()),
                        Placeholder.unparsed("cleared", String.valueOf(cleared(run))), Placeholder.unparsed("levels", String.valueOf(levels)),
                        Placeholder.unparsed("deaths", String.valueOf(run.deaths)),
                        Placeholder.unparsed("noun", run.deaths == 1 ? "death" : "deaths"),
                        Placeholder.unparsed("time", run.clearedAt == Long.MAX_VALUE ? "" : ", " + Utils.formatTime(run.progressTicks() / 20))));
            }
        }
        for (int i = 0; i < ranked.size(); i++) {
            Player player = ranked.get(i);
            Run run = runs.get(player.getUniqueId());
            boolean won = multiplayer ? player.equals(winner) : run.phase == Phase.FINISHED;
            if (won && run.celebrated) continue; // already shown the moment their win was certain
            Component title = won ? Utils.mm("<gold>You win!")
                    : multiplayer ? Utils.mm("<yellow>#<place>", Placeholder.unparsed("place", String.valueOf(i + 1)))
                    : Utils.mm(timeUp ? "<red>Time's up" : "<red>Game over");
            Utils.title(player, title, Utils.mm("<gray>Levels cleared: <white><cleared>/<levels></white>  Deaths: <white><deaths>",
                    Placeholder.unparsed("cleared", String.valueOf(cleared(run))), Placeholder.unparsed("levels", String.valueOf(levels)),
                    Placeholder.unparsed("deaths", String.valueOf(run.deaths))), 10, 60, 10);
            Utils.playSound(player, won ? "ui.toast.challenge_complete" : "entity.wither.death", 1f, 1f);
        }
    }

    @Override
    protected void onReset() {
        runs.values().forEach(this::cleanUp);
        runs.clear();
        fallGrace.clear();
        levels = 0;
        matchTicks = 0;
        timeUp = false;
    }

    @Override
    protected void onPlayerLeave(Player player, LeaveReason reason) {
        Run run = runs.remove(player.getUniqueId());
        if (run != null) cleanUp(run);
        fallGrace.cancel(player.getUniqueId());
        if (getState() == GameState.RUNNING) {
            checkEnd();
            celebrateSettledWinners(); // whoever left can't beat anyone any more
        }
    }

    /** Levels a run has cleared. */
    private int cleared(Run run) {
        return run.cleared;
    }

    /** Ends the match once nobody is still climbing. */
    private void checkEnd() {
        for (Run run : runs.values()) {
            if (run.phase.isPlaying()) return;
        }
        end();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Attempts
    // ------------------------------------------------------------------------------------------------------------

    /** Puts them on their level's platform with the level's kit, for a fresh attempt. */
    private void onPlatform(Player player, Run run) {
        Location platform = platform(run);
        fallGrace.cancel(player.getUniqueId());
        run.waitedOnServer = false;
        run.clutched = false;
        run.fallHits = 0;
        run.biggestFallHit = 0;
        run.maxFallDistance = 0;
        run.lastFallDistance = 0;
        closeContainer(player);
        player.leaveVehicle();
        Utils.heal(player);
        Utils.clearPotionEffects(player);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0f);
        player.setVelocity(new Vector());
        // Every attempt, so a change to the option applies from their next try.
        setMaxHealth(player, Math.round(getDoubleOption("max-health-hearts") * 2));
        Utils.heal(player);
        // Adventure where blocks can't be placed: the client then never shows a placed block at all. In survival it
        // would, until the server's refusal arrived, and spamming that against a wall stalls a fall.
        player.setGameMode(getBooleanOption(placeOption(run.level)) ? GameMode.SURVIVAL : GameMode.ADVENTURE);
        player.getInventory().clear();
        giveKit(player, kitName(run.level), UnaryOperator.identity());
        player.teleport(platform);
        setStartPoint(player, platform);
        run.phase = Phase.READY;
        run.ticks = 0;
        run.stillTicks = 0;
        run.lastY = platform.getY();

        // The big title only when they reach a new level; a retry just gets the action bar.
        if (run.levelDeaths == 0) {
            Utils.title(player, Utils.mm("<gold>Level <level>", Placeholder.unparsed("level", String.valueOf(run.level))),
                    kitHint(run.level), 5, 40, 10);
            Utils.playPling(player, 1f);
        } else {
            player.sendActionBar(Utils.mm("<gray>Level <level>  <dark_gray>|</dark_gray>  Deaths: <white><deaths>",
                    Placeholder.unparsed("level", String.valueOf(run.level)), Placeholder.unparsed("deaths", String.valueOf(run.deaths))));
        }
    }

    /**
     * "Clutch with: Water Bucket", or a hint that the items are somewhere in the map. Levels 9 and 10 get a hint of
     * their own (written for the current map: a chest of pearls on level 9, a totem on level 10). Bedrock in a kit is
     * filler that blocks inventory slots, so it isn't listed, and each item is named once.
     */
    private Component kitHint(int level) {
        if (level == 9) return Utils.mm("<gray>Grab the Pearl!");
        if (level == 10) return Utils.mm("<gray>Grab the Totem!");
        Kit kit = getKit(kitName(level));
        List<Component> names = new ArrayList<>();
        java.util.Set<Material> seen = new java.util.HashSet<>();
        if (kit != null) {
            for (KitItem item : kit.getItems()) {
                Material type = item.item().getType();
                if (type != Material.BEDROCK && seen.add(type)) names.add(item.item().effectiveName());
            }
        }
        if (names.isEmpty()) return Utils.mm("<gray>Find what you need on the way down!");
        return Utils.mm("<gray>Clutch with: <white><items>", Placeholder.component("items", Component.join(JoinConfiguration.commas(true), names)));
    }

    /** A full completion goes on the all-time leaderboard (it keeps each player's 2 best). */
    private void recordCompletion(Player player, Run run) {
        Leaderboard board = plugin.getLeaderboards() == null ? null : plugin.getLeaderboards().get(LEADERBOARD.id());
        if (board == null) return;
        long ticks = run.completionTicks();
        String text = run.deaths + (run.deaths == 1 ? " death, " : " deaths, ") + Utils.formatTime(ticks / 20);
        board.submit(new Leaderboard.Entry(player.getUniqueId(), player.getName(), List.of((long) run.deaths, ticks), text,
                System.currentTimeMillis()));
    }

    /** Every tick: the match clock, who has jumped, who has landed, who ran out of time. */
    private void tick() {
        if (getState() != GameState.RUNNING) return;
        matchTicks++;
        long matchLimit = getIntOption("match-time-limit-minutes") * 1200L;
        if (matchLimit > 0) {
            long left = matchLimit - matchTicks;
            if (left <= 0) {
                timeUp = true;
                end();
                return;
            }
            if (left == 1200 || left == 200) {
                broadcast(Utils.mm("<yellow><time> left!", Placeholder.unparsed("time", left == 1200 ? "1 minute" : "10 seconds")));
            }
        }
        fallGrace.tick();
        int limit = getIntOption("level-time-limit-seconds") * 20;
        int settle = (int) Math.max(1, Math.round(getDoubleOption("landed-seconds") * 20));
        for (Player player : getPlayers()) {
            Run run = runs.get(player.getUniqueId());
            if (run == null) continue;
            // Finished players watch the rest of the match with their result kept on screen.
            if (run.phase == Phase.FINISHED) {
                if (matchTicks % 10 == 0) sendFinishedStatus(player, run, matchLimit);
                continue;
            }
            if (!run.phase.isAttempting()) continue;
            // One player's error mustn't stop everyone else's clutches from being checked.
            try {
                tick(player, run, limit, settle, matchLimit);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("Error in {}'s Clutch run", player.getName(), e);
            }
        }
        // A tie on deaths can be settled by the clock alone (the one still climbing can no longer finish faster).
        if (matchTicks % 20 == 0) celebrateSettledWinners();
    }

    /** One player's tick, mid-attempt: have they jumped, landed, or run out of time? */
    private void tick(Player player, Run run, int limit, int settle, long matchLimit) {
        run.ticks++;
        checkContainerReach(player, run);
        // Only once they've jumped: touching a ladder on the platform isn't a clutch.
        Location at = player.getLocation();
        double fallDistance = player.getFallDistance();
        if (fallDistance > 0) {
            run.lastFallDistance = fallDistance;
            run.maxFallDistance = Math.max(run.maxFallDistance, fallDistance);
        }
        // Only once they've jumped, and only at the landing (see clutched()).
        if (!run.clutched && run.phase == Phase.FALLING && atLanding(run, at)
                && (breakingFallWithoutWater(player) || inOwnWater(player, run))) {
            clutched(run);
        }
        double platformY = platform(run).getY();
        if (run.phase == Phase.READY) {
            if (at.getY() < platformY - DROP_DISTANCE) {
                run.phase = Phase.FALLING;
                run.lastY = at.getY();
            }
        } else {
            // Still, and below the platform (climbing or pearling back up onto it doesn't count).
            boolean still = Math.abs(at.getY() - run.lastY) < STILL_PER_TICK && !player.isGliding()
                    && at.getY() < platformY - DROP_DISTANCE;
            run.stillTicks = still ? run.stillTicks + 1 : 0;
            run.lastY = at.getY();
            // Not while a fall hit is held back for their ping: it decides whether they survived the landing.
            if (run.stillTicks >= settle && !fallGrace.isHolding(player.getUniqueId())) {
                Region landing = arena().getArea(landingArea(run.level));
                if (landing == null || inside(landing, at)) {
                    // Reaching the landing unhurt isn't enough: they must have clutched (see clutched()). Without
                    // this, a fall that went wrong but somehow did no damage still counted.
                    if (!run.clutched) {
                        plugin.getSLF4JLogger().info("Clutch: {} reached the level {} landing without clutching; counted as a miss "
                                        + "(ping {} ms, fall hits seen {}, biggest {}, waited on server {}, "
                                        + "server fall distance before impact {} blocks, most {} blocks)",
                                player.getName(), run.level, player.getPing(), run.fallHits,
                                String.format("%.1f", run.biggestFallHit), run.waitedOnServer,
                                String.format("%.1f", run.lastFallDistance), String.format("%.1f", run.maxFallDistance));
                        miss(player, run, "didn't clutch it", true);
                        return;
                    }
                    landed(player, run);
                    return;
                }
                // Stopped on a ledge or the wrong pad. Hanging on a ladder or sitting in a cobweb is fine: they
                // can still make their way down to the landing.
                if (standingOnBlock(player)) {
                    miss(player, run, "landed in the wrong spot", true);
                    return;
                }
            }
        }
        if (limit > 0 && run.ticks >= limit) {
            miss(player, run, "ran out of time", true);
            return;
        }
        if (run.ticks % 10 == 0) sendStatus(player, run, limit, matchLimit);
    }

    /**
     * Records that they clutched this attempt: a landing only counts with one (however they reached it unhurt).
     * <p>
     * A clutch is what catches them <em>at the landing</em> ({@link #atLanding}): something they pass on the way down
     * (a few blocks of water, a cobweb or ladder in the map) doesn't stop a fall that's still going, so it never counts.
     * Where there's a sign the clutch actually caught them, that's what counts, not just trying it. And it's what a
     * clutch is made of, never the item a level's kit happens to hold (kits change, and levels 8-10 use what's found in
     * the map):
     * <ul>
     *     <li>at the landing, after jumping: in a boat, minecart or on a mob; in water from their own bucket; in
     *     cobweb, sweet berries or powder snow; on a ladder, vine or other climbable; on slime (see
     *     {@link #breakingFallWithoutWater} and {@link #inOwnWater});</li>
     *     <li>a block placed at the landing (a slime bounce lasts about a tick, too short to be sure of seeing);</li>
     *     <li>an ender pearl teleporting them onto the landing (not just throwing it);</li>
     *     <li>a totem saving them (it only goes off on a hit that would kill);</li>
     *     <li>shooting anything other than a pearl, anywhere: what level 5's crossbow shot does is up to the map.</li>
     * </ul>
     */
    private static void clutched(Run run) {
        run.clutched = true;
    }

    /** How far around a level's landing area a clutch may catch them: sideways, below, above. */
    private static final int LANDING_MARGIN_SIDE = 2, LANDING_MARGIN_BELOW = 1, LANDING_MARGIN_ABOVE = 6;

    /**
     * Whether a spot is at the run's landing: its landing area, a little bigger (a ladder grabbed just above it, a boat
     * on its edge). Without a landing area, anywhere well below the platform.
     */
    private boolean atLanding(Run run, Location at) {
        Region landing = arena().getArea(landingArea(run.level));
        if (landing == null) return at.getY() < platform(run).getY() - DROP_DISTANCE;
        return at.getX() >= landing.minX() - LANDING_MARGIN_SIDE && at.getX() < landing.maxX() + 1 + LANDING_MARGIN_SIDE
                && at.getZ() >= landing.minZ() - LANDING_MARGIN_SIDE && at.getZ() < landing.maxZ() + 1 + LANDING_MARGIN_SIDE
                && at.getY() >= landing.minY() - LANDING_MARGIN_BELOW && at.getY() < landing.maxY() + 1 + LANDING_MARGIN_ABOVE;
    }

    /**
     * Whether they're in water near something they placed this attempt: their own water bucket, not the map's water
     * (the arena keeps placed water from flowing, but a few blocks of leeway costs nothing).
     */
    private static boolean inOwnWater(Player player, Run run) {
        if (!player.isInWater() || run.placed.isEmpty()) return false;
        Block feet = player.getLocation().getBlock();
        for (Block block : run.placed.keySet()) {
            if (!block.getWorld().equals(feet.getWorld())) continue;
            if (Math.abs(block.getX() - feet.getX()) <= 8 && Math.abs(block.getZ() - feet.getZ()) <= 8
                    && Math.abs(block.getY() - feet.getY()) <= 2) return true;
        }
        return false;
    }

    /**
     * Whether something other than water is breaking their fall right now: a vehicle, something climbable, cobweb,
     * sweet berries or powder snow, or a slime block under them (like {@link FallGrace#brokeFall}, without water).
     */
    private static boolean breakingFallWithoutWater(Player player) {
        if (player.isInsideVehicle() || player.isClimbing()) return true;
        Block feet = player.getLocation().getBlock();
        for (Block block : new Block[]{feet, feet.getRelative(0, 1, 0)}) {
            Material type = block.getType();
            if (Tag.FALL_DAMAGE_RESETTING.isTagged(type) || type == Material.POWDER_SNOW) return true;
        }
        Block below = player.getLocation().subtract(0, 0.05, 0).getBlock();
        return below.getType() == Material.SLIME_BLOCK && !player.isSneaking();
    }

    private static boolean standingOnBlock(Player player) {
        if (player.isInsideVehicle()) return false;
        return player.getLocation().subtract(0, 0.05, 0).getBlock().isSolid();
    }

    private void sendStatus(Player player, Run run, int limit, long matchLimit) {
        List<String> parts = new ArrayList<>();
        parts.add("<gold>Level " + run.level + "<gray>/" + levels);
        if (!hardcore()) parts.add("<gray>Deaths <white>" + run.deaths);
        if (limit > 0) parts.add("<gray>Level time <white>" + Utils.formatTime((limit - run.ticks + 19) / 20));
        if (matchLimit > 0) parts.add("<gray>Match <white>" + Utils.formatTime((matchLimit - matchTicks + 19) / 20));
        player.sendActionBar(Utils.mm(String.join("  <dark_gray>|</dark_gray>  ", parts)));
    }

    /** What a player who cleared every level sees while they spectate: their result, and the match clock. */
    private void sendFinishedStatus(Player player, Run run, long matchLimit) {
        List<String> parts = new ArrayList<>();
        parts.add("<green>Cleared all " + levels + " levels");
        if (!hardcore()) parts.add("<gray>Deaths <white>" + run.deaths);
        parts.add("<gray>Time <white>" + Utils.formatTime(run.completionTicks() / 20));
        if (matchLimit > 0) parts.add("<gray>Match <white>" + Utils.formatTime((matchLimit - matchTicks + 19) / 20));
        player.sendActionBar(Utils.mm(String.join("  <dark_gray>|</dark_gray>  ", parts)));
    }

    /** They survived the drop: on to the next level, or done. */
    private void landed(Player player, Run run) {
        Utils.playSound(player, "entity.player.levelup", 1f, 1.2f);
        run.cleared = run.level;
        run.clearedAt = matchTicks;
        if (run.level >= levels) {
            run.phase = Phase.FINISHED;
            run.finishedAt = matchTicks;
            cleanUp(run);
            broadcast(Utils.mm("<gold><name> cleared all <levels> levels!", Placeholder.component("name", player.name()),
                    Placeholder.unparsed("levels", String.valueOf(levels))));
            spectate(player, run, true);
            // Last, and guarded: a problem saving the record must never leave them stuck mid-run.
            try {
                recordCompletion(player, run);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("Could not record {}'s Clutch completion", player.getName(), e);
            }
            checkEnd();
            // Still running: others are still climbing. Celebrate now rather than at the end (see celebrate()).
            if (getState() == GameState.RUNNING) {
                if (!celebrate(player, run)) {
                    Utils.title(player, Utils.mm("<green>All levels cleared!"), Utils.mm("<gray>Deaths: <white><deaths></white>  Time: <white><time></white>  <dark_gray>|</dark_gray>  <gray>final place when the match ends",
                            Placeholder.unparsed("deaths", String.valueOf(run.deaths)),
                            Placeholder.unparsed("time", Utils.formatTime(run.completionTicks() / 20))), 10, 60, 10);
                    Utils.playSound(player, "ui.toast.challenge_complete", 1f, 1.2f);
                }
                celebrateSettledWinners();
            }
            return;
        }
        int cleared = run.level;
        run.phase = Phase.RESETTING;
        if (getPlayers().size() > 1) {
            broadcast(Utils.mm("<green><name> clutched level <level>.", Placeholder.component("name", player.name()),
                    Placeholder.unparsed("level", String.valueOf(cleared))));
        }
        player.sendActionBar(Utils.mm("<green>Clutched! <gray>On to level <level>", Placeholder.unparsed("level", String.valueOf(cleared + 1))));
        // A moment to enjoy it, then they're sent up.
        later(player, run, NEXT_LEVEL_DELAY, () -> {
            cleanUp(run);
            run.level++;
            run.levelDeaths = 0;
            onPlatform(player, run);
        });
    }

    /**
     * Shows a player who cleared every level "You win!" with the victory sound, right away, if nobody left in the match
     * can beat them any more (see {@link #canStillBeBeaten}). Done once; the match's end doesn't repeat it.
     *
     * @return whether it was shown
     */
    private boolean celebrate(Player player, Run run) {
        if (run.celebrated || run.phase != Phase.FINISHED || canStillBeBeaten(run)) return false;
        run.celebrated = true;
        Utils.title(player, Utils.mm("<gold>You win!"), Utils.mm("<gray>Levels cleared: <white><cleared>/<levels></white>  Deaths: <white><deaths>",
                Placeholder.unparsed("cleared", String.valueOf(cleared(run))), Placeholder.unparsed("levels", String.valueOf(levels)),
                Placeholder.unparsed("deaths", String.valueOf(run.deaths))), 10, 60, 10);
        Utils.playSound(player, "ui.toast.challenge_complete", 1f, 1f);
        return true;
    }

    /** Someone's result changed (a miss, a finish, someone left): celebrates any finisher whose win is now certain. */
    private void celebrateSettledWinners() {
        if (getState() != GameState.RUNNING) return;
        for (Player player : getPlayers()) {
            Run run = runs.get(player.getUniqueId());
            if (run != null) celebrate(player, run);
        }
    }

    /**
     * Whether anyone else in the match could still finish ahead of someone who cleared every level. Players rank by
     * levels cleared, then fewest deaths, then fastest (from the start of their own run). Only someone who has also
     * cleared every level, or is still climbing and could, can tie on levels. Deaths only go up, so someone with more
     * can never catch up; someone with fewer is ahead (or will be, once they finish). On equal deaths, a finished
     * player is compared by time, and one still climbing could only win if they can still beat that time.
     */
    private boolean canStillBeBeaten(Run finished) {
        for (Run other : runs.values()) {
            if (other == finished || other.deaths > finished.deaths) continue;
            if (other.finishedAt == Long.MAX_VALUE && !other.phase.isPlaying()) continue; // out: can't reach the top
            if (other.deaths < finished.deaths) return true;
            if (other.finishedAt != Long.MAX_VALUE) {
                if (other.completionTicks() < finished.completionTicks()) return true;
            } else if (other.phase.isPlaying() && matchTicks - other.startedAt < finished.completionTicks()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A failed attempt: back to the platform to try again, or (in hardcore) the end of their run.
     *
     * @param teleport {@code false} from a move event, which moves the player itself
     */
    private void miss(Player player, Run run, String why, boolean teleport) {
        // Right away: a chest opened on the way down mustn't stay open for them to loot from the platform.
        closeContainer(player);
        Utils.playSound(player, "entity.player.hurt", 1f, 0.8f);
        run.deaths++;
        if (hardcore()) {
            run.phase = Phase.OUT;
            cleanUp(run);
            broadcast(Utils.mm("<red><name> <gray><why> on level <level>.", Placeholder.component("name", player.name()),
                    Placeholder.unparsed("why", why), Placeholder.unparsed("level", String.valueOf(run.level))));
            spectate(player, run, teleport);
            checkEnd();
            celebrateSettledWinners();
            return;
        }
        run.phase = Phase.RESETTING;
        run.levelDeaths++;
        celebrateSettledWinners(); // one more death may put a finisher out of reach
        player.sendMessage(Utils.prefixed(Utils.mm("<red>Missed: <gray>you <why>. Back up you go.", Placeholder.unparsed("why", why))));
        // Next tick, so a move event can finish moving them first.
        later(player, run, 1L, () -> {
            cleanUp(run);
            onPlatform(player, run);
        });
    }

    /**
     * The copies aren't real containers, so Minecraft doesn't close them when the player gets too far away (falling past
     * a chest, say). This does, with the same reach: the player's block interaction range plus 4 blocks, from their eyes
     * to the nearest point of the block.
     */
    private void checkContainerReach(Player player, Run run) {
        Block block = run.openContainer;
        if (block == null) return;
        if (player.getOpenInventory().getTopInventory() != run.containers.get(block)) {
            run.openContainer = null; // they closed it themselves
            return;
        }
        AttributeInstance range = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        double reach = (range == null ? 4.5 : range.getValue()) + 4.0;
        Location eye = player.getEyeLocation();
        double dx = eye.getX() - Math.clamp(eye.getX(), block.getX(), block.getX() + 1.0);
        double dy = eye.getY() - Math.clamp(eye.getY(), block.getY(), block.getY() + 1.0);
        double dz = eye.getZ() - Math.clamp(eye.getZ(), block.getZ(), block.getZ() + 1.0);
        if (!eye.getWorld().equals(block.getWorld()) || dx * dx + dy * dy + dz * dz > reach * reach) {
            run.openContainer = null;
            player.closeInventory();
        }
    }

    /** Closes a chest (or any other container) they have open; their own inventory screen doesn't matter. */
    private static void closeContainer(Player player) {
        if (player.getOpenInventory().getType() != InventoryType.CRAFTING) player.closeInventory();
    }

    /** Runs a step of their run later, if they're still in the match on the same run by then. */
    private void later(Player player, Run run, long ticks, Runnable step) {
        runTaskLater(() -> {
            if (getState() == GameState.RUNNING && runs.get(player.getUniqueId()) == run && player.isOnline()) step.run();
        }, ticks);
    }

    /** Done climbing: they watch from their platform until the match ends (or leave with /game leave). */
    private void spectate(Player player, Run run, boolean teleport) {
        fallGrace.cancel(player.getUniqueId());
        closeContainer(player);
        player.leaveVehicle();
        Utils.heal(player);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
        Location platform = platform(run);
        setStartPoint(player, platform);
        if (teleport) player.teleport(platform);
        if (getState() == GameState.RUNNING && runs.values().stream().anyMatch(other -> other.phase.isPlaying())) {
            player.sendMessage(Utils.prefixed(Utils.mm("<gray>You're spectating until everyone is done. <white>/game leave</white> to go.")));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Sharing the map: undoing what a player changed, and per-player copies of what they take
    // ------------------------------------------------------------------------------------------------------------

    /** The run of a player in this match who is mid-attempt, or {@code null}. */
    private @Nullable Run attempting(@Nullable Player player) {
        if (player == null || getState() != GameState.RUNNING) return null;
        Run run = runs.get(player.getUniqueId());
        return run != null && run.phase.isAttempting() ? run : null;
    }

    /** Puts the map back the way it was before this attempt: removes what they placed, forgets what they took. */
    private void cleanUp(Run run) {
        for (Entity entity : run.entities) {
            if (entity.isValid()) entity.remove();
        }
        run.entities.clear();
        List<BlockState> states = new ArrayList<>(run.placed.values());
        for (int i = states.size() - 1; i >= 0; i--) states.get(i).update(true, false);
        run.placed.clear();
        run.containers.clear();
        run.openContainer = null;
        run.frames.clear();
    }

    /** Puts a block back next tick, after a change the map shouldn't keep. */
    private void restoreNextTick(Block block) {
        BlockState state = block.getState();
        runTaskLater(() -> state.update(true, false), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Run run = attempting(event.getPlayer());
        if (run == null) return;
        run.placed.putIfAbsent(event.getBlockPlaced(), event.getBlockReplacedState());
        if (atLanding(run, event.getBlockPlaced().getLocation().toCenterLocation())) clutched(run);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Run run = attempting(event.getPlayer());
        if (run != null) run.placed.putIfAbsent(event.getBlock(), event.getBlock().getState());
        // Not a clutch yet: being in that water is (see inOwnWater); powder snow shows as breakingFallWithoutWater.
    }

    // What else counts as clutching (see clutched()); each only records it. Being in a vehicle at the landing is
    // seen every tick (breakingFallWithoutWater); getting in counts straight away too, there.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMountClutch(EntityMountEvent event) {
        Run run = event.getEntity() instanceof Player player ? attempting(player) : null;
        if (run != null && atLanding(run, event.getEntity().getLocation())) clutched(run);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunchClutch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl) return; // a pearl counts when it lands them (onPearlClutch)
        Run run = event.getEntity().getShooter() instanceof Player player ? attempting(player) : null;
        if (run != null) clutched(run);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlClutch(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Run run = attempting(event.getPlayer());
        if (run != null && atLanding(run, event.getTo())) clutched(run);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemClutch(EntityResurrectEvent event) {
        Run run = event.getEntity() instanceof Player player ? attempting(player) : null;
        if (run != null) clutched(run);
    }

    /** Liquid taken from the map comes straight back (their own placed water just goes, as usual). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Run run = attempting(event.getPlayer());
        if (run != null && !run.placed.containsKey(event.getBlock())) restoreNextTick(event.getBlock());
    }

    /** Cauldrons refill right away, so the next player finds them as the map has them. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Run run = attempting(player);
        if (run == null) return;
        restoreNextTick(event.getBlock());
        // Filling a bucket from a cauldron isn't shown until the server answers.
        if (event.getReason() == CauldronLevelChangeEvent.ChangeReason.BUCKET_FILL) run.waitedOnServer = true;
    }

    /** The filled bucket only shows up once the server answers, like a chest's contents. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFillWait(PlayerBucketFillEvent event) {
        Run run = attempting(event.getPlayer());
        if (run != null) run.waitedOnServer = true;
    }

    /** A teleport mid-hold (an ender pearl landing, or being sent back up) means the held fall hit never lands. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportForgiveFall(PlayerTeleportEvent event) {
        if (isPlaying(event.getPlayer())) fallGrace.cancel(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Inventory lock: on levels that say so, items only move in and out of a few slots (taking from chests is fine)
    // ------------------------------------------------------------------------------------------------------------

    /** The inventory rule for a player's current level, or {@code null} if they aren't playing a level. */
    private InventoryLock.@Nullable Rule inventoryRule(Player player) {
        if (getState() != GameState.RUNNING) return null;
        Run run = runs.get(player.getUniqueId());
        if (run == null || !run.phase.isPlaying()) return null;
        return InventoryLock.Rule.parse(getStringOption(inventoryOption(run.level)));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryLock.Rule rule = inventoryRule(player);
        if (rule != null && !inventoryLock.allows(player, rule, event)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryLock.Rule rule = inventoryRule(player);
        if (rule != null && !inventoryLock.allows(player, rule, event)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        InventoryLock.Rule rule = inventoryRule(event.getPlayer());
        if (rule != null && !inventoryLock.allowsSwapHands(event.getPlayer(), rule)) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Utils.mm("<red>You can't move that item on this level."));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        inventoryLock.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuitForget(PlayerQuitEvent event) {
        inventoryLock.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Boats only go down where the level's clutch must land (or, without a landing area, well below the platform), so
     * nobody places one on the platform and rides it off.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlaceBoat(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Boat boat) || event.getPlayer() == null || !isPlaying(event.getPlayer())) return;
        Run run = attempting(event.getPlayer());
        if (run == null || !onLanding(run, boat.getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Utils.mm("<red>Boats can only go on the landing."));
        }
    }

    /** Water (or anything else from a bucket) only goes down on the landing too, never on a platform by accident. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEmptyBucketOffLanding(PlayerBucketEmptyEvent event) {
        if (!isPlaying(event.getPlayer())) return;
        Run run = attempting(event.getPlayer());
        if (run == null || !onLanding(run, event.getBlock().getLocation().toCenterLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Utils.mm("<red>You can only place that on the landing."));
        }
    }

    /**
     * Whether a spot is where the run's current level must land: inside its landing area, or without one, well below
     * the platform.
     */
    private boolean onLanding(Run run, Location at) {
        Region landing = arena().getArea(landingArea(run.level));
        return landing != null ? inside(landing, at) : at.getY() < platform(run).getY() - DROP_DISTANCE;
    }

    /** Boats, minecarts and the like are removed after the attempt. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Run run = attempting(event.getPlayer());
        if (run != null) run.entities.add(event.getEntity());
        // Not a clutch yet: getting in is (onMountClutch).
    }

    /** Containers open the player's own copy of what's inside, so nobody empties them for everyone else. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onOpenContainer(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!isPlaying(player) || !(block.getState() instanceof Container container)) return;
        if (player.isSneaking() && event.getItem() != null) return; // placing against it, as usual
        event.setCancelled(true);
        Run run = attempting(player);
        if (run == null || !arena().contains(block.getLocation())) return;
        Inventory copy = run.containers.computeIfAbsent(block, at -> copyOf(container));
        player.openInventory(copy);
        run.openContainer = block;
        run.waitedOnServer = true;
    }

    private static Inventory copyOf(Container container) {
        Inventory original = container.getInventory();
        Component title = container.customName() != null ? container.customName() : Component.translatable(container.getType());
        InventoryType type = original.getType();
        Inventory copy = type == InventoryType.CHEST || type == InventoryType.BARREL || type == InventoryType.SHULKER_BOX
                ? Bukkit.createInventory(null, original.getSize(), title)
                : Bukkit.createInventory(null, type, title);
        ItemStack[] contents = original.getContents();
        for (int slot = 0; slot < contents.length && slot < copy.getSize(); slot++) {
            if (contents[slot] != null) copy.setItem(slot, contents[slot].clone());
        }
        return copy;
    }

    /** Right-clicking an item frame gives a copy of its item instead of turning it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClickFrame(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame) || !isPlaying(event.getPlayer())) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) takeFromFrame(event.getPlayer(), frame);
    }

    /** Taking an item out of a frame gives the player a copy, once per attempt, and leaves the frame as it was. */
    private void takeFromFrame(Player player, ItemFrame frame) {
        Run run = attempting(player);
        ItemStack item = frame.getItem();
        if (run == null || item.getType().isAir()) return;
        if (!run.frames.add(frame.getUniqueId())) {
            player.sendActionBar(Utils.mm("<gray>You already took that."));
            return;
        }
        player.getInventory().addItem(item.clone());
        run.waitedOnServer = true;
        Utils.playSound(player, "entity.item_frame.remove_item", 1f, 1f);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Events (they fire for every player on the server, so each checks the player is in this match first)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A hit that would kill them is a miss, unless they hold a totem (which then saves them, as usual). Nobody takes
     * damage between attempts.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isPlaying(player)) return;
        Run run = attempting(player);
        if (run == null) {
            event.setCancelled(true);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            run.fallHits++;
            run.biggestFallHit = Math.max(run.biggestFallHit, event.getFinalDamage());
        }
        if (event.getDamageSource().getDamageType() == org.bukkit.damage.DamageType.ENDER_PEARL) {
            // Pearls are a clutch item here; landing one never hurts.
            event.setCancelled(true);
        } else if (fallGrace.hold(player, event, graceTicks(player, run))) {
            // A high-ping player's fall hit waits a tick or two for their clutch to reach the server; it comes back
            // through here if nothing broke their fall by then.
            return;
        } else if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
            miss(player, run, "fell into the void", true);
        } else if (player.getHealth() - event.getFinalDamage() <= 0) {
            if (holdsTotem(player)) return; // the totem saves them, the usual way
            event.setCancelled(true);
            miss(player, run, event.getCause() == EntityDamageEvent.DamageCause.FALL ? "didn't clutch it" : "died", true);
        } else if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            // Same damage, without the landing crunch and hurt sounds: cancelling the hit skips its sounds, so the
            // health is taken here instead, with the red flash and camera shake kept.
            event.setCancelled(true);
            player.setHealth(Math.max(0, player.getHealth() - event.getFinalDamage()));
            player.playHurtAnimation(0);
        }
    }

    /**
     * How long a player's fall hits wait (see {@link FallGrace}): by ping, 0, 1 or 2 ticks; and with any grace at all,
     * their whole round trip (capped) once they've waited on the server this attempt, since that cost them exactly that.
     */
    private int graceTicks(Player player, Run run) {
        int ping = player.getPing();
        int ticks = FallGrace.tierTicks(ping, getIntOption("ping-grace-1-tick"), getIntOption("ping-grace-2-ticks"));
        if (ticks > 0 && run.waitedOnServer) {
            ticks = Math.max(ticks, Math.min(getIntOption("interaction-grace-max-ticks"), FallGrace.roundTripTicks(ping)));
        }
        return ticks;
    }

    private static boolean holdsTotem(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    /** Leaving the arena mid-fall is a miss; walking out of it from the platform just puts them back. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!event.hasChangedBlock()) return;
        Run run = attempting(player);
        if (run == null || !isPlaying(player) || arena().isWithinBounds(event.getTo())) return;
        if (run.phase == Phase.FALLING) miss(player, run, "left the arena", false);
        event.setTo(platform(run));
    }

    /**
     * The map stays intact: players can't hurt mobs (a saddled pig is someone's clutch), armour stands and the like,
     * or break item frames and paintings. Punching an item frame takes a copy of its item, like right-clicking it.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHitEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player) return;
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) damager = shooter;
        if (!isPlaying(damager)) return;
        event.setCancelled(true);
        if (event.getEntity() instanceof ItemFrame frame && event.getDamager() instanceof Player player) takeFromFrame(player, frame);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        if (remover instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) remover = shooter;
        if (remover != null && isPlaying(remover)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHunger(FoodLevelChangeEvent event) {
        if (isPlaying(event.getEntity())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------------------------

    private Arena arena() {
        return Objects.requireNonNull(getArena(), "Clutch always has an arena");
    }
}
