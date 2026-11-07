package me.sevj6.funCore.games.hotpotato;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.economy.Cash;
import me.sevj6.funCore.game.LeaveReason;
import me.sevj6.funCore.game.Minigame;
import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hot Potato: a random player is handed a potato with a hidden fuse. Hit another player to pass it on. Whoever holds it
 * when the fuse runs out explodes and is out. The last player standing wins {@link #PRIZE}.
 *
 * <p>It needs no arena (it's played wherever the players are), which makes it a good first test of the minigame
 * framework: joining, the countdown, tasks, events, eliminations, winning, prizes and state restore.
 */
public final class HotPotatoGame extends Minigame {

    public static final String ID = "hotpotato";
    private static final long PRIZE = Cash.dollars(100);
    private static final int MIN_FUSE_SECONDS = 10, MAX_FUSE_SECONDS = 20;
    /** After a pass, the potato can't be passed again for this long (stops instant pass-backs). */
    private static final long PASS_COOLDOWN_MILLIS = 750;
    private static final long ROUND_BREAK_TICKS = 60;

    private static final ItemStack POTATO = ItemBuilder.of(Material.BAKED_POTATO)
            .name("<gold><bold>Hot Potato")
            .lore("<gray>Hit another player to pass it on!", "<red>Don't be holding it when it blows.")
            .glow()
            .build();

    private @Nullable UUID holder;
    private @Nullable UUID winner;
    private long passedAt;
    private int fuse;
    private @Nullable BukkitTask fuseTask;

    public HotPotatoGame(FunCore plugin) {
        super(plugin, ID, Utils.mm("<gold>Hot Potato"), 2, 16);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        broadcast(Utils.mm("<yellow>Hit another player to pass the potato. Don't be holding it when it explodes!"));
        Utils.title(audience(), Utils.mm("<gold>Hot Potato"), Utils.mm("<gray>Last one standing wins <gold><prize>",
                Placeholder.unparsed("prize", Cash.format(PRIZE))), 10, 40, 10);
        nextRound();
    }

    @Override
    protected void onEnd() {
        Player champion = winner == null ? null : Bukkit.getPlayer(winner);
        if (champion == null) {
            broadcast(Utils.mm("<gray>The game ended with no winner."));
            return;
        }
        plugin.getEconomy().deposit(champion.getUniqueId(), PRIZE, "minigame reward: " + ID);
        broadcast(Utils.mm("<gold><player></gold> <yellow>wins and earns <gold><prize></gold>!",
                Placeholder.component("player", champion.name()), Placeholder.unparsed("prize", Cash.format(PRIZE))));
        Utils.title(audience(), Utils.mm("<gold><player> wins!", Placeholder.component("player", champion.name())),
                Component.empty(), 10, 50, 10);
        Utils.playSound(audience(), "ui.toast.challenge_complete", 1f, 1f);
    }

    @Override
    protected void onReset() {
        holder = null;
        winner = null;
        fuseTask = null;
    }

    @Override
    protected void onPlayerLeave(Player player, LeaveReason reason) {
        if (!getState().isActive()) return;
        boolean wasHolder = player.getUniqueId().equals(holder);
        if (wasHolder) {
            holder = null;
            stopFuse();
        }
        // The last one left wins.
        List<Player> remaining = getPlayers();
        if (remaining.size() == 1) {
            winner = remaining.getFirst().getUniqueId();
            end();
            return;
        }
        if (wasHolder && remaining.size() > 1) {
            runTaskLater(this::nextRound, ROUND_BREAK_TICKS);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rounds
    // ------------------------------------------------------------------------------------------------------------

    private void nextRound() {
        if (!getState().isActive() || holder != null) return;
        List<Player> players = getPlayers();
        if (players.size() <= 1) {
            // Only reachable when an admin force-starts with one player; a real win goes through onPlayerLeave.
            broadcast(Utils.mm("<red>Hot Potato needs at least 2 players."));
            winner = null;
            end();
            return;
        }
        Player first = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        broadcast(Utils.mm("<yellow>New round! <gold><player></gold> has the potato.", Placeholder.component("player", first.name())));
        give(first);
        fuse = ThreadLocalRandom.current().nextInt(MIN_FUSE_SECONDS, MAX_FUSE_SECONDS + 1);
        fuseTask = runTaskTimer(this::tickFuse, 20L, 20L);
    }

    private void tickFuse() {
        Player current = holder == null ? null : Bukkit.getPlayer(holder);
        if (current == null) {
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
        audience().sendActionBar(Utils.mm("<gold><player></gold> <gray>has the potato <dark_gray>|</dark_gray> " + heat,
                Placeholder.component("player", current.name())));
        if (fuse <= 3) Utils.playPling(audience(), 2f);
    }

    private void explode(Player victim) {
        stopFuse();
        victim.getWorld().spawnParticle(Particle.EXPLOSION, victim.getLocation().add(0, 1, 0), 1);
        Utils.playSound(audience(), "entity.generic.explode", 1f, 1f);
        broadcast(Utils.mm("<red><player> exploded!", Placeholder.component("player", victim.name())));
        // onPlayerLeave picks the winner or starts the next round.
        leave(victim, LeaveReason.ELIMINATED);
    }

    private void stopFuse() {
        if (fuseTask != null) {
            fuseTask.cancel();
            fuseTask = null;
        }
    }

    private void pass(Player from, Player to) {
        long now = System.currentTimeMillis();
        if (now - passedAt < PASS_COOLDOWN_MILLIS) return;
        passedAt = now;
        take(from);
        give(to);
        broadcast(Utils.mm("<gray><from> passed the potato to <gold><to></gold>!",
                Placeholder.component("from", from.name()), Placeholder.component("to", to.name())));
    }

    private void give(Player player) {
        holder = player.getUniqueId();
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

    // ------------------------------------------------------------------------------------------------------------
    // Events (they fire for every player on the server, so each checks isPlaying first)
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Player attacker = event.getPlayer();
        if (!isPlaying(attacker)) return;
        event.setCancelled(true);
        if (getState().isActive() && attacker.getUniqueId().equals(holder)
                && event.getAttacked() instanceof Player target && isPlaying(target)) {
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

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent event) {
        if (isPlaying(event.getPlayer())) event.setCancelled(true);
    }
}
