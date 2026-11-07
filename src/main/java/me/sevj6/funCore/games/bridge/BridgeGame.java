package me.sevj6.funCore.games.bridge;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
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
import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bridge Duels: red vs. blue on a bridge between two bases. Jump into the other side's goal to score; the first side
 * to {@code winning-score} goals wins, or the side ahead when time runs out.
 *
 * <ul>
 *     <li>Modes: FFA (1v1) and duos, trios and squads, always exactly two sides.</li>
 *     <li>Arena setup: points {@code red-spawn} and {@code blue-spawn}, areas {@code red-goal} and {@code blue-goal}
 *     (red defends red-goal), and a region around everything playable. Leaving the region counts as falling into the
 *     void.</li>
 *     <li>Only red and blue terracotta can be placed and broken, and never inside a goal. Blocks stay as they are
 *     between goals; the arena resets after the match.</li>
 *     <li>Dying (or falling) respawns you at your base with full health and a fresh kit. After a goal, everyone does.</li>
 *     <li>The kit is edited with {@code /game kit bridge}; terracotta in it becomes the player's side colour and leather
 *     armour is dyed. Players arrange it with {@code /game layout bridge}.</li>
 * </ul>
 */
public final class BridgeGame extends Minigame {

    public static final String ID = "bridge";
    /** A hit counts for a kill (e.g. knocking someone into the void) for this long. */
    private static final long KILL_CREDIT_MILLIS = 10_000;

    public static final GameType TYPE = GameType.builder(ID, Utils.mm("<red>Bridge</red> <blue>Duels</blue>"), BridgeGame::new)
            .modes(PlayMode.FFA, PlayMode.DUOS, PlayMode.TRIOS, PlayMode.SQUADS)
            .players(2, 8)
            .maxTeams(2)
            .requiresPoints(Side.RED.spawnPoint, Side.BLUE.spawnPoint)
            .requiresAreas(Side.RED.goalArea, Side.BLUE.goalArea)
            .kit(BridgeGame::defaultKit, Side.RED::paint)
            .option("winning-score", 3, 1, 50, "goals needed to win")
            .option("time-limit-minutes", 15, 0, 120, "0 = none; when time runs out, the side ahead wins")
            .option("bow-cooldown-seconds", 3.5, 0, 60, "wait between bow shots")
            .build();

    private final Map<Side, Integer> scores = new EnumMap<>(Side.class);
    private final Map<UUID, LastHit> lastHits = new HashMap<>();
    private @Nullable Side winner;
    /** When the time limit runs out (epoch millis), or 0 for no limit. */
    private long endsAt;
    private @Nullable Objective sidebar;

    private record LastHit(UUID attacker, long at) {
    }

    public BridgeGame(FunCore plugin, GameType type, @Nullable Arena arena) {
        super(plugin, type, arena);
    }

    /** The built-in kit. White terracotta is painted the player's side colour when it's handed out. */
    static Kit defaultKit() {
        return Kit.builder()
                .helmet(unbreakable(Material.LEATHER_HELMET))
                .chestplate(unbreakable(Material.LEATHER_CHESTPLATE))
                .leggings(unbreakable(Material.LEATHER_LEGGINGS))
                .boots(unbreakable(Material.LEATHER_BOOTS))
                .slot(0, unbreakable(Material.IRON_SWORD))
                .slot(1, unbreakable(Material.BOW))
                .slot(2, ItemBuilder.of(Material.DIAMOND_PICKAXE).meta(meta -> {
                    meta.setUnbreakable(true);
                    meta.addEnchant(Enchantment.EFFICIENCY, 2, true);
                }).build())
                .slot(3, new ItemStack(Material.WHITE_TERRACOTTA, 64))
                .slot(4, new ItemStack(Material.WHITE_TERRACOTTA, 64))
                .slot(5, new ItemStack(Material.GOLDEN_APPLE, 8))
                .slot(8, new ItemStack(Material.ARROW, 16))
                .build();
    }

    private static ItemStack unbreakable(Material material) {
        return ItemBuilder.of(material).meta(meta -> meta.setUnbreakable(true)).build();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Framework settings
    // ------------------------------------------------------------------------------------------------------------

    /** Red and blue even in a 1v1. */
    @Override
    protected boolean colorsSoloTeams() {
        return true;
    }

    /** Each side has its own spawn point, so the arena's generic spawns aren't used. */
    @Override
    protected boolean teleportToSpawnsOnStart() {
        return false;
    }

    @Override
    protected GameMode getPlayingGameMode() {
        return GameMode.SURVIVAL;
    }

    @Override
    protected boolean canBreak(Player player, Block block) {
        return Side.isBridgeBlock(block.getType()) && arena().contains(block.getLocation());
    }

    @Override
    protected boolean canPlace(Player player, Block block) {
        Location at = block.getLocation();
        return Side.isBridgeBlock(block.getType()) && arena().contains(at)
                && !arena().isInArea(Side.RED.goalArea, at) && !arena().isInArea(Side.BLUE.goalArea, at);
    }

    /** Arrows can be picked back up; nothing else is ever on the ground. */
    @Override
    protected boolean canPickUp(Player player, ItemStack item) {
        return item.getType() == Material.ARROW;
    }

    @Override
    protected boolean canDrop(Player player, ItemStack item) {
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        if (getTeams().size() < 2) {
            broadcast(Utils.mm("<red>Bridge Duels needs a player on each side."));
            end();
            return;
        }
        for (Side side : Side.values()) scores.put(side, 0);
        int minutes = getSettings().getInt("time-limit-minutes");
        endsAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        createSidebar();
        for (Player player : getPlayers()) respawn(player);

        broadcast(Utils.mm("<yellow>Cross the bridge and jump into the other side's goal. First to <gold><score></gold> wins!",
                Placeholder.unparsed("score", String.valueOf(winningScore()))));
        for (Player player : getPlayers()) {
            Side side = sideOf(player);
            if (side == null) continue;
            Utils.title(player, Utils.mm("<name>", Placeholder.component("name", getDisplayName())),
                    Utils.mm("<gray>You're on <side>", Placeholder.component("side", side.displayName())), 5, 40, 10);
        }
        runTaskTimer(this::tick, 20L, 20L);
    }

    @Override
    protected void onEnd() {
        if (scores.isEmpty()) return; // stopped before it began
        updateSidebar();
        if (winner == null) {
            broadcast(Utils.mm("<yellow>It's a draw! <score>", Placeholder.component("score", scoreLine())));
            Utils.title(audience(), Utils.mm("<yellow>Draw"), scoreLine(), 10, 60, 10);
            return;
        }
        Side won = winner;
        broadcast(Utils.mm("<side> wins! <score>", Placeholder.component("side", won.displayName()),
                Placeholder.component("score", scoreLine())));
        for (Player player : getPlayers()) {
            boolean victory = sideOf(player) == won;
            Utils.title(player, Utils.mm(victory ? "<gold>Victory!" : "<red>Defeat"), scoreLine(), 10, 60, 10);
            Utils.playSound(player, victory ? "ui.toast.challenge_complete" : "entity.wither.death", 1f, 1f);
        }
    }

    @Override
    protected void onReset() {
        scores.clear();
        lastHits.clear();
        winner = null;
        endsAt = 0;
        sidebar = null;
    }

    /** A side with nobody left loses. */
    @Override
    protected void onPlayerLeave(Player player, LeaveReason reason) {
        lastHits.remove(player.getUniqueId());
        if (getState() != GameState.RUNNING) return;
        List<GameTeam> remaining = getRemainingTeams();
        if (remaining.size() >= 2) return;
        winner = remaining.isEmpty() ? null : Side.ofTeam(remaining.getFirst().getIndex());
        if (winner != null) {
            broadcast(Utils.mm("<gray><side> has nobody left.", Placeholder.component("side", winner.opposite().displayName())));
        }
        end();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rounds, goals and deaths
    // ------------------------------------------------------------------------------------------------------------

    private void tick() {
        if (getState() != GameState.RUNNING) return;
        if (endsAt > 0 && System.currentTimeMillis() >= endsAt) {
            int red = scores.getOrDefault(Side.RED, 0), blue = scores.getOrDefault(Side.BLUE, 0);
            winner = red == blue ? null : red > blue ? Side.RED : Side.BLUE;
            broadcast(Utils.mm("<yellow>Time's up!"));
            end();
            return;
        }
        updateSidebar();
    }

    /** Scores a goal. Everyone else is teleported home; the scorer is refreshed, and the caller moves them. */
    private void scoreGoal(Player scorer, Side side) {
        int score = scores.merge(side, 1, Integer::sum);
        Component name = scorer.name().color(side.color);
        broadcast(Utils.mm("<name> <yellow>scored for</yellow> <side><yellow>!</yellow> <score>",
                Placeholder.component("name", name), Placeholder.component("side", side.displayName()),
                Placeholder.component("score", scoreLine())));
        Utils.title(audience(), Utils.mm("<name> <yellow>scored!", Placeholder.component("name", name)), scoreLine(), 0, 30, 10);
        Utils.playSound(audience(), "entity.firework_rocket.blast", 1f, 1f);
        Utils.playSound(audience(), "entity.player.levelup", 1f, 1.5f);

        // Everyone back to base. The blocks stay as they are.
        lastHits.clear();
        for (Player player : getPlayers()) {
            if (player.equals(scorer)) refresh(player);
            else respawn(player);
        }
        updateSidebar();

        if (score >= winningScore()) {
            winner = side;
            end();
        }
    }

    /**
     * Handles a death: a kill message (crediting the last hitter, if recent) and a respawn at base with full health and
     * a fresh kit.
     *
     * @param teleport {@code false} from a move event, which moves the player itself
     */
    private void die(Player victim, boolean fellOut, boolean teleport) {
        Side side = sideOf(victim);
        Player killer = recentAttacker(victim);
        lastHits.remove(victim.getUniqueId());
        Component victimName = side == null ? victim.name() : victim.name().color(side.color);
        Component message;
        if (killer != null) {
            Side killerSide = sideOf(killer);
            Component killerName = killerSide == null ? killer.name() : killer.name().color(killerSide.color);
            message = Utils.mm(fellOut ? "<victim> <gray>was knocked into the void by</gray> <killer>" : "<victim> <gray>was killed by</gray> <killer>",
                    Placeholder.component("victim", victimName), Placeholder.component("killer", killerName));
            Utils.playSound(killer, "entity.experience_orb.pickup", 1f, 1f);
        } else {
            message = Utils.mm(fellOut ? "<victim> <gray>fell into the void" : "<victim> <gray>died",
                    Placeholder.component("victim", victimName));
        }
        broadcast(message);
        if (teleport) respawn(victim);
        else refresh(victim);
    }

    private @Nullable Player recentAttacker(Player victim) {
        LastHit hit = lastHits.get(victim.getUniqueId());
        if (hit == null || System.currentTimeMillis() - hit.at() > KILL_CREDIT_MILLIS) return null;
        Player attacker = Bukkit.getPlayer(hit.attacker());
        return attacker != null && isPlaying(attacker) ? attacker : null;
    }

    /** Full health, a fresh kit and back to base. */
    private void respawn(Player player) {
        Location spawn = refresh(player);
        if (spawn != null) player.teleport(spawn);
    }

    /** Everything {@link #respawn} does except the teleport (for move events, which set the destination instead). */
    private @Nullable Location refresh(Player player) {
        Side side = sideOf(player);
        if (side == null) return null;
        Utils.heal(player);
        Utils.clearPotionEffects(player);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setVelocity(new Vector());
        player.setCooldown(Material.BOW, 0);
        giveKit(player, side::paint);
        Location spawn = arena().getPoint(side.spawnPoint);
        if (spawn != null) setStartPoint(player, spawn);
        return spawn;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Events (they fire for every player on the server, so each checks isPlaying first)
    // ------------------------------------------------------------------------------------------------------------

    /** Goals and the void. Runs before the arena confinement, so falling out counts as a death rather than a bounce. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!event.hasChangedBlock() || !isPlaying(player) || getState() != GameState.RUNNING) return;
        Side side = sideOf(player);
        if (side == null) return;
        Location to = event.getTo();
        Region region = arena().getRegion();

        if (region != null && !arena().contains(to)) {
            Location spawn = arena().getPoint(side.spawnPoint);
            die(player, true, false);
            if (spawn != null) event.setTo(spawn);
            return;
        }
        if (arena().isInArea(side.opposite().goalArea, to)) {
            Location spawn = arena().getPoint(side.spawnPoint);
            scoreGoal(player, side);
            if (spawn != null) event.setTo(spawn);
            return;
        }
        if (arena().isInArea(side.goalArea, to)) {
            // Your own goal: no score, just back to base.
            Location spawn = arena().getPoint(side.spawnPoint);
            if (spawn != null) event.setTo(spawn);
        }
    }

    /** No death screens: a fatal hit (or the void) is a respawn at base. Nobody takes damage outside a round. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !isPlaying(victim)) return;
        if (getState() != GameState.RUNNING) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = byEntity.getDamager() instanceof Player player ? player
                    : byEntity.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter
                    : null;
            if (attacker != null && !attacker.equals(victim) && isPlaying(attacker)) {
                lastHits.put(victim.getUniqueId(), new LastHit(attacker.getUniqueId(), System.currentTimeMillis()));
            }
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
            die(victim, true, true);
        } else if (victim.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            die(victim, false, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isPlaying(player)) return;
        int ticks = (int) Math.round(getSettings().getDouble("bow-cooldown-seconds") * 20);
        if (ticks > 0) player.setCooldown(Material.BOW, ticks);
    }

    /** Broken blocks don't drop: clay only comes from the kit. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isPlaying(event.getPlayer())) event.setDropItems(false);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHunger(FoodLevelChangeEvent event) {
        if (isPlaying(event.getEntity())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Sidebar
    // ------------------------------------------------------------------------------------------------------------

    private void createSidebar() {
        Scoreboard board = getScoreboard();
        if (board == null) return;
        sidebar = board.registerNewObjective("bridge", Criteria.DUMMY, getDisplayName());
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        sidebar.numberFormat(NumberFormat.blank());
        updateSidebar();
    }

    private void updateSidebar() {
        if (sidebar == null) return;
        line(6, "goal", Utils.mm("<gray>First to <white><score></white> goals", Placeholder.unparsed("score", String.valueOf(winningScore()))));
        line(5, "gap1", Component.empty());
        for (Side side : Side.values()) {
            line(side == Side.RED ? 4 : 3, side.name(), Utils.mm("<side><gray>:</gray> <white><score>",
                    Placeholder.component("side", side.displayName()),
                    Placeholder.unparsed("score", String.valueOf(scores.getOrDefault(side, 0)))));
        }
        line(2, "gap2", Component.empty());
        String time = endsAt == 0 ? "no limit" : Utils.formatTime(Math.max(0, (endsAt - System.currentTimeMillis() + 999) / 1000));
        line(1, "time", Utils.mm("<gray>Time left: <white><time>", Placeholder.unparsed("time", time)));
        line(0, "mode", Utils.mm("<dark_gray><mode>", Placeholder.unparsed("mode", describeMode())));
    }

    private void line(int order, String entry, Component text) {
        var score = Objects.requireNonNull(sidebar).getScore(entry);
        score.setScore(order);
        score.customName(text);
    }

    /** "1v1", "2v2", ... */
    private String describeMode() {
        int size = getMode().teamSize();
        return size + "v" + size;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    private Arena arena() {
        return Objects.requireNonNull(getArena(), "Bridge Duels always has an arena");
    }

    private @Nullable Side sideOf(Player player) {
        GameTeam team = getTeam(player);
        return team == null ? null : Side.ofTeam(team.getIndex());
    }

    private int winningScore() {
        return getSettings().getInt("winning-score");
    }

    /** "Red 2 - 1 Blue" */
    private Component scoreLine() {
        return Utils.mm("<red>Red <white><red_score></white> <gray>-</gray> <white><blue_score></white> <blue>Blue",
                Placeholder.unparsed("red_score", String.valueOf(scores.getOrDefault(Side.RED, 0))),
                Placeholder.unparsed("blue_score", String.valueOf(scores.getOrDefault(Side.BLUE, 0))));
    }
}
