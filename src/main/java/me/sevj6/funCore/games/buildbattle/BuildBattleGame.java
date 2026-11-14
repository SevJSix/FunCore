package me.sevj6.funCore.games.buildbattle;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import me.sevj6.funCore.arena.ArenaPoint;
import me.sevj6.funCore.arena.Region;
import me.sevj6.funCore.game.GameState;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.game.PlayMode;
import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Build Battle: everyone gets the same secret theme and the same time to build it in their own plot, in creative mode.
 * Then everyone flies from plot to plot rating each build 1 to 5 stars. The highest total wins; a tie means several
 * winners.
 *
 * <h2>Rules</h2>
 * <ul>
 *     <li>Free for all only, 2 or more players: one plot each, given at random.</li>
 *     <li>Building lasts {@code build-minutes}, in creative mode, with any block. Players can only build in, and can't
 *     leave, their own plot (up to {@code plot-height} blocks above its floor). Nothing reaches out of a plot: no
 *     water or lava flowing out, pistons or dispensers pushing out, trees growing out, explosions, thrown items or
 *     ender chests.</li>
 *     <li>Then the builds are shown one at a time, in random order, without saying whose they are, for
 *     {@code vote-seconds} each (cut short once everyone has voted). Players fly around it and click a star item in
 *     their hotbar to rate it; the last click counts. Nobody rates their own build. A missed vote gives nothing.</li>
 *     <li>Builders are revealed with the results. Highest total stars wins; tied players all win. If fewer than 2
 *     players are left at any point, the match ends without a winner.</li>
 *     <li>Themes come from {@code plugins/FunCore/buildbattle/themes.yml} (see {@link BuildBattleThemes}).</li>
 * </ul>
 *
 * <h2>Arena setup</h2>
 * One spawn per plot, standing anywhere on the plot's floor (the plot is the rectangle of that floor block around the
 * spawn: see {@link PlotFinder}; one smaller than 5x5 is refused, as the spawn is then on a border), or an area
 * {@code plot-N} for spawn N to give its bounds by hand. An optional point
 * {@code view-N} is where everyone watches plot N from while voting; otherwise it's just inside the plot's side that
 * faces open space (the walkway), looking in. The map resets from its snapshot after every match. The arena's lobby is
 * where players wait.
 */
public final class BuildBattleGame extends Minigame {

    public static final String ID = "buildbattle";
    private static final NamespacedKey VOTE_KEY = new NamespacedKey("funcore", "buildbattle_vote");
    /** How far outwards from a plot's side the walkway must stay clear of other plots, to view from that side. */
    private static final int VIEW_REACH = 10;
    /** A plot found from a spawn must be at least this wide each way (smaller means the spawn missed the floor). */
    private static final int MIN_PLOT = 5;
    /** Players may fly this far above their plot's top before they're stopped. */
    private static final int ABOVE_TOP = 2;
    /** Once everyone has voted on a build, it moves on after this long. */
    private static final long ALL_VOTED_TICKS = 60;
    private static final String[] STAR_COLORS = {"#ff4d4d", "#ff9933", "#ffd11a", "#a3e635", "#22c55e"};
    private static final Material[] STAR_ITEMS = {Material.RED_TERRACOTTA, Material.ORANGE_TERRACOTTA,
            Material.YELLOW_TERRACOTTA, Material.LIME_TERRACOTTA, Material.GREEN_TERRACOTTA};
    private static final String[] STAR_WORDS = {"Not great", "Okay", "Good", "Great", "Amazing"};

    public static final GameType TYPE = GameType.builder(ID, Utils.mm("<gold>Build Battle"), BuildBattleGame::new)
            .modes(PlayMode.FFA)
            .players(2, 16)
            .requiresArena()
            .pvpByDefault(false)
            .arenaDefaults(arena -> {
                arena.setPlaceAllowed(true);
                arena.setBreakAllowed(true);
                arena.setDropAllowed(false);
                arena.setPickupAllowed(false);
                arena.setFluidFlowAllowed(false);
                arena.setFallDamageAllowed(false);
            })
            .arenaCheck(BuildBattleGame::checkArena)
            .option("build-minutes", 10, 1, 60, "how long everyone has to build")
            .option("vote-seconds", 15, 5, 120, "how long everyone has to rate each build")
            .option("plot-height", 32, 4, 320, "how many blocks above its floor a plot goes")
            .build();

    private enum Phase { BUILDING, VOTING, DONE }

    /** A player's plot: its blocks (floor included) and where they start in it. */
    private record Plot(int number, Region region, Location spawn) {
        boolean holds(Location location) {
            return region.contains(location.getX(), location.getY(), location.getZ());
        }

        /** Inside the plot's columns, at or above its floor and not too far above its top. */
        boolean allows(Location location) {
            return location.getX() >= region.minX() && location.getX() < region.maxX() + 1
                    && location.getZ() >= region.minZ() && location.getZ() < region.maxZ() + 1
                    && location.getY() >= region.minY() && location.getY() < region.maxY() + 1 + ABOVE_TOP;
        }
    }

    // ---- per match ----
    private Phase phase = Phase.DONE;
    private final Map<UUID, Plot> plots = new LinkedHashMap<>();
    /** Every plot of the arena, taken or not (to tell which side of a plot faces the walkway). */
    private final List<Region> allPlots = new ArrayList<>();
    /** Builders' names, kept for the results even if they leave. */
    private final Map<UUID, String> names = new HashMap<>();
    private String theme = "";
    private long ticks, buildEndsAt;
    /** The builds in the order they're rated, the one being rated now, and when its time is up. */
    private final List<UUID> showOrder = new ArrayList<>();
    private int showing = -1;
    private long voteEndsAt;
    /** Stars each build got: builder, then voter, then 1 to 5. */
    private final Map<UUID, Map<UUID, Integer>> votes = new HashMap<>();
    private final List<UUID> winners = new ArrayList<>();
    private @Nullable BossBar bar;
    /** The previous match's theme in this arena, not picked twice in a row. Kept across matches. */
    private @Nullable String lastTheme;

    public BuildBattleGame(FunCore plugin, GameType type, @Nullable Arena arena) {
        super(plugin, type, arena);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Arena
    // ------------------------------------------------------------------------------------------------------------

    private static List<String> checkArena(Arena arena) {
        List<String> problems = new ArrayList<>();
        Region region = arena.getRegion();
        List<ArenaPoint> spawns = arena.getSpawnPoints();
        if (spawns.size() < 2) problems.add("a spawn standing in each plot, at least 2 (/arena addspawn)");
        if (!arena.isSnapshotCurrent()) problems.add("an up-to-date snapshot (/arena snapshot, once it's built), so builds are cleared after each match");
        if (region == null) return problems;
        for (int i = 0; i < spawns.size(); i++) {
            ArenaPoint spawn = spawns.get(i);
            if (!region.contains(spawn.x(), spawn.y(), spawn.z())) {
                problems.add("spawn " + (i + 1) + " is outside the region");
                return problems;
            }
        }
        World world = arena.getWorld();
        // Checked again when the world is loaded; and not while a match's builds are still there (a builder may have
        // dug out the block under a spawn).
        if (world == null || arena.isDirty() || arena.isRestoring()) return problems;
        List<Region> found = new ArrayList<>();
        for (int i = 0; i < spawns.size(); i++) {
            Region plot = plotRegion(arena, world, i, 1);
            if (plot == null) {
                problems.add("spawn " + (i + 1) + " isn't standing on a plot's floor");
                return problems;
            }
            // A plot this small means the spawn is on a border or decoration, not the plot's floor.
            if (arena.getArea("plot-" + (i + 1)) == null && (plot.sizeX() < MIN_PLOT || plot.sizeZ() < MIN_PLOT)) {
                ArenaPoint spawn = spawns.get(i);
                Material under = world.getBlockAt((int) Math.floor(spawn.x()), plot.minY(), (int) Math.floor(spawn.z())).getType();
                problems.add("spawn " + (i + 1) + " is standing on " + under.name().toLowerCase().replace('_', ' ') + ", which only makes a "
                        + plot.sizeX() + "x" + plot.sizeZ() + " plot; stand on the plot's floor (e.g. the grass) and add the spawns again"
                        + " (/arena clearspawns " + arena.getId() + ")");
                return problems;
            }
            for (int j = 0; j < found.size(); j++) {
                if (PlotFinder.overlap(found.get(j), plot)) {
                    problems.add("spawns " + (j + 1) + " and " + (i + 1) + " are in the same plot");
                    return problems;
                }
            }
            found.add(plot);
        }
        return problems;
    }

    /** Spawn {@code index}'s plot: its {@code plot-N} area, or the floor around it. {@code null} if it isn't on a floor. */
    private static @Nullable Region plotRegion(Arena arena, World world, int index, int height) {
        Region area = arena.getArea("plot-" + (index + 1));
        if (area != null) return area;
        Region bounds = arena.getRegion();
        if (bounds == null) return null;
        ArenaPoint spawn = arena.getSpawnPoints().get(index);
        int x = (int) Math.floor(spawn.x()), z = (int) Math.floor(spawn.z());
        int floorY = (int) Math.floor(spawn.y() + 0.01) - 1;
        Material floor = world.getBlockAt(x, floorY, z).getType();
        if (floor.isAir() || !floor.isSolid() || !bounds.contains(x, floorY, z)) return null;
        return PlotFinder.find(x, floorY, z, height, bounds, (fx, fz) -> world.getBlockAt(fx, floorY, fz).getType() == floor);
    }

    @Override
    protected int getCapacity() {
        Arena arena = getArena();
        return arena == null ? 0 : arena.getSpawnPoints().size();
    }

    @Override
    protected boolean teleportToSpawnsOnStart() {
        return false; // each player gets a plot of their own instead
    }

    @Override
    protected GameMode getPlayingGameMode() {
        return GameMode.CREATIVE;
    }

    @Override
    protected int getEndDelaySeconds() {
        return 12;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        Arena arena = getArena();
        World world = arena == null ? null : arena.getWorld();
        if (arena == null || world == null || arena.getRegion() == null) {
            broadcast(Utils.mm("<red>This arena isn't set up."));
            end();
            return;
        }
        int height = getIntOption("plot-height");
        List<Plot> free = new ArrayList<>();
        for (int i = 0; i < arena.getSpawnPoints().size(); i++) {
            Region region = plotRegion(arena, world, i, height);
            if (region == null) {
                broadcast(Utils.mm("<red>Plot <n> isn't set up right; see /arena info <id>.",
                        Placeholder.unparsed("n", String.valueOf(i + 1)), Placeholder.unparsed("id", arena.getId())));
                end();
                return;
            }
            free.add(new Plot(i + 1, region, arena.getSpawnPoints().get(i).toLocation(world)));
            allPlots.add(region);
        }
        Collections.shuffle(free);
        List<Player> players = new ArrayList<>(getPlayers());
        Collections.shuffle(players);
        for (int i = 0; i < players.size() && i < free.size(); i++) {
            Player player = players.get(i);
            Plot plot = free.get(i);
            plots.put(player.getUniqueId(), plot);
            names.put(player.getUniqueId(), player.getName());
            player.getInventory().clear();
            player.teleport(plot.spawn());
            setStartPoint(player, plot.spawn());
        }

        theme = BuildBattleThemes.pick(BuildBattleThemes.load(plugin.getDataFolder(), plugin.getSLF4JLogger()), lastTheme);
        lastTheme = theme;
        ticks = 0;
        buildEndsAt = getIntOption("build-minutes") * 1200L;
        phase = Phase.BUILDING;
        bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        for (Player player : getPlayers()) player.showBossBar(bar);
        updateBar();

        Utils.title(audience(), Utils.mm("<gold><theme>", Placeholder.unparsed("theme", theme)),
                Utils.mm("<gray>Build it! You have <white><time></white>.", Placeholder.unparsed("time", Utils.formatTime(buildEndsAt / 20))),
                10, 70, 15);
        broadcast(Utils.mm("<yellow>The theme is <gold><bold><theme></bold></gold>. You have <white><time></white> to build it in your plot, with any block.",
                Placeholder.unparsed("theme", theme), Placeholder.unparsed("time", Utils.formatTime(buildEndsAt / 20))));
        Utils.playSound(audience(), "ui.toast.challenge_complete", 0.7f, 1.2f);
        runTaskTimer(this::tick, 1L, 1L);
    }

    @Override
    protected void onEnd() {
        if (bar != null) {
            for (Player player : getPlayers()) player.hideBossBar(bar);
        }
        if (phase != Phase.DONE || names.isEmpty()) {
            // Ended early: someone left, or an admin stopped it.
            if (!names.isEmpty()) broadcast(Utils.mm("<gray>The match ended before the voting finished. The theme was <white><theme></white>.",
                    Placeholder.unparsed("theme", theme)));
            phase = Phase.DONE;
            return;
        }
        Map<UUID, Integer> totals = totals();
        List<UUID> ranked = new ArrayList<>(totals.keySet());
        ranked.sort(Comparator.comparingInt((UUID id) -> totals.get(id)).reversed());

        broadcast(Utils.mm("<gold>The theme was <white><theme></white>. Results:", Placeholder.unparsed("theme", theme)));
        for (int i = 0; i < ranked.size() && i < 10; i++) {
            UUID id = ranked.get(i);
            int place = placeOf(ranked, totals, id);
            Map<UUID, Integer> got = votes.getOrDefault(id, Map.of());
            broadcast(Utils.mm("<gray>#<place> <white><name></white>: <gold><total> ★</gold> <dark_gray>(<count> <unit>, plot <plot>)",
                    Placeholder.unparsed("place", String.valueOf(place)), Placeholder.unparsed("name", names.getOrDefault(id, "?")),
                    Placeholder.unparsed("total", String.valueOf(totals.get(id))),
                    Placeholder.unparsed("count", String.valueOf(got.size())), Placeholder.unparsed("unit", Utils.plural(got.size(), "vote", "votes")),
                    Placeholder.unparsed("plot", String.valueOf(plots.get(id).number()))));
        }
        List<String> winnerNames = winners.stream().map(id -> names.getOrDefault(id, "?")).toList();
        broadcast(Utils.mm(winners.size() == 1 ? "<gold><name> wins!" : "<gold><name> win, tied!",
                Placeholder.component("name", Component.join(JoinConfiguration.commas(true), winnerNames.stream().map(Component::text).toList()))));

        // Everyone gets to see the winning build.
        Plot best = winners.isEmpty() ? null : plots.get(winners.getFirst());
        Location view = best == null ? null : viewPoint(best);
        for (Player player : getPlayers()) {
            boolean won = winners.contains(player.getUniqueId());
            int place = ranked.contains(player.getUniqueId()) ? placeOf(ranked, totals, player.getUniqueId()) : 0;
            Utils.title(player, won ? Utils.mm("<gold>You win!") : Utils.mm(place > 0 ? "<yellow>#" + place : "<gray>Results"),
                    Utils.mm("<gray>Winning build by <white><name>", Placeholder.unparsed("name", String.join(", ", winnerNames))), 10, 80, 15);
            Utils.playSound(player, won ? "ui.toast.challenge_complete" : "entity.player.levelup", 1f, 1f);
            if (view != null) {
                player.teleport(view);
                setStartPoint(player, view);
                player.setAllowFlight(true);
                player.setFlying(true);
            }
        }
    }

    /** A player's place: 1 + how many scored more (so ties share a place). */
    private static int placeOf(List<UUID> ranked, Map<UUID, Integer> totals, UUID id) {
        int score = totals.get(id), place = 1;
        for (UUID other : ranked) {
            if (totals.get(other) > score) place++;
        }
        return place;
    }

    @Override
    protected void onReset() {
        phase = Phase.DONE;
        plots.clear();
        allPlots.clear();
        names.clear();
        showOrder.clear();
        showing = -1;
        votes.clear();
        winners.clear();
        theme = "";
        ticks = buildEndsAt = voteEndsAt = 0;
        bar = null;
    }

    @Override
    protected void onPlayerLeave(Player player, LeaveReason reason) {
        if (bar != null) player.hideBossBar(bar);
        if (getState() != GameState.RUNNING) return;
        if (getPlayerCount() < 2) {
            broadcast(Utils.mm("<red>Not enough players left to rate the builds."));
            end();
            return;
        }
        // Their build is skipped if it's up now.
        if (phase == Phase.VOTING && showing >= 0 && showing < showOrder.size() && showOrder.get(showing).equals(player.getUniqueId())) {
            showNext();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // The match
    // ------------------------------------------------------------------------------------------------------------

    private void tick() {
        if (getState() != GameState.RUNNING) return;
        ticks++;
        if (phase == Phase.BUILDING) {
            long left = buildEndsAt - ticks;
            if (left <= 0) {
                startVoting();
                return;
            }
            if (ticks % 10 == 0) updateBar();
            if (left % 20 == 0) {
                long seconds = left / 20;
                if (seconds == 300 || seconds == 60 || seconds == 30) {
                    broadcast(Utils.mm("<yellow><time> left to build!", Placeholder.unparsed("time", Utils.formatTime(seconds))));
                    Utils.playPling(audience(), 1f);
                } else if (seconds <= 10) {
                    Utils.title(audience(), Component.empty(), Utils.mm("<yellow><s>", Placeholder.unparsed("s", String.valueOf(seconds))), 0, 25, 0);
                    Utils.playPling(audience(), seconds <= 3 ? 1.5f : 1f);
                }
            }
        } else if (phase == Phase.VOTING) {
            if (ticks >= voteEndsAt) {
                showNext();
                return;
            }
            if (ticks % 10 == 0) {
                updateBar();
                showVoteStatus();
            }
        }
    }

    private void startVoting() {
        phase = Phase.VOTING;
        for (Player player : getPlayers()) {
            if (plots.containsKey(player.getUniqueId())) showOrder.add(player.getUniqueId());
        }
        Collections.shuffle(showOrder);
        for (Player player : getPlayers()) {
            player.closeInventory();
            player.getInventory().clear();
            player.setGameMode(GameMode.ADVENTURE);
            player.setAllowFlight(true);
            player.setFlying(true);
            for (int stars = 1; stars <= 5; stars++) player.getInventory().setItem(1 + stars, voteItem(stars));
            player.getInventory().setHeldItemSlot(4);
        }
        Utils.title(audience(), Utils.mm("<gold>Time's up!"), Utils.mm("<gray>Rate every build from 1 to 5 stars"), 5, 40, 10);
        broadcast(Utils.mm("<yellow>Time's up! Now rate each build: click a star in your hotbar. You can change your mind until the next build."));
        Utils.playSound(audience(), "block.note_block.bell", 1f, 1f);
        showing = -1;
        showNext();
    }

    /** Moves on to the next build to rate, or to the results after the last one. */
    private void showNext() {
        do {
            showing++;
        } while (showing < showOrder.size() && !isPlaying(showOrder.get(showing)));
        if (showing >= showOrder.size()) {
            finishVoting();
            return;
        }
        UUID builder = showOrder.get(showing);
        Plot plot = plots.get(builder);
        Location view = viewPoint(plot);
        voteEndsAt = ticks + getIntOption("vote-seconds") * 20L;
        for (Player player : getPlayers()) {
            player.teleport(view);
            setStartPoint(player, view);
            player.setFlying(true);
            boolean own = player.getUniqueId().equals(builder);
            Utils.title(player, Utils.mm("<gold>Build <n> of <total>", Placeholder.unparsed("n", String.valueOf(showing + 1)),
                            Placeholder.unparsed("total", String.valueOf(showOrder.size()))),
                    Utils.mm(own ? "<green>This is your build!" : "<gray>Theme: <white><theme></white>. Rate it!", Placeholder.unparsed("theme", theme)),
                    5, 40, 10);
        }
        Utils.playSound(audience(), "entity.enderman.teleport", 0.6f, 1.2f);
        updateBar();
    }

    private void finishVoting() {
        phase = Phase.DONE;
        Map<UUID, Integer> totals = totals();
        int best = totals.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        totals.forEach((id, total) -> {
            if (total == best) winners.add(id);
        });
        end();
    }

    /** Every rated builder's total stars (builders who left are dropped). */
    private Map<UUID, Integer> totals() {
        Map<UUID, Integer> totals = new LinkedHashMap<>();
        for (UUID builder : showOrder) {
            if (!isPlaying(builder)) continue;
            int total = 0;
            for (int stars : votes.getOrDefault(builder, Map.of()).values()) total += stars;
            totals.put(builder, total);
        }
        return totals;
    }

    private void vote(Player voter, int stars) {
        if (phase != Phase.VOTING || showing < 0 || showing >= showOrder.size()) return;
        UUID builder = showOrder.get(showing);
        if (builder.equals(voter.getUniqueId())) {
            voter.sendActionBar(Utils.mm("<red>You can't rate your own build."));
            return;
        }
        votes.computeIfAbsent(builder, id -> new HashMap<>()).put(voter.getUniqueId(), stars);
        voter.sendActionBar(Utils.mm("<gray>You rated it " + starText(stars) + " <gray>(" + STAR_WORDS[stars - 1] + ")"));
        Utils.playSound(voter, "block.note_block.pling", 0.8f, 0.6f + stars * 0.2f);
        // Everyone rated it: no need to wait out the timer.
        long voters = getPlayers().stream().filter(player -> !player.getUniqueId().equals(builder)).count();
        if (votes.get(builder).size() >= voters && voteEndsAt - ticks > ALL_VOTED_TICKS) voteEndsAt = ticks + ALL_VOTED_TICKS;
    }

    /** Where everyone watches a build from: its {@code view-N} point, or just inside its open side, looking in. */
    private Location viewPoint(Plot plot) {
        Arena arena = getArena();
        Location custom = arena == null ? null : arena.getPoint("view-" + plot.number());
        if (custom != null) return custom;
        Region region = plot.region();
        World world = plot.spawn().getWorld();
        PlotFinder.Side side = arena == null || arena.getRegion() == null ? null
                : PlotFinder.openSide(region, allPlots, arena.getRegion(), VIEW_REACH);
        if (side == null) side = PlotFinder.Side.SOUTH;
        double cx = (region.minX() + region.maxX() + 1) / 2.0, cz = (region.minZ() + region.maxZ() + 1) / 2.0;
        double x = side.dx > 0 ? region.maxX() - 0.5 : side.dx < 0 ? region.minX() + 1.5 : cx;
        double z = side.dz > 0 ? region.maxZ() - 0.5 : side.dz < 0 ? region.minZ() + 1.5 : cz;
        int width = Math.min(region.sizeX(), region.sizeZ());
        int y = Math.min(region.minY() + 1 + Math.max(4, (int) Math.round(width * 0.35)), region.maxY() - 1);
        // Above whatever was built there.
        while (y < region.maxY() - 1 && (!world.getBlockAt((int) Math.floor(x), y, (int) Math.floor(z)).isPassable()
                || !world.getBlockAt((int) Math.floor(x), y + 1, (int) Math.floor(z)).isPassable())) {
            y++;
        }
        Location view = new Location(world, x, y, z);
        Location target = new Location(world, cx, region.minY() + 3, cz);
        view.setDirection(target.toVector().subtract(view.toVector()));
        return view;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Showing the match
    // ------------------------------------------------------------------------------------------------------------

    private void updateBar() {
        if (bar == null) return;
        if (phase == Phase.BUILDING) {
            long left = Math.max(0, buildEndsAt - ticks);
            bar.name(Utils.mm("<gold>Theme: <white><theme></white> <dark_gray>|</dark_gray> <yellow><time></yellow> left",
                    Placeholder.unparsed("theme", theme), Placeholder.unparsed("time", Utils.formatTime((left + 19) / 20))));
            bar.progress(buildEndsAt <= 0 ? 0f : Math.clamp((float) left / buildEndsAt, 0f, 1f));
            bar.color(left <= 600 ? BossBar.Color.RED : BossBar.Color.YELLOW);
        } else if (phase == Phase.VOTING && showing < showOrder.size()) {
            long total = getIntOption("vote-seconds") * 20L, left = Math.max(0, voteEndsAt - ticks);
            bar.name(Utils.mm("<gold>Rating build <n> of <count> <dark_gray>|</dark_gray> <yellow><time></yellow>",
                    Placeholder.unparsed("n", String.valueOf(showing + 1)), Placeholder.unparsed("count", String.valueOf(showOrder.size())),
                    Placeholder.unparsed("time", Utils.formatTime((left + 19) / 20))));
            bar.progress(Math.clamp((float) left / total, 0f, 1f));
            bar.color(BossBar.Color.BLUE);
        }
    }

    private void showVoteStatus() {
        if (showing < 0 || showing >= showOrder.size()) return;
        UUID builder = showOrder.get(showing);
        Map<UUID, Integer> got = votes.getOrDefault(builder, Map.of());
        for (Player player : getPlayers()) {
            if (player.getUniqueId().equals(builder)) {
                player.sendActionBar(Utils.mm("<green>Your build <dark_gray>|</dark_gray> <gray>others are rating it"));
            } else {
                Integer stars = got.get(player.getUniqueId());
                player.sendActionBar(stars == null ? Utils.mm("<yellow>Click a star to rate this build")
                        : Utils.mm("<gray>Your rating: " + starText(stars)));
            }
        }
    }

    private static String starText(int stars) {
        return "<" + STAR_COLORS[stars - 1] + ">" + "★".repeat(stars) + "</" + STAR_COLORS[stars - 1] + "><dark_gray>" + "☆".repeat(5 - stars) + "</dark_gray>";
    }

    private static ItemStack voteItem(int stars) {
        return ItemBuilder.of(STAR_ITEMS[stars - 1])
                .amount(stars)
                .name("<!italic>" + starText(stars) + " <" + STAR_COLORS[stars - 1] + ">" + STAR_WORDS[stars - 1])
                .lore("<!italic><gray>Click to give this build " + stars + " " + (stars == 1 ? "star" : "stars") + ".")
                .meta(meta -> meta.getPersistentDataContainer().set(VOTE_KEY, PersistentDataType.INTEGER, stars))
                .build();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Building rules
    // ------------------------------------------------------------------------------------------------------------

    private boolean building() {
        return getState() == GameState.RUNNING && phase == Phase.BUILDING;
    }

    @Override
    protected boolean canBreak(Player player, Block block) {
        Plot plot = plots.get(player.getUniqueId());
        return building() && plot != null && plot.holds(block.getLocation());
    }

    @Override
    protected boolean canPlace(Player player, Block block) {
        return canBreak(player, block);
    }

    @Override
    protected boolean canDrop(Player player, ItemStack item) {
        return false;
    }

    @Override
    protected boolean canPickUp(Player player, ItemStack item) {
        return false;
    }

    /** The plot holding a block, if this match is building and it's in one. */
    private @Nullable Plot plotAt(Location location) {
        if (!building()) return null;
        for (Plot plot : plots.values()) {
            if (plot.holds(location)) return plot;
        }
        return null;
    }

    /** Whether a block is in this match's arena while it's building (where the rules below apply). */
    private boolean inArena(Location location) {
        Arena arena = getArena();
        return building() && arena != null && arena.contains(location);
    }

    /** Builders stay in their own plot (they may fly a little above it). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!building() || !event.hasChangedBlock()) return;
        Plot plot = plots.get(event.getPlayer().getUniqueId());
        if (plot == null || plot.allows(event.getTo())) return;
        if (plot.allows(event.getFrom())) {
            Location back = event.getFrom().clone();
            back.setYaw(event.getTo().getYaw());
            back.setPitch(event.getTo().getPitch());
            event.setTo(back);
        } else {
            event.setTo(plot.spawn());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!building() || event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        Plot plot = plots.get(event.getPlayer().getUniqueId());
        if (plot != null && !plot.allows(event.getTo())) event.setCancelled(true);
    }

    /** Beds and the like place more than one block: all of them must be in your own plot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMultiPlace(org.bukkit.event.block.BlockMultiPlaceEvent event) {
        if (!isPlaying(event.getPlayer())) return;
        Plot plot = plots.get(event.getPlayer().getUniqueId());
        boolean inside = building() && plot != null;
        for (org.bukkit.block.BlockState state : event.getReplacedBlockStates()) {
            if (!inside) break;
            inside = plot.holds(state.getLocation());
        }
        if (!inside) event.setCancelled(true);
    }

    /** Armour stands, boats, minecarts, end crystals: only in your own plot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceEntity(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || !isPlaying(player)) return;
        Plot plot = plots.get(player.getUniqueId());
        if (!building() || plot == null || !plot.holds(event.getEntity().getLocation())) event.setCancelled(true);
    }

    /** Paintings and item frames: only in your own plot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHang(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || !isPlaying(player)) return;
        Plot plot = plots.get(player.getUniqueId());
        if (!building() || plot == null || !plot.holds(event.getBlock().getRelative(event.getBlockFace()).getLocation())) event.setCancelled(true);
    }

    /**
     * Armour stands count as mobs, which the arena doesn't spawn; a builder's own armour stand in their plot is fine
     * (spawn eggs stay blocked).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onArmorStandSpawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.ArmorStand && inArena(event.getLocation()) && plotAt(event.getLocation()) != null) {
            event.setCancelled(false);
        }
    }

    /** Bone-mealed trees, mushrooms and flowers only grow inside the plot they're in. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(org.bukkit.event.world.StructureGrowEvent event) {
        if (!inArena(event.getLocation())) return;
        Plot plot = plotAt(event.getLocation());
        if (plot == null) event.setCancelled(true);
        else event.getBlocks().removeIf(state -> !plot.holds(state.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(org.bukkit.event.block.BlockFertilizeEvent event) {
        if (!inArena(event.getBlock().getLocation())) return;
        Plot plot = plotAt(event.getBlock().getLocation());
        if (plot == null) event.setCancelled(true);
        else event.getBlocks().removeIf(state -> !plot.holds(state.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (player == null || !isPlaying(player)) return;
        if (!canPlace(player, event.getBlock())) event.setCancelled(true);
    }

    /** Fluids (when the arena lets them flow) stay in their plot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (!inArena(event.getBlock().getLocation()) && !inArena(event.getToBlock().getLocation())) return;
        if (plotAt(event.getBlock().getLocation()) != plotAt(event.getToBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!inArena(event.getBlock().getLocation())) return;
        Plot plot = plotAt(event.getBlock().getLocation());
        boolean inside = plot != null && plot.holds(event.getBlock().getRelative(event.getDirection()).getLocation());
        for (Block block : event.getBlocks()) {
            if (!inside) break;
            inside = plot.holds(block.getLocation()) && plot.holds(block.getRelative(event.getDirection()).getLocation());
        }
        if (!inside) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!inArena(event.getBlock().getLocation())) return;
        Plot plot = plotAt(event.getBlock().getLocation());
        boolean inside = plot != null;
        for (Block block : event.getBlocks()) {
            if (!inside) break;
            inside = plot.holds(block.getLocation());
        }
        if (!inside) event.setCancelled(true);
    }

    /** Dispensers can't place water, shoot or drop things out of their plot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!inArena(event.getBlock().getLocation())) return;
        Plot plot = plotAt(event.getBlock().getLocation());
        Block target = event.getBlock().getBlockData() instanceof Directional directional
                ? event.getBlock().getRelative(directional.getFacing()) : event.getBlock();
        if (plot == null || !plot.holds(target.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        if (inArena(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (inArena(event.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (inArena(event.getBlock().getLocation())) event.setCancelled(true);
    }

    /** Nothing thrown or shot: it could land in someone else's plot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && isPlaying(player) && getState() == GameState.RUNNING) {
            event.setCancelled(true);
        }
    }

    /**
     * No ender chests: they're the one place creative items could be stored and taken out of the game (everything
     * else is reset with the player's inventory and the arena).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isPlaying(player)) return;
        // No ender chests; while building, nothing usable (doors, levers, chests, ...) outside your own plot; and once
        // building is over nobody touches the builds. (Denying a plain block would also stop placing against it.)
        Block clicked = event.getClickedBlock();
        if (clicked != null && getState() != GameState.WAITING) {
            Plot own = plots.get(player.getUniqueId());
            boolean outside = own == null || !own.holds(clicked.getLocation());
            if (clicked.getType() == Material.ENDER_CHEST || !building() || (outside && clicked.getType().isInteractable())) {
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            }
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta() || event.getHand() != EquipmentSlot.HAND) return;
        Integer stars = item.getItemMeta().getPersistentDataContainer().get(VOTE_KEY, PersistentDataType.INTEGER);
        if (stars == null) return;
        event.setCancelled(true);
        if (event.getAction() != Action.PHYSICAL) vote(player, stars);
    }

    /** After building, builds can't be changed: no taking items from frames, posing armour stands or hitting them. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (isPlaying(event.getPlayer()) && getState() != GameState.WAITING && !building()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(org.bukkit.event.player.PlayerArmorStandManipulateEvent event) {
        if (isPlaying(event.getPlayer()) && getState() != GameState.WAITING && !building()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHitEntity(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isPlaying(player) && !(event.getEntity() instanceof Player)
                && getState() != GameState.WAITING && !building()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreakHanging(org.bukkit.event.hanging.HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player && isPlaying(player) && getState() != GameState.WAITING && !building()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST && event.getPlayer() instanceof Player player && isPlaying(player)) {
            event.setCancelled(true);
        }
    }

    /** The star items stay put while voting. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isPlaying(player) && getState() != GameState.WAITING && phase != Phase.BUILDING) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isPlaying(event.getPlayer()) && phase == Phase.VOTING) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isPlaying(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (isPlaying(event.getEntity())) event.setCancelled(true);
    }
}
