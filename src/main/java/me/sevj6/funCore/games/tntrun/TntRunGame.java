package me.sevj6.funCore.games.tntrun;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import me.sevj6.funCore.arena.ArenaPoint;
import me.sevj6.funCore.arena.Region;
import me.sevj6.funCore.game.GameState;
import me.sevj6.funCore.game.GameTeam;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.game.PlayMode;
import me.sevj6.funCore.util.TimeFormat;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TNT Run: the floor falls away a moment after you step on it, so keep moving. Fall through the bottom of the arena
 * and you're out (and watch as a spectator). The last one standing wins; played alone, it's how long you lasted.
 *
 * <h2>Rules</h2>
 * <ul>
 *     <li>The arena is picked when the countdown ends ({@link GameType#picksArenaAtStart()}), by how many are playing:
 *     {@code small-arena-players} (2) or fewer get a small arena, more get a large one. If the arenas of that size are
 *     all in use, the match waits for one; if the game has none of that size, any arena does. Players queue wherever
 *     they are and are sent to the arena's spawns at the start.</li>
 *     <li>After {@code grace-seconds} (3), every block a player stands on (or jumps from) goes {@code fall-delay-ticks}
 *     (8, 0.4 s) later. Only blocks inside the arena's region go; it's put back from the snapshot after the match.</li>
 *     <li>Anyone below the bottom of the region is out. Hits do no damage, only knockback (PvP is on by default), so
 *     shove people into the holes. Nothing else hurts, and nobody gets hungry.</li>
 *     <li>The match ends when one player is left (they win), or with everyone gone (the last ones to fall, together).
 *     Played alone, it ends when you fall.</li>
 * </ul>
 *
 * <h2>Arena setup</h2>
 * A region whose bottom is a few blocks below the floor (that's where players are out), at least one spawn on the floor
 * and an up-to-date snapshot (the floor comes back from it). Whether an arena is small or large is worked out from its
 * region's width ({@code arena-size: auto}), or set per arena with {@code /arena option <arena> arena-size small|large}.
 */
public final class TntRunGame extends Minigame {

    public static final String ID = "tntrun";
    /** A hit this recent counts as knocking someone off. */
    private static final long KNOCK_CREDIT_TICKS = 100;
    /** Half a player's hitbox width. */
    private static final double HALF_WIDTH = 0.3;

    public static final GameType TYPE = GameType.builder(ID, Utils.mm("<red>TNT Run"), TntRunGame::new)
            .modes(PlayMode.SINGLEPLAYER, PlayMode.FFA)
            .players(2, 16)
            .dynamicByDefault()
            .picksArenaAtStart()
            .pvpByDefault(true)
            .arenaDefaults(arena -> {
                arena.setBreakAllowed(false);
                arena.setPlaceAllowed(false);
                arena.setPickupAllowed(false);
                arena.setDropAllowed(false);
            })
            .arenaCheck(TntRunGame::checkArena)
            .option("fall-delay-ticks", 8, 1, 100, "ticks a floor block lasts after someone steps on it (20 = 1 second)")
            .option("grace-seconds", 3, 0, 30, "seconds after the start before the floor starts falling away")
            .option("small-arena-players", 2, 1, 100, "matches with at most this many players get a small arena, bigger ones a large arena")
            .option("arena-size", ArenaSizes.AUTO, ArenaSizes::check,
                    "auto, small or large; set it per arena (/arena option). auto = small if the region is at most "
                            + ArenaSizes.AUTO_SMALL_MAX_WIDTH + " blocks across")
            .build();

    // ---- per match ----
    /** Players still on the floor. */
    private final Set<UUID> alive = new LinkedHashSet<>();
    /** Players who fell, in order, and the match tick each fell at. */
    private final List<UUID> outOrder = new ArrayList<>();
    private final Map<UUID, Long> outAt = new HashMap<>();
    /** Who last hit each player, and when (match tick), to credit a knock off the floor. */
    private final Map<UUID, UUID> lastHitBy = new HashMap<>();
    private final Map<UUID, Long> lastHitAt = new HashMap<>();
    /** Players who fell this tick: if they were the last ones, they win together. */
    private final List<Player> fellThisTick = new ArrayList<>();
    private final List<UUID> winners = new ArrayList<>();
    private final FloorDecay decay = new FloorDecay();
    private long matchTicks;
    private int graceTicks, fallDelay;
    /** Whether only one player started: then it's a solo run, timed, not a fight to be last. */
    private boolean solo;
    /** Where the fallen watch from: above the middle of the arena. */
    private @Nullable Location spectatorSpot;

    public TntRunGame(FunCore plugin, GameType type, @Nullable Arena arena) {
        super(plugin, type, arena);
    }

    private static List<String> checkArena(Arena arena) {
        List<String> problems = new ArrayList<>();
        Region region = arena.getRegion();
        if (arena.getSpawnPoints().isEmpty()) problems.add("no spawns (/arena addspawn, on the floor)");
        // Without one, the floor that fell would never come back.
        if (!arena.isSnapshotCurrent()) problems.add("no up-to-date snapshot (/arena snapshot, once it's built)");
        if (region != null) {
            for (ArenaPoint spawn : arena.getSpawnPoints()) {
                if (!region.contains(spawn.x(), spawn.y(), spawn.z())) {
                    problems.add("a spawn is outside the region");
                    break;
                }
                if (spawn.y() < region.minY() + 1) {
                    problems.add("a spawn is at the bottom of the region (players are out below it: set pos1/pos2 a few blocks under the floor)");
                    break;
                }
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Picking the arena
    // ------------------------------------------------------------------------------------------------------------

    @Override
    protected @Nullable Arena chooseArena(List<Arena> ready, List<Arena> free) {
        return ArenaSizes.pick(ready, free, this::isSmall, wantsSmallArena(), Minigame::randomArena);
    }

    /** Someone joined or left after the arena was picked: still the right size? */
    @Override
    protected boolean keepsArena(Arena picked) {
        return ArenaSizes.fits(plugin.getGameManager().readyArenas(getType()), picked, this::isSmall, wantsSmallArena());
    }

    private boolean wantsSmallArena() {
        // Read from the game's settings: an arena's own value for this wouldn't make sense (it's about choosing one).
        return getPlayerCount() <= getSettings().getInt("small-arena-players");
    }

    private boolean isSmall(Arena arena) {
        Region region = arena.getRegion();
        return region != null && ArenaSizes.isSmall(getStringOption(arena, "arena-size"), region.sizeX(), region.sizeZ());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        Arena arena = getArena();
        Region region = arena == null ? null : arena.getRegion();
        World world = arena == null ? null : arena.getWorld();
        if (region == null || world == null) {
            broadcast(Utils.mm("<red>This arena isn't set up."));
            end();
            return;
        }
        solo = getTeams().size() <= 1;
        graceTicks = getIntOption("grace-seconds") * 20;
        fallDelay = getIntOption("fall-delay-ticks");
        spectatorSpot = spectatorSpot(arena, world, region);
        for (Player player : getPlayers()) {
            alive.add(player.getUniqueId());
            player.getInventory().clear();
            Utils.heal(player);
            player.setFoodLevel(20);
            player.setSaturation(20f);
            player.setFallDistance(0f);
        }
        Utils.title(audience(), Utils.mm("<red>TNT Run"),
                Utils.mm(solo ? "<gray>How long can you keep running?" : "<gray>Keep moving. Last one standing wins!"), 5, 40, 10);
        broadcast(Utils.mm("<gray>Arena: <white><arena></white> <dark_gray>(<size>)</dark_gray>. "
                        + "The floor falls away a moment after you step on it; drop through and you're out.",
                Placeholder.unparsed("arena", prettyName(arena.getId())),
                Placeholder.unparsed("size", isSmall(arena) ? "small" : "large")));
        runTaskTimer(this::tick, 1L, 1L);
    }

    @Override
    protected void onEnd() {
        if (alive.isEmpty() && outOrder.isEmpty()) return; // stopped before it began
        // Best first: the winners, then anyone else still standing, then whoever lasted longest.
        List<UUID> ranked = new ArrayList<>(winners);
        for (UUID id : alive) {
            if (!ranked.contains(id)) ranked.add(id);
        }
        for (int i = outOrder.size() - 1; i >= 0; i--) {
            if (!ranked.contains(outOrder.get(i))) ranked.add(outOrder.get(i));
        }

        if (solo) {
            Player player = ranked.isEmpty() ? null : Bukkit.getPlayer(ranked.getFirst());
            if (player != null) {
                Utils.title(player, Utils.mm("<red>You fell!"), Utils.mm("<gray>You lasted <white><time>",
                        Placeholder.unparsed("time", lasted(player.getUniqueId()))), 10, 60, 10);
                Utils.playSound(player, "entity.wither.death", 1f, 1f);
            }
            return;
        }

        List<Player> winning = online(winners);
        if (winning.isEmpty()) {
            broadcast(Utils.mm("<gray>Nobody won."));
        } else {
            GameTeam team = getTeam(winning.getFirst());
            Component name = getMode().isTeams() && team != null ? team.getName() : names(winning);
            broadcast(Utils.mm(winning.size() == 1 || getMode().isTeams() ? "<gold><name> wins!" : "<gold><name> win together!",
                    Placeholder.component("name", name)));
        }
        for (int i = 0; i < ranked.size() && i < 5; i++) {
            Player player = Bukkit.getPlayer(ranked.get(i));
            if (player == null) continue;
            broadcast(Utils.mm("<gray>#<place> <white><name></white>: <result>",
                    Placeholder.unparsed("place", String.valueOf(i + 1)), Placeholder.component("name", player.name()),
                    Placeholder.unparsed("result", (alive.contains(player.getUniqueId()) ? "still standing after " : "lasted ")
                            + lasted(player.getUniqueId()))));
        }
        for (Player player : getPlayers()) {
            boolean won = winners.contains(player.getUniqueId());
            int place = ranked.indexOf(player.getUniqueId()) + 1;
            Utils.title(player, won ? Utils.mm("<gold>You win!") : Utils.mm("<yellow>#<place>", Placeholder.unparsed("place", String.valueOf(place))),
                    Utils.mm("<gray>You lasted <white><time>", Placeholder.unparsed("time", lasted(player.getUniqueId()))), 10, 60, 10);
            Utils.playSound(player, won ? "ui.toast.challenge_complete" : "entity.wither.death", 1f, 1f);
        }
    }

    @Override
    protected void onReset() {
        alive.clear();
        outOrder.clear();
        outAt.clear();
        lastHitBy.clear();
        lastHitAt.clear();
        fellThisTick.clear();
        winners.clear();
        decay.clear();
        matchTicks = 0;
        graceTicks = fallDelay = 0;
        solo = false;
        spectatorSpot = null;
    }

    @Override
    protected void onPlayerLeave(Player player, LeaveReason reason) {
        if (getState() != GameState.RUNNING) return;
        if (alive.remove(player.getUniqueId())) {
            outOrder.add(player.getUniqueId());
            outAt.put(player.getUniqueId(), matchTicks);
            checkEnd(List.of());
        }
    }

    // Nothing to build, break, drop or pick up: it's adventure mode on a bare floor.

    @Override
    protected GameMode getPlayingGameMode() {
        return GameMode.ADVENTURE;
    }

    @Override
    protected boolean canBreak(Player player, Block block) {
        return false;
    }

    @Override
    protected boolean canPlace(Player player, Block block) {
        return false;
    }

    @Override
    protected boolean canDrop(Player player, ItemStack item) {
        return false;
    }

    @Override
    protected boolean canPickUp(Player player, ItemStack item) {
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // The floor
    // ------------------------------------------------------------------------------------------------------------

    private void tick() {
        if (getState() != GameState.RUNNING) return;
        matchTicks++;
        long untilFalling = graceTicks - matchTicks;
        if (untilFalling > 0) {
            if (untilFalling % 20 == 0) {
                Utils.title(audience(), Component.empty(), Utils.mm("<yellow>The floor falls in <white><s>",
                        Placeholder.unparsed("s", String.valueOf(untilFalling / 20))), 0, 25, 0);
                Utils.playPling(audience(), 1f);
            }
        } else {
            if (untilFalling == 0 && graceTicks > 0) {
                Utils.title(audience(), Component.empty(), Utils.mm("<red><bold>Run!"), 0, 20, 10);
                Utils.playPling(audience(), 2f);
            }
            markFloor();
        }
        dropFloor();
        catchFallers();
        if (getState() != GameState.RUNNING) return;
        if (matchTicks % 10 == 0 && untilFalling <= 0) showStatus();
        if (matchTicks % 20 == 0) {
            for (Player player : online(alive)) {
                player.setFoodLevel(20);
                player.setSaturation(20f);
            }
        }
    }

    /** Marks the blocks under everyone still in to go {@code fall-delay-ticks} from now. */
    private void markFloor() {
        Arena arena = getArena();
        Region region = arena == null ? null : arena.getRegion();
        World world = arena == null ? null : arena.getWorld();
        if (region == null || world == null) return;
        for (Player player : online(alive)) {
            Location at = player.getLocation();
            for (long key : FloorDecay.under(at.getX(), at.getY(), at.getZ(), HALF_WIDTH)) {
                int x = FloorDecay.x(key), y = FloorDecay.y(key), z = FloorDecay.z(key);
                if (!region.contains(x, y, z) || world.getBlockAt(x, y, z).getType().isAir()) continue;
                decay.mark(key, matchTicks + fallDelay);
            }
        }
    }

    /** Removes the blocks whose time is up, with a puff and a crumble. */
    private void dropFloor() {
        Arena arena = getArena();
        World world = arena == null ? null : arena.getWorld();
        if (world == null) return;
        for (long key : decay.due(matchTicks)) {
            Block block = world.getBlockAt(FloorDecay.x(key), FloorDecay.y(key), FloorDecay.z(key));
            if (block.getType().isAir()) continue;
            var data = block.getBlockData();
            Location middle = block.getLocation().add(0.5, 0.5, 0.5);
            block.setType(org.bukkit.Material.AIR, false);
            world.spawnParticle(Particle.BLOCK, middle, 6, 0.3, 0.3, 0.3, 0, data);
            world.playSound(middle, data.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 0.35f, 1.2f);
        }
    }

    /** Takes out anyone still in who's below the region (in case no move event caught them), then sees if it's over. */
    private void catchFallers() {
        Arena arena = getArena();
        Region region = arena == null ? null : arena.getRegion();
        if (region != null) {
            for (Player player : online(alive)) {
                if (player.getLocation().getY() < region.minY()) player.teleport(eliminate(player));
            }
        }
        if (fellThisTick.isEmpty()) return;
        List<Player> fell = List.copyOf(fellThisTick);
        fellThisTick.clear();
        checkEnd(fell);
    }

    /**
     * Takes a player out: they spectate from above the arena until the match ends. Whether that ends the match is
     * checked at the end of the tick, so everyone who fell in the same tick is counted together.
     *
     * @return where they watch from (the caller moves them there)
     */
    private Location eliminate(Player player) {
        UUID id = player.getUniqueId();
        alive.remove(id);
        outOrder.add(id);
        outAt.put(id, matchTicks);
        fellThisTick.add(player);

        UUID by = lastHitBy.get(id);
        Long at = lastHitAt.get(id);
        Player knocker = by != null && at != null && matchTicks - at <= KNOCK_CREDIT_TICKS ? Bukkit.getPlayer(by) : null;
        if (!solo) {
            broadcast(knocker != null && !knocker.equals(player)
                    ? Utils.mm("<red><name> was knocked through the floor by <knocker>! <gray>(<left> left)",
                    Placeholder.component("name", player.name()), Placeholder.component("knocker", knocker.name()),
                    Placeholder.unparsed("left", String.valueOf(alive.size())))
                    : Utils.mm("<red><name> fell! <gray>(<left> left)",
                    Placeholder.component("name", player.name()), Placeholder.unparsed("left", String.valueOf(alive.size()))));
        }
        Utils.playSound(audience(), "entity.generic.explode", 0.4f, 1.4f);

        player.setFallDistance(0f);
        Utils.heal(player);
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
        Location spot = spectatorSpot != null ? spectatorSpot.clone() : player.getLocation();
        setStartPoint(player, spot);
        if (!solo && alive.size() > 1) {
            player.sendMessage(Utils.prefixed(Utils.mm("<gray>You're out. Spectating until the match ends; <white>/game leave</white> to go.")));
        }
        return spot;
    }

    /**
     * Ends the match if it's decided: a solo run once its player is out; otherwise once one team (or none) is left.
     *
     * @param lastFell who fell just now, who win together if nobody else is left
     */
    private void checkEnd(List<Player> lastFell) {
        if (getState() != GameState.RUNNING) return;
        if (solo) {
            if (alive.isEmpty()) end();
            return;
        }
        Set<GameTeam> standing = new LinkedHashSet<>();
        for (Player player : online(alive)) {
            GameTeam team = getTeam(player);
            if (team != null) standing.add(team);
        }
        if (standing.size() > 1) return;
        if (standing.isEmpty()) {
            for (Player player : lastFell) winners.add(player.getUniqueId());
        } else {
            for (Player player : getPlayers(standing.iterator().next())) winners.add(player.getUniqueId());
        }
        end();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Showing the match
    // ------------------------------------------------------------------------------------------------------------

    private void showStatus() {
        long ticks = Math.max(0, matchTicks - graceTicks);
        audience().sendActionBar(Utils.mm(solo ? "<gold>Time <white><time>"
                        : "<gold>Time <white><time></white>  <dark_gray>|</dark_gray>  <gray><left> left",
                Placeholder.unparsed("time", Utils.formatTime(ticks / 20)),
                Placeholder.unparsed("left", String.valueOf(alive.size()))));
    }

    /** How long a player lasted once the floor started falling: until they fell, or until now. */
    private String lasted(UUID id) {
        long until = outAt.getOrDefault(id, matchTicks);
        return TimeFormat.ticksWithMillis(Math.max(0, until - graceTicks));
    }

    /**
     * Above the middle of the region, a few blocks over the highest spawn, looking down. Never above the region's
     * top, so the framework keeps spectators over the arena (it only does for a start point inside the region).
     */
    private static Location spectatorSpot(Arena arena, World world, Region region) {
        double top = region.minY() + 1;
        for (Location spawn : arena.getSpawns()) top = Math.max(top, spawn.getY());
        double y = Math.max(top, Math.min(top + 8, region.maxY()));
        double x = (region.minX() + region.maxX() + 1) / 2.0, z = (region.minZ() + region.maxZ() + 1) / 2.0;
        return new Location(world, x, y, z, 0f, 60f);
    }

    /** "neon_arcade" as "Neon Arcade". */
    static String prettyName(String id) {
        StringBuilder name = new StringBuilder();
        for (String word : id.replace('-', '_').split("_")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return name.isEmpty() ? id : name.toString();
    }

    private List<Player> online(Iterable<UUID> ids) {
        List<Player> players = new ArrayList<>();
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && isPlaying(player)) players.add(player);
        }
        return players;
    }

    private static Component names(List<Player> players) {
        return Component.join(JoinConfiguration.commas(true), players.stream().map(Player::name).toList());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Events (they fire for every player on the server, so each checks isPlaying first)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Dropping below the region takes a player out straight away. This runs before the framework's "keep them in the
     * arena" check, which would otherwise send them back to their spawn; by then their start point is the spectator
     * spot, so that check is happy.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (getState() != GameState.RUNNING || !alive.contains(player.getUniqueId()) || !isPlaying(player)) return;
        Arena arena = getArena();
        Region region = arena == null ? null : arena.getRegion();
        if (region == null || event.getTo().getY() >= region.minY()) return;
        event.setTo(eliminate(player));
    }

    /**
     * Nothing hurts (but /kill and the void). A hit from another player still in (that PvP allows: the framework cancels the rest first) lands
     * with its knockback, just without the damage.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !isPlaying(victim)) return;
        // /kill and the void still work (players queue out in the world, not in an arena lobby).
        if (event.getCause() == EntityDamageEvent.DamageCause.KILL || event.getCause() == EntityDamageEvent.DamageCause.VOID) return;
        Player attacker = event instanceof EntityDamageByEntityEvent byEntity
                ? byEntity.getDamager() instanceof Player player ? player
                : byEntity.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null
                : null;
        if (attacker == null || event.isCancelled() || getState() != GameState.RUNNING || !alive.contains(victim.getUniqueId())
                || !alive.contains(attacker.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        event.setDamage(0);
        lastHitBy.put(victim.getUniqueId(), attacker.getUniqueId());
        lastHitAt.put(victim.getUniqueId(), matchTicks);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHunger(FoodLevelChangeEvent event) {
        if (isPlaying(event.getEntity())) event.setCancelled(true);
    }
}
