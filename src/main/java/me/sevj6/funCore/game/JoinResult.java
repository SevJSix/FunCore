package me.sevj6.funCore.game;

import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;

/** The outcome of {@link Minigame#join}. Each failure has a default message that can be shown to the player. */
public enum JoinResult {
    SUCCESS("<green>You joined the game."),
    ALREADY_IN_GAME("<red>You are already in a game."),
    IN_PROGRESS("<red>That game is already in progress."),
    FULL("<red>That game is full."),
    /** The game's {@link Minigame#canJoin} check rejected the player. */
    DENIED("<red>You can't join that game right now."),
    /** The game needs an arena and doesn't have one (or its world isn't loaded). */
    NO_ARENA("<red>That game doesn't have an arena set up yet."),
    /** Every arena of the game has a match going (or a full queue). */
    ALL_ARENAS_BUSY("<red>All arenas for that game are in use right now. Try again later."),
    /** The requested mode isn't enabled for the game (or the match is already playing another one). */
    MODE_NOT_AVAILABLE("<red>That mode isn't available for this game."),
    /** The player's state couldn't be saved, so they weren't let in (their inventory is untouched). */
    ERROR("<red>Something went wrong while joining. Please try again.");

    private final String message;

    JoinResult(String message) {
        this.message = message;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public Component message() {
        return Utils.mm(message);
    }
}
