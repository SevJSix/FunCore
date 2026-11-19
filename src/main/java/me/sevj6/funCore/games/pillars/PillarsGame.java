package me.sevj6.funCore.games.pillars;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import me.sevj6.funCore.arena.ArenaPoint;
import me.sevj6.funCore.arena.Region;
import me.sevj6.funCore.game.GameState;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.game.PlayMode;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pillars: everyone starts on top of their own tall pillar, and every {@code item-interval-seconds} (5) everyone gets
 * one random item: a block, a weapon, a bucket of lava, a spawn egg... anything. Knock the others off, or kill them
 * any way you can. The last one alive wins.
 *
 * <h2>Rules</h2>
 * <ul>
 *     <li>Free for all, 2 or more players, one per pillar (spawn). Survival mode: PvP, fall damage, building and
 *     breaking, water and lava, and mobs from spawn eggs are all on; mobs don't spawn by themselves.</li>
 *     <li>Items are picked at random from every item the server has, except a built-in ban list (admin blocks,
 *     boss spawn eggs, ender chests, beds, ...; see {@link ItemPool}) and the {@code banned-items} option. A full
 *     inventory drops the item at the player's feet.</li>
 *     <li>You're out when you die (nobody really dies: the killing hit is caught, and you spectate) or when you leave
 *     the arena, below or through its sides. Your items spill where you died. A totem in hand saves you as usual.
 *     Whoever hit you in the last 10 seconds gets the kill.</li>
 *     <li>The last one alive wins; if the last ones die together, they win together. There's no time limit.</li>
 *     <li>Nothing reaches outside the arena: water and lava don't flow out, explosions, pistons, trees, falling
 *     blocks and dispensers only change blocks inside it, and anything that leaves (an item, an arrow, a mob) is
 *     removed. Everything spawned during the match is removed after it, and the arena's blocks come back from its
 *     snapshot.</li>
 * </ul>
 *
 * <h2>Arena setup</h2>
 * A region around the whole build (pillars, the ground under them and some air above), with its bottom above the world
 * below; a spawn on top of each pillar; an up-to-date snapshot.
 */
public final class PillarsGame extends Minigame {

    public static final String ID = "pillars";
    /** A hit this recent counts towards a kill, however the player then dies. */
    private static final long KILL_CREDIT_TICKS = 200;
    /** How long the last item stays named in the action bar. */
    private static final long SHOW_ITEM_TICKS = 50;

    public static final GameType TYPE = GameType.builder(ID, Utils.mm("<gold>Pillars"), PillarsGame::new)
            .modes(PlayMode.FFA)
            .players(2, 16)
            .requiresArena()
            .pvpByDefault(true)
            .arenaDefaults(arena -> {
                arena.setPlaceAllowed(true);
                arena.setBreakAllowed(true);
                arena.setPickupAllowed(true);
                arena.setDropAllowed(true);
                arena.setMobSpawningAllowed(false);
                arena.setPlayerMobsAllowed(true);
                arena.setEnvironmentAllowed(false);
                arena.setFluidFlowAllowed(true);
                arena.setFallDamageAllowed(true);
            })
            .arenaCheck(PillarsGame::checkArena)
            .option("item-interval-seconds", 5, 1, 60, "seconds between random items")
            .option("banned-items", "", value -> String.join(",", ItemPool.parseBans(value)),
                    "extra items never handed out, comma-separated (e.g. elytra,ender_pearl); the built-in bans always apply")
            .build();

    // ---- per match ----
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final List<UUID> outOrder = new ArrayList<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, UUID> lastHitBy = new HashMap<>();
    private final Map<UUID, Long> lastHitAt = new HashMap<>();
    /** Players out this tick: if they were the last ones, they win together. */
    private final List<UUID> outThisTick = new ArrayList<>();
    /** Players who died the vanilla way (it got past the damage check); they spectate once they respawn. */
    private final Set<UUID> respawning = new HashSet<>();
    private final List<UUID> winners = new ArrayList<>();
    /** Everything that appeared in the arena during the match: removed if it leaves, and after the match. */
    private final Set<UUID> spawned = new HashSet<>();
    private final Map<UUID, Component> lastItem = new HashMap<>();
    private final Map<UUID, Long> lastItemAt = new HashMap<>();
    private List<Material> pool = List.of();
    private long matchTicks, nextItemAt;
    private int interval;
    private @Nullable Location spectatorSpot;

    public PillarsGame(FunCore plugin, GameType type, @Nullable Arena arena) {
        super(plugin, type, arena);
    }

    private static List<String> checkArena(Arena arena) {
        List<String> problems = new ArrayList<>();
        Region region = arena.getRegion();
        if (arena.getSpawnPoints().size() < 2) problems.add("fewer than 2 spawns (/arena addspawn, on top of each pillar)");
        // Without one, what players build, break, pour and blow up would stay.
        if (!arena.isSnapshotCurrent()) problems.add("no up-to-date snapshot (/arena snapshot, once it's built)");
        if (region != null) {
            for (ArenaPoint spawn : arena.getSpawnPoints()) {
                if (!region.contains(spawn.x(), spawn.y(), spawn.z())) {
                    problems.add("a spawn is outside the region");
                    break;
                }
            }
        }
        return problems;
    }

    /** One player per pillar. */
    @Override
    protected int getCapacity() {
        Arena arena = getArena();
        return arena == null ? Integer.MAX_VALUE : Math.max(1, arena.getSpawnPoints().size());
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
        pool = buildPool(world);
        if (pool.isEmpty()) {
            broadcast(Utils.mm("<red>There are no items to hand out (check the banned-items option)."));
            end();
            return;
        }
        interval = getIntOption("item-interval-seconds") * 20;
        nextItemAt = 20;
        spectatorSpot = spectatorSpot(arena, world, region);
        for (Player player : getPlayers()) {
            alive.add(player.getUniqueId());
            player.getInventory().clear();
            for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
            Utils.heal(player);
            player.setFoodLevel(20);
            player.setSaturation(5f);
            player.setFireTicks(0);
            player.setFallDistance(0f);
        }
        Utils.title(audience(), Utils.mm("<gold>Pillars"), Utils.mm("<gray>Knock them off. Last one alive wins!"), 5, 50, 10);
        broadcast(Utils.mm("<yellow>Everyone gets a random item every <white><s></white> seconds. Use it however you like.",
                Placeholder.unparsed("s", String.valueOf(interval / 20))));
        runTaskTimer(this::tick, 1L, 1L);
    }

    /** Every item the server has switched on, minus the bans. */
    private List<Material> buildPool(World world) {
        List<String> names = new ArrayList<>();
        Map<String, Material> byName = new HashMap<>();
        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isItem()) continue;
            var item = material.asItemType();
            if (item == null || !world.isEnabled(item)) continue;
            String name = material.getKey().getKey();
            names.add(name);
            byName.put(name, material);
        }
        List<String> unknown = new ArrayList<>();
        List<String> picked = ItemPool.build(names, ItemPool.parseBans(getStringOption("banned-items")), unknown);
        if (!unknown.isEmpty()) {
            plugin.getSLF4JLogger().warn("Pillars: banned-items has names that aren't items: {}", String.join(", ", unknown));
        }
        return picked.stream().map(byName::get).toList();
    }

    @Override
    protected void onEnd() {
        if (alive.isEmpty() && outOrder.isEmpty()) return; // stopped before it began
        List<UUID> ranked = new ArrayList<>(winners);
        for (UUID id : alive) {
            if (!ranked.contains(id)) ranked.add(id);
        }
        for (int i = outOrder.size() - 1; i >= 0; i--) {
            if (!ranked.contains(outOrder.get(i))) ranked.add(outOrder.get(i));
        }
        List<Player> winning = new ArrayList<>();
        for (UUID id : winners) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) winning.add(player);
        }
        if (winning.isEmpty()) {
            broadcast(Utils.mm("<gray>Nobody won."));
        } else {
            broadcast(Utils.mm(winning.size() == 1 ? "<gold><name> wins!" : "<gold><name> win together!",
                    Placeholder.component("name", Component.join(net.kyori.adventure.text.JoinConfiguration.commas(true),
                            winning.stream().map(Player::name).toList()))));
        }
        for (int i = 0; i < ranked.size() && i < 5; i++) {
            Player player = Bukkit.getPlayer(ranked.get(i));
            if (player == null) continue;
            int count = kills.getOrDefault(player.getUniqueId(), 0);
            broadcast(Utils.mm("<gray>#<place> <white><name></white>: <kills>",
                    Placeholder.unparsed("place", String.valueOf(i + 1)), Placeholder.component("name", player.name()),
                    Placeholder.unparsed("kills", count + " " + Utils.plural(count, "kill", "kills"))));
        }
        for (Player player : getPlayers()) {
            boolean won = winners.contains(player.getUniqueId());
            int place = ranked.indexOf(player.getUniqueId()) + 1;
            int count = kills.getOrDefault(player.getUniqueId(), 0);
            Utils.title(player, won ? Utils.mm("<gold>You win!") : Utils.mm("<yellow>#<place>", Placeholder.unparsed("place", String.valueOf(place))),
                    Utils.mm("<gray><kills>", Placeholder.unparsed("kills", count + " " + Utils.plural(count, "kill", "kills"))), 10, 60, 10);
            Utils.playSound(player, won ? "ui.toast.challenge_complete" : "entity.wither.death", 1f, 1f);
        }
    }

    @Override
    protected void onReset() {
        for (UUID id : spawned) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && !(entity instanceof Player)) entity.remove();
        }
        spawned.clear();
        alive.clear();
        outOrder.clear();
        kills.clear();
        lastHitBy.clear();
        lastHitAt.clear();
        outThisTick.clear();
        respawning.clear();
        winners.clear();
        lastItem.clear();
        lastItemAt.clear();
        pool = List.of();
        matchTicks = nextItemAt = 0;
        interval = 0;
        spectatorSpot = null;
    }

    @Override
    protected void onPlayerLeave(Player player, LeaveReason reason) {
        respawning.remove(player.getUniqueId());
        if (getState() != GameState.RUNNING) return;
        if (alive.remove(player.getUniqueId())) {
            outOrder.add(player.getUniqueId());
            checkEnd(List.of());
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // The match
    // ------------------------------------------------------------------------------------------------------------

    private void tick() {
        if (getState() != GameState.RUNNING) return;
        matchTicks++;
        if (matchTicks >= nextItemAt) {
            giveItems();
            nextItemAt += interval;
        }
        Arena arena = getArena();
        if (arena != null) {
            // Riders (boats, horses, pigs) don't send moves, so check everyone still in each tick too.
            for (Player player : online(alive)) {
                if (arena.isWithinBounds(player.getLocation())) continue;
                player.leaveVehicle();
                player.teleport(eliminate(player, null, true));
            }
            if (matchTicks % 10 == 0) removeEscapees(arena);
        }
        if (!outThisTick.isEmpty()) {
            List<UUID> out = List.copyOf(outThisTick);
            outThisTick.clear();
            checkEnd(out);
        }
        if (getState() == GameState.RUNNING && matchTicks % 10 == 0) showStatus();
    }

    /** One random item for everyone still in; a full inventory drops it at their feet. */
    private void giveItems() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Player player : online(alive)) {
            ItemStack item = new ItemStack(pool.get(random.nextInt(pool.size())));
            for (ItemStack left : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItem(player.getLocation(), left);
            }
            lastItem.put(player.getUniqueId(), item.effectiveName());
            lastItemAt.put(player.getUniqueId(), matchTicks);
            Utils.playSound(player, "entity.item.pickup", 0.6f, 1.2f);
        }
        showStatus();
    }

    /** Anything spawned in the match that has left the arena (an item, an arrow, a mob, a boat) is removed. */
    private void removeEscapees(Arena arena) {
        spawned.removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) return true;
            if (entity instanceof Player || arena.isWithinBounds(entity.getLocation())) return false;
            entity.remove();
            return true;
        });
    }

    /**
     * Takes a player out: they spectate from above the arena until the match ends, and (unless they left the arena)
     * their items spill where they were. Whether that ends the match is checked at the end of the tick.
     *
     * @param cause       what killed them, or {@code null} if they left the arena
     * @param outOfBounds whether they left the arena (below it, or through a side)
     * @return where they watch from (the caller moves them there)
     */
    private Location eliminate(Player player, EntityDamageEvent.@Nullable DamageCause cause, boolean outOfBounds) {
        markOut(player, cause, outOfBounds);
        if (!outOfBounds) spill(player);
        player.getInventory().clear();
        player.setItemOnCursor(null);
        return toSpectator(player);
    }

    /** The bookkeeping and the announcement of a player going out. */
    private void markOut(Player player, EntityDamageEvent.@Nullable DamageCause cause, boolean outOfBounds) {
        UUID id = player.getUniqueId();
        alive.remove(id);
        outOrder.add(id);
        outThisTick.add(id);
        Player killer = creditedKiller(id);
        if (killer != null) kills.merge(killer.getUniqueId(), 1, Integer::sum);
        broadcast(Utils.mm("<red>" + deathMessage(cause, outOfBounds, killer != null) + " <gray>(<left> left)",
                Placeholder.component("name", player.name()),
                Placeholder.component("killer", killer == null ? Component.empty() : killer.name()),
                Placeholder.unparsed("left", String.valueOf(alive.size()))));
        Utils.playSound(audience(), "entity.lightning_bolt.thunder", 0.4f, 1.5f);
    }

    /** Drops everything the player had (open screens closed first, so the cursor and crafting grid count too). */
    private void spill(Player player) {
        player.closeInventory();
        Location at = player.getLocation();
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir()) items.add(item);
        }
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.getType().isAir()) items.add(cursor);
        for (ItemStack item : items) player.getWorld().dropItemNaturally(at, item);
    }

    private Location toSpectator(Player player) {
        player.leaveVehicle();
        player.setFallDistance(0f);
        player.setFireTicks(0);
        for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        Utils.heal(player);
        player.setFoodLevel(20);
        player.setGameMode(GameMode.SPECTATOR);
        Location spot = spectatorSpot != null ? spectatorSpot.clone() : player.getLocation();
        setStartPoint(player, spot);
        if (alive.size() > 1) {
            player.sendMessage(Utils.prefixed(Utils.mm("<gray>You're out. Spectating until the match ends; <white>/game leave</white> to go.")));
        }
        return spot;
    }

    /** Who gets the kill: whoever hit the player in the last few seconds (if they're still in the match). */
    private @Nullable Player creditedKiller(UUID victim) {
        UUID by = lastHitBy.get(victim);
        Long at = lastHitAt.get(victim);
        if (by == null || at == null || matchTicks - at > KILL_CREDIT_TICKS || by.equals(victim)) return null;
        Player killer = Bukkit.getPlayer(by);
        return killer != null && isPlaying(killer) ? killer : null;
    }

    static String deathMessage(EntityDamageEvent.@Nullable DamageCause cause, boolean outOfBounds, boolean byKiller) {
        if (outOfBounds) return byKiller ? "<name> was knocked into the void by <killer>." : "<name> fell into the void.";
        if (cause == null) return byKiller ? "<name> was killed by <killer>." : "<name> died.";
        return switch (cause) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> byKiller ? "<name> was killed by <killer>." : "<name> was killed.";
            case PROJECTILE -> byKiller ? "<name> was shot by <killer>." : "<name> was shot.";
            case FALL -> byKiller ? "<name> was knocked off by <killer> and hit the ground." : "<name> hit the ground too hard.";
            case LAVA, FIRE, FIRE_TICK, HOT_FLOOR, CAMPFIRE -> byKiller ? "<name> burned to death fighting <killer>." : "<name> burned to death.";
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> byKiller ? "<name> was blown up by <killer>." : "<name> blew up.";
            case DROWNING -> byKiller ? "<name> drowned fighting <killer>." : "<name> drowned.";
            case SUFFOCATION -> byKiller ? "<name> suffocated fighting <killer>." : "<name> suffocated.";
            case POISON, MAGIC, WITHER, DRAGON_BREATH -> byKiller ? "<name> was finished off by <killer>." : "<name> withered away.";
            default -> byKiller ? "<name> died fighting <killer>." : "<name> died.";
        };
    }

    /** Ends the match once one player (or none) is left. */
    private void checkEnd(List<UUID> lastOut) {
        if (getState() != GameState.RUNNING || alive.size() > 1) return;
        if (alive.isEmpty()) winners.addAll(lastOut);
        else winners.addAll(alive);
        end();
    }

    private void showStatus() {
        for (Player player : online(alive)) {
            long next = Math.max(0, nextItemAt - matchTicks);
            Long gotAt = lastItemAt.get(player.getUniqueId());
            Component got = gotAt != null && matchTicks - gotAt <= SHOW_ITEM_TICKS ? lastItem.get(player.getUniqueId()) : null;
            player.sendActionBar(Utils.mm("<gold>Next item in <white><next>s</white>"
                            + (got == null ? "" : "  <dark_gray>|</dark_gray>  <gray>Got <white><got>")
                            + "  <dark_gray>|</dark_gray>  <gray><left> left  <dark_gray>|</dark_gray>  <gray><kills> kills",
                    Placeholder.unparsed("next", String.valueOf((next + 19) / 20)),
                    Placeholder.component("got", got == null ? Component.empty() : got),
                    Placeholder.unparsed("left", String.valueOf(alive.size())),
                    Placeholder.unparsed("kills", String.valueOf(kills.getOrDefault(player.getUniqueId(), 0)))));
        }
    }

    /** Above the middle of the region, a few blocks over the highest spawn, looking down; never above the region. */
    private static Location spectatorSpot(Arena arena, World world, Region region) {
        double top = region.minY() + 1;
        for (Location spawn : arena.getSpawns()) top = Math.max(top, spawn.getY());
        double y = Math.max(region.minY() + 1, Math.min(top + 6, region.maxY()));
        double x = (region.minX() + region.maxX() + 1) / 2.0, z = (region.minZ() + region.maxZ() + 1) / 2.0;
        return new Location(world, x, y, z, 0f, 70f);
    }

    private List<Player> online(Iterable<UUID> ids) {
        List<Player> players = new ArrayList<>();
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && isPlaying(player)) players.add(player);
        }
        return players;
    }

    private boolean inArena(Location location) {
        Arena arena = getArena();
        return arena != null && arena.contains(location);
    }

    private boolean running() {
        return getState() == GameState.RUNNING;
    }

    /** The player behind a hit: the attacker, an arrow's shooter, a TNT's lighter, a cloud's thrower, a pet's owner. */
    private static @Nullable Player attacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player lighter) return lighter;
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player thrower) return thrower;
        if (damager instanceof Tameable pet && pet.getOwner() instanceof Player owner) return owner;
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Events (they fire for every player on the server, so each checks isPlaying first)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Damage is vanilla during the match (PvP, falls, lava, explosions, mobs). The hit that would kill is caught
     * instead: the player is out and spectates (a totem in hand still saves them). Nothing hurts in the queue.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !isPlaying(victim)) return;
        UUID id = victim.getUniqueId();
        if (!running() || !alive.contains(id)) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = attacker(byEntity.getDamager());
            if (attacker != null && !attacker.equals(victim) && alive.contains(attacker.getUniqueId())) {
                lastHitBy.put(id, attacker.getUniqueId());
                lastHitAt.put(id, matchTicks);
            }
        }
        if (event.getFinalDamage() < victim.getHealth() + victim.getAbsorptionAmount()) return;
        if (victim.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || victim.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) {
            return; // the totem saves them, the vanilla way
        }
        event.setCancelled(true);
        victim.teleport(eliminate(victim, event.getCause(), false));
    }

    /** In case a death gets past the damage check anyway: they're out, their items drop as usual, and they spectate. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!isPlaying(player) || !running() || !alive.contains(player.getUniqueId())) return;
        event.deathMessage(null);
        EntityDamageEvent last = player.getLastDamageCause();
        markOut(player, last == null ? null : last.getCause(), false);
        respawning.add(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!respawning.remove(player.getUniqueId()) || !isPlaying(player)) return;
        if (spectatorSpot != null) event.setRespawnLocation(spectatorSpot.clone());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isPlaying(player)) toSpectator(player);
        });
    }

    /**
     * Leaving the arena (falling below it, or through a side) is out, straight away. This runs before the framework's
     * "keep them in the arena" check, which would otherwise put them back on their pillar.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!running() || !alive.contains(player.getUniqueId()) || !isPlaying(player)) return;
        Arena arena = getArena();
        if (arena == null || arena.isWithinBounds(event.getTo())) return;
        event.setTo(eliminate(player, null, true));
    }

    /** Only hunger during the match. */
    @EventHandler(priority = EventPriority.LOW)
    public void onHunger(FoodLevelChangeEvent event) {
        if (isPlaying(event.getEntity()) && (!running() || !alive.contains(event.getEntity().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    // ---- keeping the match inside the arena ----

    /** Everything that appears in the arena during the match is remembered, to be removed if it leaves and afterwards. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (running() && !(event.getEntity() instanceof Player) && inArena(event.getLocation())) {
            spawned.add(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Arena arena = getArena();
        if (arena == null || !running()) return;
        if (spawned.contains(event.getEntity().getUniqueId()) || arena.contains(event.getLocation())) {
            event.blockList().removeIf(block -> !arena.contains(block.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Arena arena = getArena();
        if (arena != null && running() && arena.contains(event.getBlock().getLocation())) {
            event.blockList().removeIf(block -> !arena.contains(block.getLocation()));
        }
    }

    /** Falling blocks, endermen and the like can't put blocks down outside the arena. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        if (running() && spawned.contains(event.getEntity().getUniqueId()) && !inArena(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getEntity().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!running() || !inArena(event.getBlock().getLocation())) return;
        if (!inArena(event.getBlock().getRelative(event.getDirection()).getLocation())) {
            event.setCancelled(true);
            return;
        }
        for (Block moved : event.getBlocks()) {
            if (!inArena(moved.getRelative(event.getDirection()).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!running() || !inArena(event.getBlock().getLocation())) return;
        for (Block moved : event.getBlocks()) {
            if (!inArena(moved.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** A dispenser at the edge can't place water, lava or blocks outside. (What it shoots is removed if it leaves.) */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!running() || !inArena(event.getBlock().getLocation())) return;
        if (event.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional facing
                && !inArena(event.getBlock().getRelative(facing.getFacing()).getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        if (running() && inArena(event.getLocation())) event.getBlocks().removeIf(state -> !inArena(state.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (running() && inArena(event.getBlock().getLocation())) {
            event.getBlocks().removeIf((BlockState state) -> !inArena(state.getLocation()));
        }
    }

    /** Fire players light can't spread out of the arena. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (running() && inArena(event.getSource().getLocation()) && !inArena(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** No ender chests: their items are a player's own, from outside the match. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST && event.getPlayer() instanceof Player player
                && isPlaying(player)) {
            event.setCancelled(true);
        }
    }

    /** Beds and respawn anchors don't move a player's real spawn point. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnChange(com.destroystokyo.paper.event.player.PlayerSetSpawnEvent event) {
        if (isPlaying(event.getPlayer())) event.setCancelled(true);
    }
}
