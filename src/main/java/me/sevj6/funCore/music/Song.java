package me.sevj6.funCore.music;

import java.util.List;

/**
 * A song, ready to play: every note already turned into the sound, volume and pitch the client needs, grouped by song
 * tick. Made from an {@code .nbs} file by {@link me.sevj6.funCore.music.nbs.NbsLoader}; nothing here depends on
 * NoteBlockAPI, so the rest of the music system doesn't either.
 *
 * @param id             what commands and settings call it: the file name without {@code .nbs}, lower case
 * @param title          the title saved in the file, or the id if it has none
 * @param author         who wrote the song (may be empty)
 * @param ticksPerSecond the song's tempo, in its own ticks (Note Block Studio's "tempo")
 * @param ticks          the notes at each song tick; an empty list where nothing plays
 */
public record Song(String id, String title, String author, float ticksPerSecond, List<List<Note>> ticks) {

    /**
     * One note.
     *
     * @param sound  the sound's key, e.g. {@code minecraft:block.note_block.harp}
     * @param volume 0-1
     * @param pitch  0.5-2 (the note block range)
     * @param pan    -1 (left) to 1 (right); 0 is centred
     */
    public record Note(String sound, float volume, float pitch, float pan) {
    }

    public Song {
        ticks = List.copyOf(ticks);
    }

    /** How many song ticks long it is. */
    public int length() {
        return ticks.size();
    }

    public long durationSeconds() {
        return Math.round(length() / (double) ticksPerSecond);
    }

    /** "Title by Author", or just the title. */
    public String displayName() {
        return author.isBlank() ? title : title + " by " + author;
    }
}
