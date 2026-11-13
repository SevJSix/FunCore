package me.sevj6.funCore.games.floorislava;

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
import org.bukkit.event.block.BlockFromToEvent;
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
 * The Floor Is Lava: lava rises through the arena one layer at a time. Build and climb to stay above it; die in it and you're
 * out (and watch as a spectator). The last player (or team) left wins; played alone, it's how high you got.
 *
 * <h2>Rules</h2>
 * <ul>
 *     <li>Everyone starts with the main kit ({@code /game kit floorislava}): a knockback stick, blocks and ladders
 *     (unless {@code starting-kit} is false, e.g. per arena with {@code /arena option}).
 *     Every {@code refill-every-layers} layers, everyone still in gets the {@code refill} kit added to what they have.</li>
 *     <li>Hits do no damage, only knockback (when PvP is on): knock people off their towers. Lava hurts the vanilla way,
 *     but nobody catches fire, so someone who gets out in time doesn't burn to death afterwards. No other damage, and
 *     no hunger, and saturation stays full so health regenerates quickly after a dip. The lava is the only way out.</li>
 *     <li>After {@code start-delay-seconds} to build, the lowest layer of the arena with air in it fills with lava,
 *     then the next one every {@code rise-interval-seconds}, up to the top of the region. Lava only replaces air (and
 *     things like grass), so the map and players' blocks stay; it doesn't flow (arena fluid flow is off).</li>
 *     <li>You're out when the lava would kill you (you don't really die: you spectate). Anyone whose head is under the
 *     lava's top layer takes lava damage even where the lava couldn't reach them (e.g. inside a ladder column), so
 *     there's no hiding under it.</li>
 *     <li>The match ends when one player (or team) is left, or when the lava reaches the top: everyone still in wins.
 *     If the last ones are caught at the same moment, they win together.</li>
 * </ul>
 *
 * <h2>Arena setup</h2>
 * A region (the lava fills it from the bottom up, so its height is the match's length) with at least one spawn.
 * Nothing about the layout is fixed: the lava starts at the lowest layer with air and stops at the region's top.
 */
public final class FloorIsLavaGame extends Minigame {

    public static final String ID = "floorislava";
    public static final String REFILL_KIT = "refill";
    /** Every player's 2 highest layers reached, in any mode. */
    public static final Leaderboard.Definition LEADERBOARD = new Leaderboard.Definition("floorislava", "<gold><bold>The Floor Is Lava: Highest Layers", 2);
    /** Lava blocks placed per tick at most, so a big arena's layer is spread over a few ticks. */
    private static final int BLOCKS_PER_TICK = 4096;
    /** A hit this recent counts as knocking someone into the lava. */
    private static final long KNOCK_CREDIT_TICKS = 100;

    public static final GameType TYPE = GameType.builder(ID, Utils.mm("<gold>The Floor Is Lava"), FloorIsLavaGame::new)
            .modes(PlayMode.SINGLEPLAYER, PlayMode.FFA, PlayMode.DUOS, PlayMode.TRIOS, PlayMode.SQUADS)
            .players(2, 16)
            .dynamicByDefault()
            .requiresArena()
            .pvpByDefault(true)
            .kit(FloorIsLavaGame::defaultKit)
            .kit(REFILL_KIT, FloorIsLavaGame::defaultRefill)
            .arenaDefaults(arena -> {
                arena.setPlaceAllowed(true);
                arena.setBreakAllowed(false);
                arena.setFluidFlowAllowed(false);
            })
            .arenaCheck(FloorIsLavaGame::checkArena)
            .option("start-delay-seconds", 20, 0, 300, "time to build before the first layer of lava")
            .option("rise-interval-seconds", 5.0, 0.5, 120, "seconds between layers of lava")
            .option("starting-kit", true, "false = players start empty-handed (for maps where you gather your own blocks)")
            .option("refill-every-layers", 4, 0, 1000, "0 = never; everyone still in gets the refill kit every this many layers")
            .build();

    /** Damage the lava deals each half second, as vanilla lava does, to players buried where it couldn't reach. */
    private static final double LAVA_DAMAGE = 4.0;

    // ---- per match ----
    /** Players still in (not caught by the lava). */
    private final Set<UUID> alive = new LinkedHashSet<>();
    /** The layer each player was caught at (1 = the first), in the order they went out. */
    private final Map<UUID, Integer> outAt = new HashMap<>();
    private final List<UUID> outOrder = new ArrayList<>();
    /** Who last hit each player, and when (match tick), to credit a knock into the lava. */
    private final Map<UUID, UUID> lastHitBy = new HashMap<>();
    private final Map<UUID, Long> lastHitAt = new HashMap<>();
    /** Players the lava would have killed, taken out together on the next tick (so the last ones out win together). */
    private final Set<UUID> dying = new LinkedHashSet<>();
    private @Nullable LavaSchedule schedule;
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

    public FloorIsLavaGame(FunCore plugin, GameType type, @Nullable Arena arena) {
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
                .slot(1, new ItemStack(Material.COBBLESTONE, 32))
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
        // Without one the lava would never be cleared, and the next match would find nothing left to fill.
        if (!arena.isSnapshotCurrent()) problems.add("no up-to-date snapshot (/arena snapshot, once it's built)");
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
            if (getBooleanOption("starting-kit")) giveKit(player, UnaryOperator.identity());
            else player.getInventory().clear();
            Utils.heal(player);
            player.setFoodLevel(20);
            player.setSaturation(20f);
        }
        long delay = getIntOption("start-delay-seconds") * 20L;
        schedule = new LavaSchedule(delay, Utils.secondsToTicks(getDoubleOption("rise-interval-seconds")));
        Utils.title(audience(), Utils.mm("<gold>The Floor Is Lava"), Utils.mm(solo ? "<gray>Climb as high as you can!" : "<gray>Last one above the lava wins!"), 10, 50, 10);
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
                Utils.title(player, Utils.mm(top ? "<gold>You beat the lava!" : "<red>Caught by the lava"),
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
        dying.clear();
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
    // The lava
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
        // Full food and saturation all match, so players heal quickly (vanilla's fast regen) after escaping the lava.
        if (matchTicks % 20 == 0) {
            for (Player player : online(alive)) {
                player.setFoodLevel(20);
                player.setSaturation(20f);
            }
        }
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
        if (LavaSchedule.refillsAt(risen, getIntOption("refill-every-layers"))) refill();
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
        Kit kit = getKit(REFILL_KIT);
        if (kit == null || kit.isEmpty()) return;
        for (Player player : online(alive)) {
            for (KitItem item : kit.getItems()) player.getInventory().addItem(item.item());
            Utils.playSound(player, "entity.item.pickup", 1f, 1f);
        }
        audience().sendMessage(prefix().append(Utils.mm("<green>Supplies! <gray>Everyone still in got more blocks.")));
    }

    /**
     * Burns anyone buried under the lava where it couldn't reach them, then takes out everyone the lava killed, all at
     * once (so the last ones caught together win together).
     */
    private void catchPlayers() {
        if (filledY != Integer.MIN_VALUE && matchTicks % 10 == 0) {
            for (Player player : online(alive)) {
                if (!player.isInLava() && player.getEyeLocation().getY() < filledY + 1 && !dying.contains(player.getUniqueId())) {
                    player.damage(LAVA_DAMAGE, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.LAVA).build());
                }
            }
        }
        if (dying.isEmpty()) return;
        List<Player> caught = new ArrayList<>();
        for (Player player : online(dying)) {
            if (alive.contains(player.getUniqueId())) caught.add(player);
        }
        dying.clear();
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
            // The whole team wins, teammates already caught included.
            for (Player player : getPlayers(standing.iterator().next())) winners.add(player.getUniqueId());
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
            plugin.getSLF4JLogger().error("Could not record {}'s Floor Is Lava result", player.getName(), e);
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
     * Only lava hurts, the vanilla way, and a hit that would kill takes the player out instead (on the next tick, see
     * {@link #catchPlayers}). Fire doesn't burn. A hit from another player that PvP allows (the framework cancels the
     * rest first) still lands with its knockback, just without the damage.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !isPlaying(victim)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.LAVA) {
            UUID id = victim.getUniqueId();
            if (event.isCancelled() || getState() != GameState.RUNNING || !alive.contains(id) || dying.contains(id)) {
                event.setCancelled(true);
                return;
            }
            if (event.getFinalDamage() >= victim.getHealth() + victim.getAbsorptionAmount()) {
                event.setCancelled(true);
                dying.add(id);
            }
            return;
        }
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

    /** The lava never flows, even if the arena's fluids setting is turned on: it must stay inside the region. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Arena arena = getArena();
        if (getState() == GameState.RUNNING && arena != null && event.getBlock().getType() == Material.LAVA
                && arena.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Nobody catches fire, so getting out of the lava in time is enough to survive. */
    @EventHandler(priority = EventPriority.LOW)
    public void onCombust(org.bukkit.event.entity.EntityCombustEvent event) {
        if (event.getEntity() instanceof Player player && isPlaying(player)) {
            event.setCancelled(true);
            player.setFireTicks(0);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHunger(FoodLevelChangeEvent event) {
        if (isPlaying(event.getEntity())) event.setCancelled(true);
    }
}
