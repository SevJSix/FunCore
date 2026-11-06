package me.sevj6.funCore.npc.skin;

/** A skin lookup failed. The message is written to be shown to players. */
public final class SkinLookupException extends RuntimeException {

    public SkinLookupException(String message) {
        super(message);
    }

    public SkinLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
