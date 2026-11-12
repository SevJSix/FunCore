package me.sevj6.funCore.games.hotpotato;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.arena.Arena;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.game.PlayMode;
import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hot Potato: each round, random players are handed potatoes that share one hidden fuse. Hit a player without one to
 * pass yours on. Whoever holds a potato when the fuse runs out explodes and is out. The last player standing wins.
 *
 * <p>How many potatoes a round has depends on how many players are left when it starts: 1, then 2 from
 * {@code two-potatoes-at} players, then 3 from {@code three-potatoes-at} (5 and 8 by default; 0 turns that step off).
 * There's always at least one player without a potato.
 *
 * <p>It needs no arena (it's played wherever the players are), which makes it a good first test of the minigame
 * framework: joining, the countdown, tasks, events, eliminations, winning and state restore.
 */
public final class HotPotatoGame extends Minigame {

    public static final String ID = "hotpotato";
    public static final GameType TYPE = GameType.builder(ID, Utils.mm("<gold>Hot Potato"), HotPotatoGame::new)
            .modes(PlayMode.FFA)
            .players(2, 16)
            .option("pass-delay-seconds", 0.0, 0, 10,
                    "0 = none; after a pass, the potato can't be passed again for this long (stops instant pass-backs)")
            .option("two-potatoes-at", 5, 0, 1000, "0 = never; rounds start with 2 potatoes from this many players")
            .option("three-potatoes-at", 8, 0, 1000, "0 = never; rounds start with 3 potatoes from this many players")
            .build();
    private static final int MIN_FUSE_SECONDS = 10, MAX_FUSE_SECONDS = 20;
    private static final long ROUND_BREAK_TICKS = 60;

    private static final ItemStack POTATO = ItemBuilder.of(Material.BAKED_POTATO)
            .name("<gold><bold>Hot Potato")
            .lore("<gray>Hit another player to pass it on!", "<red>Don't be holding it when it blows.")
            .glow()
            .build();

    /** Who holds a potato, and when they got it (for the pass delay). */
    private final Map<UUID, Long> holders = new LinkedHashMap<>();
    private @Nullable UUID winner;
    /** Set while the fuse's victims are being taken out, so their leaving doesn't start a round of its own. */
    private boolean exploding;
    private int fuse;
    private @Nullable BukkitTask fuseTask;

    public HotPotatoGame(FunCore plugin, GameType type, @Nullable Arena arena) {
        super(plugin, type, arena);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        broadcast(Utils.mm("<yellow>Hit another player to pass the potato. Don't be holding it when it explodes!"));
        Utils.title(audience(), Utils.mm("<gold>Hot Potato"), Utils.mm("<gray>Last one standing wins!"), 10, 40, 10);
        nextRound();
    }

    @Override
    protected void onEnd() {
        Player champion = winner == null ? null : Bukkit.getPlayer(winner);
        if (champion == null) {
            broadcast(Utils.mm("<gray>The game ended with no winner."));
            return;
        }
        broadcast(Utils.mm("<gold><player></gold> <yellow>wins!", Placeholder.component("player", champion.name())));
        Utils.title(audience(), Utils.mm("<gold><player> wins!", Placeholder.component("player", champion.name())),
                Component.empty(), 10, 50, 10);
        Utils.playSound(audience(), "ui.toast.challenge_complete", 1f, 1f);
    }

    @Override
    protected void onReset() {
        holders.clear();
        winner = null;
        exploding = false;
        fuseTask = null;
    }

    @Override
    protected void onPlayerLeave(Player player, LeaveReason reason) {
        if (!getState().isActive()) return;
        boolean wasHolder = holders.remove(player.getUniqueId()) != null;
        // The last one left wins.
        List<Player> remaining = getPlayers();
        if (remaining.size() <= 1) {
            winner = remaining.isEmpty() ? null : remaining.getFirst().getUniqueId();
            end();
            return;
        }
        if (exploding) return; // explode() starts the next round once everyone caught is out
        // Someone has to be left without a potato, or nobody could pass and they'd all blow up together.
        while (holders.size() >= remaining.size()) {
            UUID first = holders.keySet().iterator().next();
            holders.remove(first);
            Player lucky = Bukkit.getPlayer(first);
            if (lucky != null) take(lucky);
        }
        if (wasHolder && holders.isEmpty()) {
            // Their potato left with them and it was the last one: on to a new round.
            stopFuse();
            runTaskLater(this::nextRound, ROUND_BREAK_TICKS);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rounds
    // ------------------------------------------------------------------------------------------------------------

    /** Potatoes for a round with this many players: 1, 2 or 3 by the thresholds, and always fewer than the players. */
    private int potatoesFor(int players) {
        int two = getSettings().getInt("two-potatoes-at"), three = getSettings().getInt("three-potatoes-at");
        int count = 1;
        if (two > 0 && players >= two) count = 2;
        if (three > 0 && players >= three) count = 3;
        return Math.max(1, Math.min(count, players - 1));
    }

    private void nextRound() {
        if (!getState().isActive() || !holders.isEmpty()) return;
        List<Player> players = getPlayers();
        if (players.size() <= 1) {
            // Only reachable when an admin force-starts with one player; a real win goes through onPlayerLeave.
            broadcast(Utils.mm("<red>Hot Potato needs at least 2 players."));
            winner = null;
            end();
            return;
        }
        List<Player> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        List<Player> chosen = List.copyOf(shuffled.subList(0, potatoesFor(players.size())));
        broadcast(chosen.size() == 1
                ? Utils.mm("<yellow>New round! <gold><names></gold> has the potato.", Placeholder.component("names", names(chosen)))
                : Utils.mm("<yellow>New round with <count> potatoes! <gold><names></gold> have them.",
                Placeholder.unparsed("count", String.valueOf(chosen.size())), Placeholder.component("names", names(chosen))));
        for (Player player : chosen) give(player);
        fuse = ThreadLocalRandom.current().nextInt(MIN_FUSE_SECONDS, MAX_FUSE_SECONDS + 1);
        fuseTask = runTaskTimer(this::tickFuse, 20L, 20L);
    }

    private void tickFuse() {
        List<Player> current = holding();
        if (current.isEmpty()) {
            stopFuse();
            return;
        }
        fuse--;
        if (fuse <= 0) {
            explode(current);
            return;
        }
        // The exact fuse is hidden; players only see that it's getting close.
        String heat = fuse <= 3 ? "<red><bold>About to blow!" : fuse <= 7 ? "<gold>It's getting hot..." : "<yellow>Tick, tock...";
        boolean one = current.size() == 1;
        audience().sendActionBar(Utils.mm("<gold><names></gold> <gray>" + (one ? "has the potato" : "have the potatoes")
                + " <dark_gray>|</dark_gray> " + heat, Placeholder.component("names", names(current))));
        if (fuse <= 3) Utils.playPling(audience(), 2f);
    }

    /** The fuse ran out: everyone holding a potato is out. */
    private void explode(List<Player> victims) {
        stopFuse();
        Utils.playSound(audience(), "entity.generic.explode", 1f, 1f);
        broadcast(Utils.mm("<red><names> exploded!", Placeholder.component("names", names(victims))));
        exploding = true;
        try {
            for (Player victim : victims) {
                victim.getWorld().spawnParticle(Particle.EXPLOSION, victim.getLocation().add(0, 1, 0), 1);
                // onPlayerLeave picks the winner once one player is left.
                leave(victim, LeaveReason.ELIMINATED);
            }
        } finally {
            exploding = false;
        }
        holders.clear();
        if (getState().isActive()) runTaskLater(this::nextRound, ROUND_BREAK_TICKS);
    }

    private void stopFuse() {
        if (fuseTask != null) {
            fuseTask.cancel();
            fuseTask = null;
        }
    }

    /** The online players holding a potato. */
    private List<Player> holding() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : holders.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) players.add(player);
        }
        return players;
    }

    private static Component names(List<Player> players) {
        return Component.join(JoinConfiguration.commas(true), players.stream().map(Player::name).toList());
    }

    private void pass(Player from, Player to) {
        long delay = Math.round(getSettings().getDouble("pass-delay-seconds") * 1000);
        Long since = holders.get(from.getUniqueId());
        if (delay > 0 && since != null && System.currentTimeMillis() - since < delay) return;
        holders.remove(from.getUniqueId());
        take(from);
        give(to);
        broadcast(Utils.mm("<gray><from> passed the potato to <gold><to></gold>!",
                Placeholder.component("from", from.name()), Placeholder.component("to", to.name())));
    }

    private void give(Player player) {
        holders.put(player.getUniqueId(), System.currentTimeMillis());
        player.getInventory().setItem(0, POTATO);
        player.getInventory().setHeldItemSlot(0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION, 0, false, false));
        Utils.title(player, Utils.mm("<gold>You have the potato!"), Utils.mm("<gray>Hit someone to pass it"), 0, 30, 10);
        Utils.playSound(player, "entity.blaze.shoot", 1f, 1.2f);
    }

    private void take(Player player) {
        player.getInventory().clear();
        player.removePotionEffect(PotionEffectType.GLOWING);
    }

    /** A potato can never be dropped, whatever the arena allows; it would end up with nobody holding it. */
    @Override
    protected boolean canDrop(Player player, ItemStack item) {
        return !item.isSimilar(POTATO) && super.canDrop(player, item);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Events (they fire for every player on the server, so each checks isPlaying first)
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Player attacker = event.getPlayer();
        if (!isPlaying(attacker)) return;
        event.setCancelled(true);
        // Only to someone without a potato: nobody holds two.
        if (getState().isActive() && holders.containsKey(attacker.getUniqueId())
                && event.getAttacked() instanceof Player target && isPlaying(target) && !holders.containsKey(target.getUniqueId())) {
            pass(attacker, target);
        }
    }

    /** Nobody in the game takes damage of any kind; the potato is the only way out. */
    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageEvent event) {
        if (isPlaying(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHunger(FoodLevelChangeEvent event) {
        if (isPlaying(event.getEntity())) event.setCancelled(true);
    }

}
