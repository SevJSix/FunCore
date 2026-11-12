package me.sevj6.funCore.games.risingtide;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import me.sevj6.funCore.arena.Region;
import me.sevj6.funCore.game.GameState;
import me.sevj6.funCore.game.GameTeam;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.game.PlayMode;
import me.sevj6.funCore.kit.Kit;
import me.sevj6.funCore.kit.KitItem;
import me.sevj6.funCore.leaderboard.Leaderboard;
import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Rising Tide: lava rises through the arena one layer at a time. Build and climb to stay above it; touch it and you're
 * out (and watch as a spectator). The last player (or team) left wins; played alone, it's how high you got.
 *
 * <h2>Rules</h2>
 * <ul>
 *     <li>Everyone starts with the main kit ({@code /game kit risingtide}): a knockback stick, blocks and ladders.
 *     Every {@code refill-every-layers} layers, everyone still in gets the {@code refill} kit added to what they have.</li>
 *     <li>Hits do no damage, only knockback (when PvP is on): knock people off their towers. No other damage
 *     either, and no hunger. The lava is the only way out.</li>
 *     <li>After {@code start-delay-seconds} to build, the lowest layer of the arena with air in it fills with lava,
 *     then the next one every {@code rise-interval-seconds}, up to the top of the region. Lava only replaces air (and
 *     things like grass), so the map and players' blocks stay; it doesn't flow (arena fluid flow is off).</li>
 *     <li>You're out when you touch lava, or when the lava's top layer is above your feet (no hiding in a sealed
 *     box under it).</li>
 *     <li>The match ends when one player (or team) is left, or when the lava reaches the top: everyone still in wins.
 *     If the last ones are caught at the same moment, they win together.</li>
 * </ul>
 *
 * <h2>Arena setup</h2>
 * A region (the lava fills it from the bottom up, so its height is the match's length) with at least one spawn.
 * Nothing about the layout is fixed: the lava starts at the lowest layer with air and stops at the region's top.
 */
public final class RisingTideGame extends Minigame {

    public static final String ID = "risingtide";
    public static final String REFILL_KIT = "refill";
    /** Every player's 2 highest layers reached, in any mode. */
    public static final Leaderboard.Definition LEADERBOARD = new Leaderboard.Definition("risingtide", "<gold><bold>Rising Tide: Highest Layers", 2);
    /** Lava blocks placed per tick at most, so a big arena's layer is spread over a few ticks. */
    private static final int BLOCKS_PER_TICK = 4096;
    /** A hit this recent counts as knocking someone into the lava. */
    private static final long KNOCK_CREDIT_TICKS = 100;

    public static final GameType TYPE = GameType.builder(ID, Utils.mm("<gold>Rising Tide"), RisingTideGame::new)
            .modes(PlayMode.SINGLEPLAYER, PlayMode.FFA, PlayMode.DUOS, PlayMode.TRIOS, PlayMode.SQUADS)
            .players(2, 16)
            .dynamicByDefault()
            .requiresArena()
            .pvpByDefault(true)
            .kit(RisingTideGame::defaultKit)
            .kit(REFILL_KIT, RisingTideGame::defaultRefill)
            .arenaDefaults(arena -> {
                arena.setPlaceAllowed(true);
                arena.setBreakAllowed(false);
                arena.setFluidFlowAllowed(false);
            })
            .arenaCheck(RisingTideGame::checkArena)
            .option("start-delay-seconds", 20, 0, 300, "time to build before the first layer of lava")
            .option("rise-interval-seconds", 5.0, 0.5, 120, "seconds between layers of lava")
            .option("refill-every-layers", 4, 0, 1000, "0 = never; everyone still in gets the refill kit every this many layers")
            .build();

    // ---- per match ----
    /** Players still in (not caught by the lava). */
    private final Set<UUID> alive = new LinkedHashSet<>();
    /** The layer each player was caught at (1 = the first), in the order they went out. */
    private final Map<UUID, Integer> outAt = new HashMap<>();
    private final List<UUID> outOrder = new ArrayList<>();
    /** Who last hit each player, and when (match tick), to credit a knock into the lava. */
    private final Map<UUID, UUID> lastHitBy = new HashMap<>();
    private final Map<UUID, Long> lastHitAt = new HashMap<>();
    private @Nullable TideSchedule schedule;
    private long matchTicks;
    /** Whether only one team started: then it's a solo run, scored by height, not a fight to be last. */
    private boolean solo;
    /** The y the lava starts at, and how many layers it has (up to the region's top). */
    private int firstY, totalLayers;
    /** Layers started; the lava's top layer is {@code firstY + risen - 1}. */
    private int risen;
    /** The highest layer that's completely filled, or {@link Integer#MIN_VALUE} before the first. */
    private int filledY = Integer.MIN_VALUE;
    /** Layers waiting to be filled (in order), and how far into the first of them the filling got. */
    private final List<Integer> pendingLayers = new ArrayList<>();
    private int fillCursor;
    /** Winners, set when the match ends (empty for none). */
    private final List<UUID> winners = new ArrayList<>();

    public RisingTideGame(FunCore plugin, GameType type, @Nullable Arena arena) {
        super(plugin, type, arena);
    }

    static Kit defaultKit() {
        ItemStack stick = ItemBuilder.of(Material.STICK)
                .name("<gold>Shove Stick")
                .lore("<gray>Knock others off their towers.")
                .meta(meta -> meta.addEnchant(Enchantment.KNOCKBACK, 1, true))
                .build();
        return Kit.builder()
                .slot(0, stick)
                .slot(1, new ItemStack(Material.COBBLESTONE, 64))
                .slot(2, new ItemStack(Material.LADDER, 8))
                .build();
    }

    static Kit defaultRefill() {
        return Kit.builder().slot(0, new ItemStack(Material.COBBLESTONE, 16)).build();
    }

    private static List<String> checkArena(Arena arena) {
        List<String> problems = new ArrayList<>();
        Region region = arena.getRegion();
        if (arena.getSpawnPoints().isEmpty()) problems.add("no spawns (/arena addspawn)");
        if (region != null) {
            for (var spawn : arena.getSpawnPoints()) {
                if (!region.contains(spawn.x(), spawn.y(), spawn.z())) {
                    problems.add("a spawn is outside the region");
                    break;
                }
            }
        }
        return problems;
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
        firstY = lowestOpenLayer(world, region);
        totalLayers = region.maxY() - firstY + 1;
        solo = getTeams().size() <= 1;
        for (Player player : getPlayers()) {
            alive.add(player.getUniqueId());
            giveKit(player, UnaryOperator.identity());
            Utils.heal(player);
            player.setFoodLevel(20);
        }
        long delay = getSettings().getInt("start-delay-seconds") * 20L;
        schedule = new TideSchedule(delay, Utils.secondsToTicks(getSettings().getDouble("rise-interval-seconds")));
        Utils.title(audience(), Utils.mm("<gold>Rising Tide"), Utils.mm(solo ? "<gray>Climb as high as you can!" : "<gray>Last one above the lava wins!"), 10, 50, 10);
        broadcast(Utils.mm("<yellow>Build up! The lava starts rising in <white><time></white>.",
                Placeholder.unparsed("time", Utils.formatTime(delay / 20))));
        runTaskTimer(this::tick, 1L, 1L);
    }

    @Override
    protected void onEnd() {
        if (alive.isEmpty() && outOrder.isEmpty()) return; // stopped before it began
        // Best first: the winners (or everyone still in), then whoever lasted longest.
        List<UUID> ranked = new ArrayList<>(winners.isEmpty() ? alive : winners);
        for (UUID id : alive) {
            if (!ranked.contains(id)) ranked.add(id);
        }
        for (int i = outOrder.size() - 1; i >= 0; i--) {
            if (!ranked.contains(outOrder.get(i))) ranked.add(outOrder.get(i));
        }
        for (UUID id : alive) record(id, risen); // the ones still standing reached the lava's current top

        if (solo) {
            Player player = ranked.isEmpty() ? null : org.bukkit.Bukkit.getPlayer(ranked.getFirst());
            if (player != null) {
                int layer = layerOf(player.getUniqueId());
                boolean top = alive.contains(player.getUniqueId());
                Utils.title(player, Utils.mm(top ? "<gold>You beat the tide!" : "<red>Caught by the lava"),
                        Utils.mm("<gray>Reached layer <white><layer></white> of <total>",
                                Placeholder.unparsed("layer", String.valueOf(layer)), Placeholder.unparsed("total", String.valueOf(totalLayers))), 10, 60, 10);
                Utils.playSound(player, top ? "ui.toast.challenge_complete" : "entity.wither.death", 1f, 1f);
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
            Player player = org.bukkit.Bukkit.getPlayer(ranked.get(i));
            if (player == null) continue;
            broadcast(Utils.mm("<gray>#<place> <white><name></white>: <result>",
                    Placeholder.unparsed("place", String.valueOf(i + 1)), Placeholder.component("name", player.name()),
                    Placeholder.unparsed("result", alive.contains(player.getUniqueId())
                            ? "still standing at layer " + risen : "out at layer " + layerOf(player.getUniqueId()))));
        }
        for (Player player : getPlayers()) {
            boolean won = winners.contains(player.getUniqueId());
            int place = ranked.indexOf(player.getUniqueId()) + 1;
            Utils.title(player, won ? Utils.mm("<gold>You win!") : Utils.mm("<yellow>#<place>", Placeholder.unparsed("place", String.valueOf(place))),
                    Utils.mm("<gray>Reached layer <white><layer></white> of <total>",
                            Placeholder.unparsed("layer", String.valueOf(layerOf(player.getUniqueId()))),
                            Placeholder.unparsed("total", String.valueOf(totalLayers))), 10, 60, 10);
            Utils.playSound(player, won ? "ui.toast.challenge_complete" : "entity.wither.death", 1f, 1f);
        }
    }

    @Override
    protected void onReset() {
        alive.clear();
        outAt.clear();
        outOrder.clear();
        lastHitBy.clear();
        lastHitAt.clear();
        winners.clear();
        pendingLayers.clear();
        schedule = null;
        matchTicks = 0;
        solo = false;
        firstY = totalLayers = risen = fillCursor = 0;
        filledY = Integer.MIN_VALUE;
    }

    @Override
    protected void onPlayerLeave(Player player, LeaveReason reason) {
        if (getState() != GameState.RUNNING) return;
        if (alive.remove(player.getUniqueId())) {
            outAt.put(player.getUniqueId(), risen);
            outOrder.add(player.getUniqueId());
            checkEnd(List.of());
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // The tide
    // ------------------------------------------------------------------------------------------------------------

    private void tick() {
        if (getState() != GameState.RUNNING || schedule == null) return;
        matchTicks++;
        int target = Math.min(schedule.layersBy(matchTicks), totalLayers);
        while (risen < target) rise();
        fill();
        catchPlayers();
        if (getState() == GameState.RUNNING && risen >= totalLayers && pendingLayers.isEmpty()) {
            // The lava reached the top: everyone still in made it.
            winners.addAll(alive);
            end();
            return;
        }
        if (matchTicks % 10 == 0) showStatus();
        // A countdown before the first layer.
        long until = schedule.ticksUntilNext(matchTicks);
        if (risen == 0 && until <= 60 && until % 20 == 0) Utils.playPling(audience(), until <= 20 ? 1.5f : 1f);
    }

    /** Starts the next layer filling, and hands out a refill when it's time. */
    private void rise() {
        risen++;
        pendingLayers.add(firstY + risen - 1);
        Utils.playSound(audience(), "item.bucket.empty_lava", 0.6f, 0.8f);
        if (risen == 1) broadcast(Utils.mm("<red>The lava is rising!"));
        if (TideSchedule.refillsAt(risen, getSettings().getInt("refill-every-layers"))) refill();
    }

    /** Fills pending layers with lava, up to {@link #BLOCKS_PER_TICK} blocks this tick. */
    private void fill() {
        Arena arena = getArena();
        Region region = arena == null ? null : arena.getRegion();
        World world = arena == null ? null : arena.getWorld();
        if (region == null || world == null) return;
        int width = region.sizeX(), area = width * region.sizeZ();
        int budget = BLOCKS_PER_TICK;
        while (!pendingLayers.isEmpty() && budget > 0) {
            int y = pendingLayers.getFirst();
            while (fillCursor < area && budget-- > 0) {
                Block block = world.getBlockAt(region.minX() + fillCursor % width, y, region.minZ() + fillCursor / width);
                fillCursor++;
                if (fillable(block)) block.setType(Material.LAVA, false);
            }
            if (fillCursor >= area) {
                pendingLayers.removeFirst();
                fillCursor = 0;
                filledY = y;
            }
        }
    }

    /** Air, or something lava washes away (grass, flowers, snow); never what players or the map are built from. */
    private static boolean fillable(Block block) {
        Material type = block.getType();
        return type.isAir() || (type != Material.LAVA && block.isReplaceable());
    }

    /** The lowest y of the region with anything to fill (a solid floor isn't worth waiting through). */
    private static int lowestOpenLayer(World world, Region region) {
        for (int y = region.minY(); y <= region.maxY(); y++) {
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    if (fillable(world.getBlockAt(x, y, z))) return y;
                }
            }
        }
        return region.maxY();
    }

    private void refill() {
        Kit kit = getSettings().getKit(REFILL_KIT);
        if (kit == null || kit.isEmpty()) return;
        for (Player player : online(alive)) {
            for (KitItem item : kit.getItems()) player.getInventory().addItem(item.item());
            Utils.playSound(player, "entity.item.pickup", 1f, 1f);
        }
        audience().sendMessage(prefix().append(Utils.mm("<green>Supplies! <gray>Everyone still in got more blocks.")));
    }

    /** Takes out everyone the lava has reached, all at once (so the last ones caught together win together). */
    private void catchPlayers() {
        List<Player> caught = new ArrayList<>();
        for (Player player : online(alive)) {
            if (player.isInLava() || (filledY != Integer.MIN_VALUE && player.getLocation().getY() < filledY)) caught.add(player);
        }
        if (caught.isEmpty()) return;
        for (Player player : caught) eliminate(player);
        checkEnd(caught);
    }

    private void eliminate(Player player) {
        UUID id = player.getUniqueId();
        alive.remove(id);
        outAt.put(id, Math.max(1, risen));
        outOrder.add(id);
        record(id, Math.max(1, risen));

        UUID by = lastHitBy.get(id);
        Long at = lastHitAt.get(id);
        Player knocker = by != null && at != null && matchTicks - at <= KNOCK_CREDIT_TICKS ? org.bukkit.Bukkit.getPlayer(by) : null;
        if (!solo) {
            broadcast(knocker != null && !knocker.equals(player)
                    ? Utils.mm("<red><name> was knocked into the lava by <knocker>! <gray>(<left> left)",
                    Placeholder.component("name", player.name()), Placeholder.component("knocker", knocker.name()),
                    Placeholder.unparsed("left", String.valueOf(alive.size())))
                    : Utils.mm("<red><name> was caught by the lava! <gray>(<left> left)",
                    Placeholder.component("name", player.name()), Placeholder.unparsed("left", String.valueOf(alive.size()))));
        }
        Utils.playSound(audience(), "entity.generic.burn", 0.8f, 1f);

        // Watch the rest from above the lava.
        player.setFireTicks(0);
        player.setFallDistance(0f);
        Utils.heal(player);
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
        Location above = player.getLocation();
        if (filledY != Integer.MIN_VALUE && above.getY() < filledY + 3) above.setY(filledY + 3);
        player.teleport(above);
        setStartPoint(player, above);
        if (!solo && alive.size() > 1) {
            player.sendMessage(Utils.prefixed(Utils.mm("<gray>You're out. Spectating until the match ends; <white>/game leave</white> to go.")));
        }
    }

    /**
     * Ends the match if it's decided: a solo run once its player is out; otherwise once one team (or none) is left.
     *
     * @param lastCaught who the lava caught just now, who win together if nobody else is left
     */
    private void checkEnd(List<Player> lastCaught) {
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
            for (Player player : lastCaught) winners.add(player.getUniqueId());
        } else {
            winners.addAll(alive);
        }
        end();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Showing the match
    // ------------------------------------------------------------------------------------------------------------

    private void showStatus() {
        if (schedule == null) return;
        long until = schedule.ticksUntilNext(matchTicks);
        String next = risen >= totalLayers ? "" : (risen == 0 ? "<red>Lava in <white><next>" : "<gray>Next layer in <white><next>");
        Component bar = Utils.mm(risen == 0 ? next
                        : "<gold>Layer <white><layer></white>/<total>" + (next.isEmpty() ? "" : "  <dark_gray>|</dark_gray>  " + next)
                        + (solo ? "" : "  <dark_gray>|</dark_gray>  <gray><left> left"),
                Placeholder.unparsed("next", Utils.formatTime((until + 19) / 20)),
                Placeholder.unparsed("layer", String.valueOf(risen)),
                Placeholder.unparsed("total", String.valueOf(totalLayers)),
                Placeholder.unparsed("left", String.valueOf(alive.size())));
        audience().sendActionBar(bar);
    }

    /** The layer a player got to: where they were caught, or the lava's top for those still in. */
    private int layerOf(UUID id) {
        Integer at = outAt.get(id);
        return at != null ? at : risen;
    }

    private void record(UUID id, int layer) {
        if (layer <= 0 || plugin.getLeaderboards() == null) return;
        Leaderboard board = plugin.getLeaderboards().get(LEADERBOARD.id());
        Player player = org.bukkit.Bukkit.getPlayer(id);
        if (board == null || player == null) return;
        try {
            board.submit(new Leaderboard.Entry(id, player.getName(), List.of((long) -layer), "layer " + layer,
                    System.currentTimeMillis()));
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Could not record {}'s Rising Tide result", player.getName(), e);
        }
    }

    private List<Player> online(Iterable<UUID> ids) {
        List<Player> players = new ArrayList<>();
        for (UUID id : ids) {
            Player player = org.bukkit.Bukkit.getPlayer(id);
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
     * No damage of any kind: the lava is the only way out. A hit from another player that PvP allows (the framework
     * cancels the rest first) still lands with its knockback, just without the damage.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !isPlaying(victim)) return;
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
