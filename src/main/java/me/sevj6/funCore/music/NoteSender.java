package me.sevj6.funCore.music;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Sends one song tick's notes to a player. Everything for a tick goes out together (see
 * {@link me.sevj6.funCore.music.nms.NmsNoteSender}), so a chord can't be split across client frames.
 */
public interface NoteSender {

    /**
     * Notes the player hears "in their head", wherever they go (a soundtrack). Panned notes come from their left or
     * right.
     *
     * @param volume multiplies each note's volume (0-1)
     */
    void sendToSelf(Player player, List<Song.Note> notes, float volume, SoundCategory category);

    /**
     * Notes coming from a point (a jukebox, an NPC). They fade with distance like any sound; a volume above 1 makes
     * them carry further, not louder.
     */
    void sendFrom(Player player, Location source, List<Song.Note> notes, float volume, SoundCategory category);
}
