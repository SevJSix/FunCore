package me.sevj6.funCore.tycoon;

import me.sevj6.funCore.FunCore;
import me.sevj6.funCore.tycoon.core.Money;
import me.sevj6.funCore.tycoon.core.data.PlayerData;
import me.sevj6.funCore.tycoon.core.sim.Cents;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Offset;
import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.tycoon.core.template.TemplateLoader;
import me.sevj6.funCore.tycoon.plot.FixtureJournal;
import me.sevj6.funCore.tycoon.plot.Fixtures;
import me.sevj6.funCore.tycoon.plot.PlotSlot;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Interaction;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tycoon gamemode. It isn't a minigame: there's no match, no arena and no end. Being in the tycoon world is what
 * makes someone a tycoon player.
 *
 * <p>The world holds one pasted map with a fixed set of plots (see {@link TycoonSettings}). Entering the world (with
 * {@code /tycoon}, or any other way) claims the first free plot and starts production; leaving it, or quitting, pays
 * out, removes everything the plugin added to the plot and frees it. With every plot taken, players can still walk
 * around as visitors, and the next plot to free up goes to one of them. The map itself is never changed: plots are
 * part of it.
 *
 * <p>One repeating task, every tick, drives every plot (see {@link me.sevj6.funCore.tycoon.core.sim.PlotSim}).
 * Money is the tycoon's own balance, kept with each player's progress (see {@link PlayerStore}), not the server's
 * economy: a rebirth can zero it without touching anyone's other money, and it can go far past the economy's $1T
 * limit. Income goes straight into it as items are collected. Buy pads take from it.
 */
public final class TycoonManager implements Listener {

    public static final String PERMISSION = "funcore.command.tycoon";
    public static final String PERMISSION_ADMIN = "funcore.command.tycoon.admin";

    private final FunCore plugin;
    private final File folder;
    private final NamespacedKey viewKey;
    private final Map<UUID, PlotSession> sessions = new HashMap<>();
    /** Plot slots in use, by index into {@link TycoonSettings#slots()}. */
    private final BitSet slots = new BitSet();
    /** Admins allowed to change blocks in the tycoon world (see {@link #toggleEditing}). */
    private final Set<UUID> editors = new HashSet<>();
    /** Every plot on the map, owned or not (empty until the map origin is set). */
    private final List<PlotSlot> plots = new ArrayList<>();
    private final FixtureJournal journal;
    private final PlayerStore store;
    private final TycoonSidebar sidebar = new TycoonSidebar();
    private final TycoonDebug debug = new TycoonDebug(this);
    /**
     * Progress of online players, loaded as they log in (off the main thread), so starting a plot never waits on the
     * disk. Kept until they quit.
     */
    private final Map<UUID, PlayerData> loaded = new ConcurrentHashMap<>();
    /** When each player was last sent a pad message, so standing on a plate doesn't spam them. */
    private final Map<UUID, Long> lastPadMessage = new HashMap<>();
    /** Clicks on the manual earner counted this second, per player: [second, clicks]. */
    private final Map<UUID, long[]> clicks = new HashMap<>();

    /** How often everyone's progress is saved while they play, in ticks. */
    private static final long SAVE_EVERY = 60 * 20;
    /** Gap between pad messages to one player, in milliseconds. */
    private static final long PAD_MESSAGE_GAP = 1000;

    private TycoonSettings settings;
    private @Nullable Template template;
    private @Nullable World world;
    private @Nullable BukkitTask task;
    private long tick;

    public TycoonManager(FunCore plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "tycoon");
        this.viewKey = new NamespacedKey(plugin, "tycoon-view");
        this.journal = new FixtureJournal(new File(folder, "fixtures.yml").toPath(), plugin.getSLF4JLogger());
        this.store = new PlayerStore(new File(folder, "players").toPath(), plugin.getSLF4JLogger());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Start and stop
    // ------------------------------------------------------------------------------------------------------------

    /** Loads the settings and template, makes (or loads) the world and starts the tick. */
    public void enable() {
        if (!folder.isDirectory() && !folder.mkdirs()) plugin.getSLF4JLogger().error("Could not create {}", folder);
        for (String name : List.of("config.yml", "template.yml")) {
            if (!new File(folder, name).exists()) plugin.saveResource("tycoon/" + name, false);
        }
        upgradeConfig();
        replaceOldTemplate();
        updateSampleTemplate();
        List<String> errors = new ArrayList<>();
        settings = TycoonSettings.load(new File(folder, "config.yml"), errors);
        TemplateLoader.Result result = loadTemplate();
        errors.addAll(result.errors());
        if (result.template() != null) errors.addAll(settings.check(result.template().size()));
        if (!errors.isEmpty()) {
            plugin.getSLF4JLogger().error("The tycoon is off until these are fixed (then /tycoon admin reload):");
            errors.forEach(error -> plugin.getSLF4JLogger().error("  {}", error));
        } else {
            template = result.template();
        }
        if (settings.origin() == null) {
            plugin.getSLF4JLogger().warn("Tycoon: the map origin isn't set, so the tycoon is off. Paste the map, "
                    + "then run /tycoon admin origin from the same block.");
        }
        world = createWorld(settings);
        for (Chunk chunk : world.getLoadedChunks()) removeLeftovers(List.of(chunk.getEntities()));
        addChunkTickets();
        sweepJournal();
        setUpPlots();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(new TycoonProtection(this), plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        // After a /reload: online players' progress, and their plots if they're in the world.
        for (Player player : Bukkit.getOnlinePlayers()) loaded.computeIfAbsent(player.getUniqueId(), id -> store.load(id).join());
        startEveryoneInTheWorld();
    }

    /** Saves everyone's progress and removes everything the plugin added to the world. */
    public void disable() {
        if (task != null) task.cancel();
        for (PlotSession session : sessions.values()) {
            save(session);
            session.stop();
            sidebar.hide(Bukkit.getPlayer(session.player), session.player);
        }
        sessions.clear();
        slots.clear();
        tearDownPlots();
        journal.shutdown();
        store.shutdown();
        if (world != null) world.removePluginChunkTickets(plugin);
    }

    /** Removes blocks a crashed earlier run left in the map (see {@link FixtureJournal}). */
    private void sweepJournal() {
        if (world == null || settings.origin() == null) return;
        journal.load().forEach((slot, blocks) -> {
            if (slot < 0 || slot >= settings.slots().size()) {
                plugin.getSLF4JLogger().warn("Tycoon: fixtures.yml lists plot {}, which isn't in config.yml; skipped", slot + 1);
                return;
            }
            int removed = Fixtures.sweep(slotOrigin(slot), blocks);
            if (removed > 0) plugin.getSLF4JLogger().info("Tycoon: removed {} leftover block(s) from plot {}", removed, slot + 1);
        });
    }

    /** Makes the map's plots (when the origin is set) and seals every vault door: nobody owns a plot yet. */
    private void setUpPlots() {
        if (world == null || settings.origin() == null) return;
        for (int i = 0; i < settings.slots().size(); i++) {
            Fixtures fixtures = new Fixtures(i, slotOrigin(i), viewKey, journal, plugin.getSLF4JLogger());
            plots.add(new PlotSlot(i, slotOrigin(i), fixtures));
        }
        resealVaults();
    }

    /**
     * Puts up what every plot shows whoever owns it, as the current template has it: the vault door's seal (owners
     * can't open theirs yet: there are no rebirths) and the gate sign.
     */
    private void resealVaults() {
        if (template == null) return;
        for (PlotSlot plot : plots) {
            plot.openVault();
            plot.sealVault(template);
            plot.showGate(template, ownerName(plot.index()));
        }
    }

    /** The name of whoever owns slot {@code index}, or {@code null}. */
    private @Nullable String ownerName(int index) {
        for (PlotSession session : sessions.values()) {
            if (session.slot != index) continue;
            Player player = Bukkit.getPlayer(session.player);
            return player != null ? player.getName() : Bukkit.getOfflinePlayer(session.player).getName();
        }
        return null;
    }

    /** Takes everything the plugin added off every plot: the map is as pasted. */
    private void tearDownPlots() {
        for (PlotSlot plot : plots) plot.fixtures().clear();
        plots.clear();
    }

    /**
     * Brings an existing {@code config.yml} up to date: settings added since it was written get their defaults, and
     * settings that no longer exist (the old grid of plots: {@code plot-y}, {@code spacing}, {@code max-slots}) are
     * removed. Anything an admin set is kept.
     */
    private void upgradeConfig() {
        File file = new File(folder, "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;
        if (config.isString("world") && !config.contains("map.world")) {
            config.set("map.world", config.getString("world"));
            changed = true;
        }
        for (String obsolete : List.of("world", "plot-y", "spacing", "max-slots", "income-flush-seconds")) {
            if (config.contains(obsolete)) {
                config.set(obsolete, null);
                changed = true;
            }
        }
        try (var in = plugin.getResource("tycoon/config.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                for (String key : defaults.getKeys(true)) {
                    if (defaults.isConfigurationSection(key) || config.contains(key)) continue;
                    config.set(key, defaults.get(key));
                    changed = true;
                }
            }
        } catch (IOException e) {
            plugin.getSLF4JLogger().warn("Could not read the default tycoon config", e);
        }
        if (!changed) return;
        try {
            config.save(file);
            plugin.getSLF4JLogger().info("Tycoon: updated config.yml with the current settings");
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Could not save {}", file, e);
        }
    }

    /**
     * A template from an older schema can't describe plots on the map (it was for a different design), so it's moved
     * aside as {@code template.v<schema>.yml} and the current sample takes its place.
     */
    private void replaceOldTemplate() {
        File file = new File(folder, "template.yml");
        if (!file.exists()) return;
        int schema;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            schema = root instanceof Map<?, ?> map && map.get("schema") instanceof Number n ? n.intValue() : 0;
        } catch (IOException | RuntimeException e) {
            return; // unreadable: loading reports it
        }
        if (schema >= TemplateLoader.SCHEMA) return;
        File old = new File(folder, "template.v" + schema + ".yml");
        try {
            Files.move(file.toPath(), old.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            plugin.saveResource("tycoon/template.yml", false);
            plugin.getSLF4JLogger().warn("Tycoon: template.yml was schema {}, which doesn't fit the map; it's now {} and "
                    + "template.yml is the current sample", schema, old.getName());
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Could not replace the old tycoon template", e);
        }
    }

    /**
     * Keeps an untouched sample template up to date. The SHA-256 of the sample last written is kept in
     * {@code template.sample-sha256}: if {@code template.yml} still matches it (nobody edited it) and the plugin's
     * sample has changed, the file is replaced (the old one kept as {@code template.previous.yml}). An edited template
     * is never overwritten; the newer sample is put next to it as {@code template.sample.yml} instead.
     */
    private void updateSampleTemplate() {
        java.nio.file.Path file = new File(folder, "template.yml").toPath();
        java.nio.file.Path hashFile = new File(folder, "template.sample-sha256").toPath();
        try (var in = plugin.getResource("tycoon/template.yml")) {
            if (in == null || !Files.exists(file)) return;
            byte[] sample = in.readAllBytes();
            byte[] current = Files.readAllBytes(file);
            String sampleHash = sha256(sample), currentHash = sha256(current);
            if (currentHash.equals(sampleHash)) {
                Files.writeString(hashFile, sampleHash, StandardCharsets.UTF_8);
                return;
            }
            String recorded = Files.exists(hashFile) ? Files.readString(hashFile, StandardCharsets.UTF_8).trim() : "";
            if (currentHash.equals(recorded)) {
                Files.copy(file, file.resolveSibling("template.previous.yml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.write(file, sample);
                Files.writeString(hashFile, sampleHash, StandardCharsets.UTF_8);
                plugin.getSLF4JLogger().info("Tycoon: template.yml was the old sample, so it's been updated to the "
                        + "current one (the old one is template.previous.yml)");
            } else {
                Files.write(file.resolveSibling("template.sample.yml"), sample);
                plugin.getSLF4JLogger().info("Tycoon: template.yml has been edited, so it's kept; the current sample is "
                        + "in template.sample.yml for comparison");
            }
        } catch (IOException e) {
            plugin.getSLF4JLogger().warn("Could not check the tycoon template against the sample", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private World createWorld(TycoonSettings settings) {
        World existing = Bukkit.getWorld(settings.world());
        if (existing != null) return existing;
        int spawnY = settings.origin() == null ? 64 : settings.origin().y() + settings.hub().y();
        World created = new WorldCreator(settings.world())
                .generator(new VoidGenerator(spawnY))
                .generateStructures(false)
                .createWorld();
        if (created == null) throw new IllegalStateException("could not create the tycoon world " + settings.world());
        created.setGameRule(GameRules.SPAWN_MOBS, false);
        created.setGameRule(GameRules.ADVANCE_WEATHER, false);
        created.setGameRule(GameRules.ADVANCE_TIME, false);
        created.setGameRule(GameRules.FALL_DAMAGE, false);
        created.setTime(6000);
        created.setStorm(false);
        return created;
    }

    /**
     * Keeps the whole map loaded while the tycoon runs. Production doesn't need it (it's simulated as data), but the
     * plugin's entities are never saved, so they'd vanish whenever their chunk unloaded. The map is small, so this
     * costs little and keeps every plot looking right.
     */
    private void addChunkTickets() {
        if (world == null) return;
        world.removePluginChunkTickets(plugin);
        Offset origin = settings.origin();
        if (origin == null) return;
        Offset size = settings.mapSize();
        for (int cx = origin.x() >> 4; cx <= (origin.x() + size.x() - 1) >> 4; cx++) {
            for (int cz = origin.z() >> 4; cz <= (origin.z() + size.z() - 1) >> 4; cz++) {
                world.addPluginChunkTicket(cx, cz, plugin);
            }
        }
    }

    private TemplateLoader.Result loadTemplate() {
        File file = new File(folder, "template.yml");
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return TemplateLoader.load(new Yaml().load(reader));
        } catch (IOException | RuntimeException e) {
            return new TemplateLoader.Result(null, List.of("template.yml: could not be read: " + e.getMessage()));
        }
    }

    /**
     * Reads the template and settings again. Valid: they're used from now on and every plot restarts with them.
     * Invalid: nothing changes, and the errors are returned. The map (world, origin, slots) can only change while
     * nobody has a plot.
     */
    public List<String> reload() {
        List<String> errors = new ArrayList<>();
        TycoonSettings next = TycoonSettings.load(new File(folder, "config.yml"), errors);
        TemplateLoader.Result result = loadTemplate();
        errors.addAll(result.errors());
        if (result.template() != null) errors.addAll(next.check(result.template().size()));
        if (!next.sameMap(settings) && !sessions.isEmpty()) {
            errors.add("config.yml: the map's world, origin and slots can only change while nobody has a plot");
        }
        if (!next.world().equals(settings.world())) errors.add("config.yml: map.world only changes on a restart");
        if (!errors.isEmpty()) return errors;
        boolean mapChanged = !next.sameMap(settings);
        if (mapChanged) tearDownPlots(); // with the old origin, before it changes
        settings = next;
        template = result.template();
        addChunkTickets();
        if (mapChanged) {
            sweepJournal();
            setUpPlots();
        } else {
            resealVaults();
        }
        for (PlotSession session : sessions.values()) session.build(template, tick);
        startEveryoneInTheWorld(); // the tycoon may have been off
        return List.of();
    }

    /**
     * Sets the map origin to a block (where the map's minimum corner was pasted) and saves it.
     *
     * @return a problem, or {@code null} if it worked
     */
    public @Nullable String setOrigin(Location at) {
        if (!isTycoonWorld(at.getWorld())) return "Stand in the tycoon world (" + settings.world() + ").";
        if (!sessions.isEmpty()) return "Everyone has to leave their plot first (" + sessions.size() + " in use).";
        File file = new File(folder, "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("map.origin", List.of(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Could not save {}", file, e);
            return "Couldn't save config.yml: " + e.getMessage();
        }
        List<String> errors = reload();
        return errors.isEmpty() ? null : "Saved, but the tycoon couldn't reload: " + String.join("; ", errors);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Places
    // ------------------------------------------------------------------------------------------------------------

    public boolean isTycoonWorld(@Nullable World world) {
        return world != null && world.equals(this.world);
    }

    public @Nullable World getWorld() {
        return world;
    }

    /** Whether plots can be handed out: the template loaded and the map origin is set. */
    public boolean isAvailable() {
        return template != null && world != null && settings.origin() != null && !plots.isEmpty();
    }

    /** A point from the map origin, in the world. Needs the origin set. */
    private Location onMap(Offset offset, double dx, double dy, double dz) {
        Offset origin = java.util.Objects.requireNonNull(settings.origin(), "map origin");
        return new Location(world, origin.x() + offset.x() + dx, origin.y() + offset.y() + dy, origin.z() + offset.z() + dz);
    }

    /** Where visitors go: the hub, or the world's spawn while the map origin isn't set. */
    public Location hub() {
        if (world == null) return Bukkit.getWorlds().getFirst().getSpawnLocation();
        if (settings.origin() == null) return world.getSpawnLocation();
        Location hub = onMap(settings.hub(), 0.5, 0, 0.5);
        hub.setYaw(settings.hubYaw());
        return hub;
    }

    /** Slot {@code index}'s plot origin (0-based). */
    private Location slotOrigin(int index) {
        return onMap(settings.slots().get(index), 0, 0, 0);
    }

    /** Where a slot's owner spawns (0-based index), for admins checking the map lines up. */
    public @Nullable Location slotSpawn(int index) {
        if (template == null || settings.origin() == null || index < 0 || index >= settings.slots().size()) return null;
        Location spawn = slotOrigin(index).add(template.spawn().x() + 0.5, template.spawn().y(), template.spawn().z() + 0.5);
        spawn.setYaw(template.spawnYaw());
        return spawn;
    }

    /** The lowest point players can fall to before they're caught, or {@code null} without a map. */
    public @Nullable Double catchBelowY() {
        return settings.origin() == null ? null : (double) settings.origin().y() - 8;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Entering and leaving
    // ------------------------------------------------------------------------------------------------------------

    /**
     * {@code /tycoon}: takes the player to their plot, claiming one first if they don't have one. With every plot
     * taken, they're sent to the hub as a visitor instead.
     *
     * @return a problem to show them, or {@code null} if it worked
     */
    public @Nullable String enter(Player player) {
        if (!isAvailable()) return "The tycoon isn't open yet.";
        if (plugin.getGameManager().isInGame(player)) return "Leave your game first (/game leave).";
        PlotSession session = sessions.get(player.getUniqueId());
        if (session == null) session = start(player, false);
        if (session == null) {
            player.teleport(hub());
            player.sendMessage(Utils.prefixed(Utils.mm("<yellow>Every plot is taken, so you're visiting. "
                    + "<gray>You'll get the next plot that frees up.")));
            return null;
        }
        player.teleport(session.spawn());
        return null;
    }

    /** {@code /tycoon leave}: back to the server spawn (which ends their session). */
    public void leave(Player player) {
        Location spawn = plugin.getServerSpawn().getLocation();
        if (spawn == null || isTycoonWorld(spawn.getWorld())) spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
        player.teleport(spawn);
    }

    /**
     * Gives a player the first free plot.
     *
     * @param teleport whether to send them to it (they're already in the world, somewhere)
     * @return their session, or {@code null} if every plot is taken
     */
    private @Nullable PlotSession start(Player player, boolean teleport) {
        PlotSession existing = sessions.get(player.getUniqueId());
        if (existing != null || !isAvailable()) return existing;
        int slot = slots.nextClearBit(0);
        if (slot >= settings.slots().size()) return null;
        slots.set(slot);
        PlayerData data = loaded.computeIfAbsent(player.getUniqueId(), id -> store.load(id).join());
        PlotSession session = new PlotSession(player.getUniqueId(), plots.get(slot), data, viewKey, settings.maxVisibleItems());
        try {
            session.build(template, tick);
        } catch (RuntimeException e) {
            plugin.getSLF4JLogger().error("Could not start {}'s tycoon plot", player.getName(), e);
            slots.clear(slot);
            return null;
        }
        sessions.put(player.getUniqueId(), session);
        List<String> unknown = session.unknownOwned();
        if (!unknown.isEmpty()) {
            plugin.getSLF4JLogger().warn("Tycoon: {} owns {}, which the template doesn't have; kept in their save but not shown",
                    player.getName(), unknown);
        }
        plots.get(slot).showGate(template, player.getName());
        sidebar.show(player);
        updateSidebar(player, session);
        if (teleport) player.teleport(session.spawn());
        player.sendMessage(Utils.prefixed(Utils.mm("<green>Plot <slot> is yours! <gray>Your droppers are running.",
                Placeholder.unparsed("slot", String.valueOf(slot + 1)))));
        return session;
    }

    /** Gives every player in the world without a plot one, while there are free plots. */
    private void startEveryoneInTheWorld() {
        if (!isAvailable()) return;
        for (Player player : world.getPlayers()) {
            if (sessions.containsKey(player.getUniqueId())) continue;
            if (start(player, false) == null) break; // full
        }
    }

    /** Pays out, stops production and frees the plot, which goes to a visitor if one is waiting. */
    private void end(UUID player) {
        PlotSession session = sessions.remove(player);
        if (session == null) return;
        if (session.testMode()) {
            loaded.put(player, session.progressToKeep()); // back to before the test; the file already has it
        } else {
            save(session);
        }
        session.stop();
        sidebar.hide(Bukkit.getPlayer(player), player);
        slots.clear(session.slot);
        if (template != null) plots.get(session.slot).showGate(template, null);
        offerFreedPlot(player);
    }

    /** A plot just freed up: the first visitor in the world (other than whoever left) gets it. */
    private void offerFreedPlot(UUID leaving) {
        if (!isAvailable()) return;
        for (Player visitor : world.getPlayers()) {
            if (visitor.getUniqueId().equals(leaving) || sessions.containsKey(visitor.getUniqueId())) continue;
            PlotSession session = start(visitor, false);
            if (session != null) {
                visitor.sendMessage(Utils.prefixed(Utils.mm("<gray>Run <white>/tycoon</white> to go to it.")));
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (isTycoonWorld(event.getFrom())) {
            end(player.getUniqueId());
            editors.remove(player.getUniqueId());
        }
        if (isTycoonWorld(player.getWorld()) && isAvailable()) {
            // Arrived some other way than /tycoon (a /tp, a portal): give them a plot too, if there's one free.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && isTycoonWorld(player.getWorld())) start(player, false);
            });
        }
    }

    /** Someone logging in already in the tycoon world (with the server spawn off) gets a plot. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && isTycoonWorld(player.getWorld())) start(player, false);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        end(id);
        editors.remove(id);
        loaded.remove(id);
        lastPadMessage.remove(id);
        clicks.remove(id);
    }

    /**
     * Loads a joining player's progress before they're in, off the main thread. It's queued behind any save still
     * being written (from leaving a moment ago), so it's never stale.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        loaded.put(event.getUniqueId(), store.load(event.getUniqueId()).join());
    }

    /** The plugin's entities are never saved, but after a crash any that were can turn up: they're removed on load. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (isTycoonWorld(event.getWorld())) removeLeftovers(event.getEntities());
    }

    private void removeLeftovers(Collection<Entity> entities) {
        for (Entity entity : entities) {
            if (entity.getPersistentDataContainer().has(viewKey, PersistentDataType.BYTE)) entity.remove();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Edit mode
    // ------------------------------------------------------------------------------------------------------------

    /** Turns edit mode on or off for an admin: in it, they can change blocks in the tycoon world (to fix the map). */
    public boolean toggleEditing(Player player) {
        if (editors.remove(player.getUniqueId())) return false;
        editors.add(player.getUniqueId());
        return true;
    }

    public boolean isEditing(Player player) {
        return editors.contains(player.getUniqueId());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------------------------------------------------

    private void tick() {
        tick++;
        for (PlotSession session : sessions.values()) {
            try {
                session.sim().tick(tick);
                session.animate(tick);
            } catch (RuntimeException e) {
                plugin.getSLF4JLogger().error("Error running tycoon plot {}", session.slot + 1, e);
            }
        }
        if (tick % 10 == 0) {
            for (PlotSession session : sessions.values()) {
                Player player = Bukkit.getPlayer(session.player);
                if (player != null) updateSidebar(player, session);
            }
        }
        if (tick % SAVE_EVERY == 0) sessions.values().forEach(this::save);
        debug.tick(tick);
    }

    private void updateSidebar(Player player, PlotSession session) {
        sidebar.update(player, session.data().balance(), session.incomePerSecond(), session.data().rebirths());
    }

    /**
     * Saves a player's progress (in the background). The one way progress is saved, so test mode can't be missed: a
     * plot in test mode is never saved.
     */
    private void save(PlotSession session) {
        if (session.testMode()) return;
        store.save(session.player, session.data());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Buy pads
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Stepping on a pad buys it, on your own plot. (The plate never presses: protection cancels the event after this,
     * which is why this runs first.)
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPad(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        if (!isTycoonWorld(player.getWorld())) return;
        Block block = event.getClickedBlock();
        for (PlotSession session : sessions.values()) {
            Location origin = session.origin;
            Offset at = new Offset(block.getX() - origin.getBlockX(), block.getY() - origin.getBlockY(), block.getZ() - origin.getBlockZ());
            String id = session.padAt(at);
            if (id == null) continue;
            if (!session.player.equals(player.getUniqueId())) {
                padMessage(player, "<gray>This is <white><owner></white>'s plot.", Placeholder.unparsed("owner",
                        String.valueOf(ownerName(session.slot))));
                return;
            }
            buy(player, session, id, block.getLocation().add(0.5, 0.3, 0.5));
            return;
        }
    }

    private void buy(Player player, PlotSession session, String id, Location pad) {
        PlotSession.Offer offer = session.offer(id);
        if (offer == null) return;
        switch (session.buy(id)) {
            case OK -> {
                save(session);
                Utils.playSound(player, "entity.player.levelup", 0.7f, 1.4f);
                pad.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, pad, 20, 0.4, 0.3, 0.4, 0);
                player.sendActionBar(Utils.mm("<green>Bought <white><name></white> for <gold><price>",
                        Placeholder.unparsed("name", session.boughtName(id)), Placeholder.unparsed("price", Money.format(offer.price()))));
                updateSidebar(player, session);
            }
            case TOO_POOR -> padMessage(player, "<red>Need <gold><more></gold> more", Placeholder.unparsed("more",
                    Money.format(offer.price() - session.data().balance())));
            case NEEDS_REBIRTHS -> {
                Component component = session.template().component(id);
                if (component != null) padMessage(player, "<red>Requires Rebirth <n>", Placeholder.unparsed("n",
                        String.valueOf(component.common().minRebirths())));
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // The manual earner (clicked, left or right)
    // ------------------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRightClickNode(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Interaction)) return;
        if (clickNode(event.getPlayer(), event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLeftClickNode(PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Interaction)) return;
        if (clickNode(event.getPlayer(), event.getAttacked())) event.setCancelled(true);
    }

    /**
     * A click on a plot's manual earner: the owner earns what a click is worth, up to the clicks-a-second cap.
     *
     * @return whether the entity was a manual earner (so the click is used up)
     */
    private boolean clickNode(Player player, Entity entity) {
        if (!isTycoonWorld(player.getWorld())) return false;
        for (PlotSession session : sessions.values()) {
            if (!session.isManualNode(entity)) continue;
            if (!session.player.equals(player.getUniqueId())) {
                padMessage(player, "<gray>This is <white><owner></white>'s plot.", Placeholder.unparsed("owner",
                        String.valueOf(ownerName(session.slot))));
                return true;
            }
            Template.Manual manual = session.template().manual();
            if (manual == null || !countClick(player.getUniqueId(), manual.maxClicks())) return true;
            long earned = session.mine();
            Location at = entity.getLocation().add(0, 0.8, 0);
            at.getWorld().spawnParticle(Particle.BLOCK, at, 6, 0.3, 0.3, 0.3, 0,
                    org.bukkit.Material.DEEPSLATE_GOLD_ORE.createBlockData());
            Utils.playSound(player, "block.deepslate.hit", 0.6f, 0.8f + (float) Math.random() * 0.4f);
            player.sendActionBar(Utils.mm("<gold>+<amount>", Placeholder.unparsed("amount", Money.format(earned))));
            return true;
        }
        return false;
    }

    /** Counts a click toward this second; {@code false} if it's over the cap (it doesn't count). */
    private boolean countClick(UUID player, int max) {
        long second = tick / 20;
        long[] count = clicks.computeIfAbsent(player, id -> new long[]{second, 0});
        if (count[0] != second) {
            count[0] = second;
            count[1] = 0;
        }
        if (count[1] >= max) return false;
        count[1]++;
        return true;
    }

    /** An action-bar message about a pad, at most about once a second per player. */
    private void padMessage(Player player, String miniMessage, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        long now = System.currentTimeMillis();
        Long last = lastPadMessage.get(player.getUniqueId());
        if (last != null && now - last < PAD_MESSAGE_GAP) return;
        lastPadMessage.put(player.getUniqueId(), now);
        player.sendActionBar(Utils.mm(miniMessage, resolvers));
    }

    // ------------------------------------------------------------------------------------------------------------
    // For commands
    // ------------------------------------------------------------------------------------------------------------

    /**
     * For testing: gives a player's plot a component (or every one, with {@code "all"}) without buying it.
     *
     * @return a problem, or {@code null} if it worked
     */
    public @Nullable String grant(Player player, String id) {
        PlotSession session = sessions.get(player.getUniqueId());
        if (session == null || template == null) return "You need a plot first (/tycoon).";
        if (!id.equals("all") && template.component(id) == null) return "There's no component '" + id + "'.";
        enterTestMode(session, player);
        session.grant(id.equals("all") ? List.copyOf(template.components().keySet()) : List.of(id), tick);
        return null;
    }

    /** {@code /tycoon admin debug}: the particle view of where everything lands, for a minute. */
    public void startDebug(Player player) {
        debug.start(player, tick);
    }

    /** @return whether it was on */
    public boolean stopDebug(Player player) {
        return debug.stop(player);
    }

    /** Every plot on the map (empty until the map origin is set). */
    public List<PlotSlot> plots() {
        return Collections.unmodifiableList(plots);
    }

    /**
     * Puts a plot in test mode (an admin testing tool was used on it). Its progress is saved first; after that nothing
     * about the session is saved, and when it ends the player goes back to how they were. Tells the owner the first time.
     */
    public void enterTestMode(PlotSession session, Player player) {
        if (session.testMode()) return;
        save(session);
        session.enterTestMode();
        player.sendMessage(Utils.prefixed(Utils.mm("<yellow>Your plot is in test mode: <gray>nothing from now on is saved. "
                + "When you leave the tycoon, your money and purchases go back to how they were.")));
    }

    /**
     * For testing: gives or sets a player's tycoon balance (test mode).
     *
     * @return a problem, or {@code null} if it worked
     */
    public @Nullable String testMoney(Player player, long cents, boolean set) {
        PlotSession session = sessions.get(player.getUniqueId());
        if (session == null) return "You need a plot first (/tycoon).";
        enterTestMode(session, player);
        PlayerData data = session.data();
        data.setBalance(set ? cents : Cents.add(data.balance(), cents));
        updateSidebar(player, session);
        return null;
    }

    /**
     * Wipes a player's tycoon progress for good (balance, purchases, rebirths, trophies). Their plot restarts if they
     * have one. Works for offline players.
     */
    public void reset(UUID player) {
        PlayerData fresh = new PlayerData();
        PlotSession session = sessions.get(player);
        if (session != null) {
            session.replaceData(fresh);
            loaded.put(player, fresh);
            store.save(player, fresh); // not save(session): a reset is real, test mode or not
            session.build(template, tick);
            Player online = Bukkit.getPlayer(player);
            if (online != null) updateSidebar(online, session);
        } else {
            if (loaded.containsKey(player)) loaded.put(player, fresh);
            store.save(player, fresh);
        }
    }

    public @Nullable Template template() {
        return template;
    }

    public Collection<PlotSession> sessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public @Nullable PlotSession session(UUID player) {
        return sessions.get(player);
    }

    public TycoonSettings settings() {
        return settings;
    }
}
