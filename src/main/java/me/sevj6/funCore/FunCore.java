package me.sevj6.funCore;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.sevj6.funCore.command.NpcCommand;
import me.sevj6.funCore.command.economy.BalanceCommand;
import me.sevj6.funCore.command.economy.BalanceTopCommand;
import me.sevj6.funCore.command.economy.EcoCommand;
import me.sevj6.funCore.command.economy.PayCommand;
import me.sevj6.funCore.economy.Cash;
import me.sevj6.funCore.economy.Economy;
import me.sevj6.funCore.economy.EconomyListener;
import me.sevj6.funCore.game.GameManager;
import me.sevj6.funCore.npc.NpcListener;
import me.sevj6.funCore.npc.NpcManager;
import me.sevj6.funCore.player.PlayerStateManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class FunCore extends JavaPlugin {

    private static FunCore instance;

    private PlayerStateManager playerStateManager;
    private GameManager gameManager;
    private NpcManager npcManager;
    private Economy economy;

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
        gameManager = new GameManager(this);
        npcManager = new NpcManager(this);

        var plugins = getServer().getPluginManager();
        plugins.registerEvents(playerStateManager, this);
        plugins.registerEvents(gameManager, this);
        plugins.registerEvents(new NpcListener(npcManager), this);
        plugins.registerEvents(new EconomyListener(economy), this);

        registerCommands();

        // Players who were online through a /reload with a pending state get it back now.
        playerStateManager.restoreOnlinePlayers();
        getServer().getOnlinePlayers().forEach(economy::ensureAccount); // after a /reload
        npcManager.start();
    }

    @Override
    public void onDisable() {
        // Ends every game and restores everyone still in one.
        if (gameManager != null) gameManager.shutdown();
        if (npcManager != null) npcManager.shutdown();
        // Last, so anything paid out while games shut down is saved.
        if (economy != null) economy.shutdown();
        instance = null;
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();
            commands.register(new NpcCommand(npcManager).build(), "Create and edit NPCs");
            commands.register(new BalanceCommand(economy).build(), "Show your balance", List.of("bal", "money"));
            commands.register(new PayCommand(economy).build(), "Send cash to another player");
            commands.register(new BalanceTopCommand(economy).build(), "Show the richest players", List.of("balancetop"));
            commands.register(new EcoCommand(economy).build(), "Economy admin tools");
        });
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

    public NpcManager getNpcManager() {
        return npcManager;
    }

    public Economy getEconomy() {
        return economy;
    }
}
