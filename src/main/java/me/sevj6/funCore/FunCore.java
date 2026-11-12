package me.sevj6.funCore;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.sevj6.funCore.arena.ArenaManager;
import me.sevj6.funCore.arena.ArenaProtectionListener;
import me.sevj6.funCore.command.ArenaCommand;
import me.sevj6.funCore.command.GameCommand;
import me.sevj6.funCore.command.MenuTestCommand;
import me.sevj6.funCore.command.SetSpawnCommand;
import me.sevj6.funCore.command.PingCommand;
import me.sevj6.funCore.command.MusicCommand;
import me.sevj6.funCore.command.LeaderboardCommand;
import me.sevj6.funCore.spawn.ServerSpawn;
import me.sevj6.funCore.music.MusicManager;
import me.sevj6.funCore.leaderboard.LeaderboardManager;
import me.sevj6.funCore.npc.behavior.LeaderboardBehavior;
import me.sevj6.funCore.npc.behavior.SongBehavior;
import me.sevj6.funCore.command.NpcCommand;
import me.sevj6.funCore.game.GameManager;
import me.sevj6.funCore.game.GameType;
import me.sevj6.funCore.games.bridge.BridgeGame;
import me.sevj6.funCore.games.clutch.ClutchGame;
import me.sevj6.funCore.games.hotpotato.HotPotatoGame;
import me.sevj6.funCore.kit.KitEditor;
import me.sevj6.funCore.menu.MenuListener;
import me.sevj6.funCore.menu.MenuRegistry;
import me.sevj6.funCore.npc.behavior.GameBehavior;
import me.sevj6.funCore.npc.behavior.NpcBehaviorListener;
import me.sevj6.funCore.npc.NpcListener;
import me.sevj6.funCore.npc.NpcManager;
import me.sevj6.funCore.player.PlayerStateManager;
import org.bukkit.plugin.java.JavaPlugin;


public final class FunCore extends JavaPlugin {

    private static FunCore instance;

    private PlayerStateManager playerStateManager;
    private ServerSpawn serverSpawn;
    private MusicManager music;
    private LeaderboardManager leaderboards;
    private GameManager gameManager;
    private ArenaManager arenaManager;
    private NpcManager npcManager;
    private final MenuRegistry menuRegistry = new MenuRegistry();

    public static FunCore getInstance() {
        if (instance == null) throw new IllegalStateException("FunCore is not enabled");
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        // Older config.yml files get sections added since (like spawn), with their defaults.
        getConfig().options().copyDefaults(true);
        saveConfig();

        playerStateManager = new PlayerStateManager(this);
        arenaManager = new ArenaManager(this);
        arenaManager.load();
        gameManager = new GameManager(this);
        npcManager = new NpcManager(this, menuRegistry);
        npcManager.behaviors().register(GameBehavior.definition(gameManager));
        leaderboards = new LeaderboardManager(this);
        npcManager.behaviors().register(LeaderboardBehavior.definition(this, leaderboards));
        menuRegistry.register("menutest", player -> MenuTestCommand.mainMenu()); // TEMPORARY, goes with /menutest

        var plugins = getServer().getPluginManager();
        plugins.registerEvents(playerStateManager, this);
        music = new MusicManager(this);
        music.enable();
        plugins.registerEvents(music, this);
        npcManager.behaviors().register(SongBehavior.definition(music));
        serverSpawn = new ServerSpawn(this);
        serverSpawn.load();
        plugins.registerEvents(serverSpawn, this);
        plugins.registerEvents(gameManager, this);
        plugins.registerEvents(new NpcListener(npcManager), this);
        plugins.registerEvents(new NpcBehaviorListener(getSLF4JLogger()), this);
        plugins.registerEvents(new MenuListener(this), this);
        plugins.registerEvents(new KitEditor.EditorListener(), this);
        plugins.registerEvents(new ArenaProtectionListener(arenaManager), this);

        registerCommands();
        registerGames();

        // Players who were online through a /reload with a pending state get it back now.
        playerStateManager.restoreOnlinePlayers();
        npcManager.start();
    }

    @Override
    public void onDisable() {
        // First, so nobody is left looking at a menu that no longer responds.
        MenuListener.closeAll();
        // Ends every game and restores everyone still in one.
        if (gameManager != null) gameManager.shutdown();
        // After the games, which start restoring their arenas as they stop; this finishes those restores.
        if (arenaManager != null) arenaManager.shutdown();
        if (npcManager != null) npcManager.shutdown();
        if (music != null) music.shutdown();
        instance = null;
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();
            commands.register(new GameCommand(gameManager, music).build(), "Join, leave and manage minigames");
            commands.register(new ArenaCommand(arenaManager, gameManager).build(), "Set up minigame arenas");
            commands.register(new NpcCommand(npcManager).build(), "Create and edit NPCs");
            commands.register(new MusicCommand(music).build(), "Play songs, or turn music off for yourself");
            commands.register(new LeaderboardCommand(leaderboards).build(), "Manage the all-time leaderboards");
            commands.register(new SetSpawnCommand(serverSpawn).build(), "Set the server spawn to where you stand");
            commands.register(new PingCommand().build(), "Show your ping (or another player's)");
            commands.register(new MenuTestCommand().build(), "TEMPORARY: try out the menu framework");
        });
    }

    /** Every minigame on the server. */
    private void registerGames() {
        gameManager.register(HotPotatoGame.TYPE);
        gameManager.register(BridgeGame.TYPE);
        gameManager.register(ClutchGame.TYPE);
        leaderboards.define(ClutchGame.LEADERBOARD);

        // NPCs (menu behavior) and other menus can open "layout-<game>" for games with a kit.
        for (GameType type : gameManager.getTypes()) {
            if (!type.hasKit()) continue;
            menuRegistry.register("layout-" + type.getId(), player -> gameManager.createLayoutEditor(player, type));
        }
    }

    /** Songs and everything playing them. Check {@link MusicManager#isAvailable()}: it needs NoteBlockAPI. */
    public MusicManager getMusic() {
        return music;
    }

    /** Records kept forever, like Clutch's best runs (NPCs can show them with the leaderboard behavior). */
    public LeaderboardManager getLeaderboards() {
        return leaderboards;
    }

    public ServerSpawn getServerSpawn() {
        return serverSpawn;
    }

    public PlayerStateManager getPlayerStateManager() {
        return playerStateManager;
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public NpcManager getNpcManager() {
        return npcManager;
    }

    /** Named menus that config files (e.g. NPC menu behaviors) can open. */
    public MenuRegistry getMenuRegistry() {
        return menuRegistry;
    }
}
