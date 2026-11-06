package me.sevj6.funCore;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.sevj6.funCore.command.NpcCommand;
import me.sevj6.funCore.game.GameManager;
import me.sevj6.funCore.npc.NpcListener;
import me.sevj6.funCore.npc.NpcManager;
import me.sevj6.funCore.player.PlayerStateManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class FunCore extends JavaPlugin {

    private static FunCore instance;

    private PlayerStateManager playerStateManager;
    private GameManager gameManager;
    private NpcManager npcManager;

    public static FunCore getInstance() {
        if (instance == null) throw new IllegalStateException("FunCore is not enabled");
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        playerStateManager = new PlayerStateManager(this);
        gameManager = new GameManager(this);
        npcManager = new NpcManager(this);

        var plugins = getServer().getPluginManager();
        plugins.registerEvents(playerStateManager, this);
        plugins.registerEvents(gameManager, this);
        plugins.registerEvents(new NpcListener(npcManager), this);

        registerCommands();

        // Players who were online through a /reload with a pending state get it back now.
        playerStateManager.restoreOnlinePlayers();
        npcManager.start();
    }

    @Override
    public void onDisable() {
        // Ends every game and restores everyone still in one.
        if (gameManager != null) gameManager.shutdown();
        if (npcManager != null) npcManager.shutdown();
        instance = null;
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();
            commands.register(new NpcCommand(npcManager).build(), "Create and edit NPCs");
        });
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
}
