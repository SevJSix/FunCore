package me.sevj6.funCore;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.sevj6.funCore.arena.ArenaManager;
import me.sevj6.funCore.arena.ArenaProtectionListener;
import me.sevj6.funCore.command.ArenaCommand;
import me.sevj6.funCore.command.GameCommand;
import me.sevj6.funCore.command.MenuTestCommand;
import me.sevj6.funCore.command.NpcCommand;
import me.sevj6.funCore.command.economy.BalanceCommand;
import me.sevj6.funCore.command.economy.BalanceTopCommand;
import me.sevj6.funCore.command.economy.EcoCommand;
import me.sevj6.funCore.command.economy.PayCommand;
import me.sevj6.funCore.economy.Cash;
import me.sevj6.funCore.economy.Economy;
import me.sevj6.funCore.economy.EconomyListener;
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

import java.util.List;

public final class FunCore extends JavaPlugin {

    private static FunCore instance;

    private PlayerStateManager playerStateManager;
    private GameManager gameManager;
    private ArenaManager arenaManager;
    private NpcManager npcManager;
    private Economy economy;
    private final MenuRegistry menuRegistry = new MenuRegistry();

    public static FunCore getInstance() {
        if (instance == null) throw new IllegalStateException("FunCore is not enabled");
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        economy = new Economy(this, readStartingBalance());
        economy.load();
        playerStateManager = new PlayerStateManager(this);
        arenaManager = new ArenaManager(this);
        arenaManager.load();
        gameManager = new GameManager(this);
        npcManager = new NpcManager(this, menuRegistry);
        npcManager.behaviors().register(GameBehavior.definition(gameManager));
        menuRegistry.register("menutest", player -> MenuTestCommand.mainMenu()); // TEMPORARY, goes with /menutest

        var plugins = getServer().getPluginManager();
        plugins.registerEvents(playerStateManager, this);
        plugins.registerEvents(gameManager, this);
        plugins.registerEvents(new NpcListener(npcManager), this);
        plugins.registerEvents(new NpcBehaviorListener(getSLF4JLogger()), this);
        plugins.registerEvents(new EconomyListener(economy), this);
        plugins.registerEvents(new MenuListener(this), this);
        plugins.registerEvents(new KitEditor.EditorListener(), this);
        plugins.registerEvents(new ArenaProtectionListener(arenaManager), this);

        registerCommands();
        registerGames();

        // Players who were online through a /reload with a pending state get it back now.
        playerStateManager.restoreOnlinePlayers();
        getServer().getOnlinePlayers().forEach(economy::ensureAccount); // after a /reload
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
        // Last, so anything paid out while games shut down is saved.
        if (economy != null) economy.shutdown();
        instance = null;
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();
            commands.register(new GameCommand(gameManager).build(), "Join, leave and manage minigames");
            commands.register(new ArenaCommand(arenaManager, gameManager).build(), "Set up minigame arenas");
            commands.register(new NpcCommand(npcManager).build(), "Create and edit NPCs");
            commands.register(new BalanceCommand(economy).build(), "Show your balance", List.of("bal", "money"));
            commands.register(new PayCommand(economy).build(), "Send cash to another player");
            commands.register(new BalanceTopCommand(economy).build(), "Show the richest players", List.of("balancetop"));
            commands.register(new EcoCommand(economy).build(), "Economy admin tools");
            commands.register(new MenuTestCommand().build(), "TEMPORARY: try out the menu framework");
        });
    }

    /** Every minigame on the server. */
    private void registerGames() {
        gameManager.register(HotPotatoGame.TYPE);
        gameManager.register(BridgeGame.TYPE);
        gameManager.register(ClutchGame.TYPE);

        // NPCs (menu behavior) and other menus can open "layout-<game>" for games with a kit.
        for (GameType type : gameManager.getTypes()) {
            if (!type.hasKit()) continue;
            menuRegistry.register("layout-" + type.getId(), player -> gameManager.createLayoutEditor(player, type));
        }
    }

    private long readStartingBalance() {
        String value = getConfig().getString("economy.starting-balance", "0");
        try {
            return Cash.parse(value);
        } catch (IllegalArgumentException e) {
            getSLF4JLogger().warn("Invalid economy.starting-balance '{}' in config.yml; using $0", value);
            return 0;
        }
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

    public Economy getEconomy() {
        return economy;
    }
}
