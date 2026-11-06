package me.sevj6.funCore.game;

/**
 * Lifecycle of a {@link Minigame}:
 * <pre>
 * WAITING -> STARTING (countdown) -> RUNNING -> ENDING -> (reset) -> WAITING
 *               |                                            ^
 *               +----- not enough players (cancelled) -------+
 * </pre>
 */
public enum GameState {
    /** Lobby phase: players may join, waiting for enough players. */
    WAITING,
    /** Countdown is running; players may still join or leave. */
    STARTING,
    /** The game is in progress. Joining is closed. */
    RUNNING,
    /** The game has finished and is about to reset. Joining is closed. */
    ENDING;

    public boolean isJoinable() {
        return this == WAITING || this == STARTING;
    }

    public boolean isActive() {
        return this == RUNNING;
    }
}
