package me.sevj6.funCore.game;

/** Why a player was removed from a {@link Minigame}. */
public enum LeaveReason {
    /** The player chose to leave (e.g. a leave command). */
    LEFT(true, true),
    /** The player disconnected. Their state stays saved and is restored when they next join the server. */
    DISCONNECT(false, true),
    /** The game (or an admin) removed the player. */
    REMOVED(true, true),
    /**
     * The player was knocked out by the game's rules (exploded, fell, ran out of lives). No generic "left" message is
     * shown, since the game announces it in its own words.
     */
    ELIMINATED(true, true),
    /** The game ended normally. */
    GAME_ENDED(true, false),
    /** The game was force-stopped (plugin disable, unregister, admin stop). */
    SHUTDOWN(true, false);

    private final boolean restoresImmediately;
    private final boolean midGame;

    LeaveReason(boolean restoresImmediately, boolean midGame) {
        this.restoresImmediately = restoresImmediately;
        this.midGame = midGame;
    }

    /** Whether the player's saved state is restored right away (false only for disconnects). */
    public boolean restoresImmediately() {
        return restoresImmediately;
    }

    /**
     * Whether this player left while the game kept going, as opposed to being removed because the whole game is
     * stopping. Only mid-game leaves trigger player-count checks (cancel the countdown, end an empty game).
     */
    public boolean isMidGame() {
        return midGame;
    }
}
