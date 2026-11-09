package me.sevj6.funCore.music.nbs;

import com.xxmicloxx.NoteBlockAPI.model.CustomInstrument;
import com.xxmicloxx.NoteBlockAPI.model.Layer;
import com.xxmicloxx.NoteBlockAPI.model.Note;
import com.xxmicloxx.NoteBlockAPI.utils.InstrumentUtils;
import com.xxmicloxx.NoteBlockAPI.utils.NBSDecoder;
import com.xxmicloxx.NoteBlockAPI.utils.NoteUtils;
import me.sevj6.funCore.music.Song;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads {@code .nbs} files with NoteBlockAPI and turns them into {@link Song}s. The only class that touches
 * NoteBlockAPI, so it's only loaded (and only needs NoteBlockAPI installed) when songs are actually read.
 *
 * <p>Each note is worked out once, here: its instrument's sound, its volume (layer volume x note velocity), its pitch
 * (folded into the two octaves a note block can play, as NoteBlockAPI does) and its stereo panning.
 */
public final class NbsLoader {

    private NbsLoader() {
    }

    /** @throws IOException if the file can't be read or isn't a valid song */
    public static Song load(File file, String id) throws IOException {
        com.xxmicloxx.NoteBlockAPI.model.Song song;
        try {
            song = NBSDecoder.parse(file);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (song == null) throw new IOException("not a valid .nbs song");

        int length = song.getLength() + 1;
        List<List<Song.Note>> ticks = new ArrayList<>(length);
        for (int tick = 0; tick < length; tick++) ticks.add(new ArrayList<>());
        CustomInstrument[] custom = song.getCustomInstruments();
        for (Layer layer : song.getLayerHashMap().values()) {
            for (var entry : layer.getNotesAtTicks().entrySet()) {
                int tick = entry.getKey();
                Note note = entry.getValue();
                if (tick < 0 || tick >= length || note == null) continue;
                String sound = soundOf(note.getInstrument(), custom);
                if (sound == null) continue;
                float volume = (layer.getVolume() * note.getVelocity()) / 10_000f;
                if (volume <= 0) continue;
                // Both are 0-200 with 100 in the middle.
                float pan = Math.clamp(((layer.getPanning() - 100) + (note.getPanning() - 100)) / 100f, -1f, 1f);
                ticks.get(tick).add(new Song.Note(sound, Math.min(1f, volume), NoteUtils.getPitchTransposed(note), pan));
            }
        }
        String title = song.getTitle() == null || song.getTitle().isBlank() ? id : song.getTitle();
        String author = song.getOriginalAuthor() != null && !song.getOriginalAuthor().isBlank() ? song.getOriginalAuthor()
                : song.getAuthor() == null ? "" : song.getAuthor();
        return new Song(id, title, author, song.getSpeed(), ticks.stream().map(List::copyOf).toList());
    }

    /** The sound key an instrument plays, or {@code null} if it can't be played. */
    private static @Nullable String soundOf(byte instrument, @Nullable CustomInstrument[] custom) {
        if (InstrumentUtils.isCustomInstrument(instrument)) {
            int index = instrument - InstrumentUtils.getCustomInstrumentFirstIndex();
            if (custom == null || index < 0 || index >= custom.length || custom[index] == null) return null;
            Sound sound = custom[index].getSound();
            if (sound != null) return keyOf(sound);
            // A sound from a resource pack, named after its file.
            String file = custom[index].getSoundFileName();
            if (file == null || file.isBlank()) return null;
            return file.toLowerCase(Locale.ROOT).replace(".ogg", "").replace('\\', '/');
        }
        Sound sound = InstrumentUtils.getInstrument(instrument);
        return sound == null ? null : keyOf(sound);
    }

    private static @Nullable String keyOf(Sound sound) {
        NamespacedKey key = Registry.SOUNDS.getKey(sound);
        return key == null ? null : key.toString();
    }
}
