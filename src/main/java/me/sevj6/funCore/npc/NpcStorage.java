package me.sevj6.funCore.npc;

import me.sevj6.funCore.npc.skin.Skin;
import me.sevj6.funCore.util.Utils;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes NPC config files, one per NPC: {@code plugins/FunCore/npcs/<id>.yml}. The file name is the NPC's id.
 *
 * <pre>
 * name: "&lt;gold&gt;Guide"          # MiniMessage
 * name-visible: true
 * description: "&lt;gray&gt;Click me"  # optional line under the name
 * location: {world: world, x: 0.5, y: 64.0, z: 0.5, yaw: 90.0, pitch: 0.0}
 * look-at-players: true
 * look-range: 20.0
 * proximity-range: 5.0
 * skin:                          # optional; filled in by /npc edit &lt;id&gt; skin &lt;player&gt;
 *   owner-name: Notch
 *   owner-id: 069a79f4-44e9-4726-a5be-fca90e38aaf5
 *   value: ...
 *   signature: ...
 * </pre>
 */
final class NpcStorage {

    private final File directory;

    NpcStorage(File directory) {
        this.directory = directory;
    }

    File directory() {
        return directory;
    }

    File file(String id) {
        return new File(directory, id + ".yml");
    }

    /** Ids of every NPC file on disk. */
    List<String> listIds() {
        List<String> ids = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return ids;
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - ".yml".length());
            if (NpcManager.isValidId(id)) ids.add(id);
        }
        ids.sort(null);
        return ids;
    }

    void save(Npc npc) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", npc.getName());
        yaml.set("name-visible", npc.isNameVisible());
        yaml.set("description", npc.getDescription());
        Utils.writeLocation(yaml, "location", npc.getLocation());
        yaml.set("look-at-players", npc.isLookAtPlayers());
        yaml.set("look-range", npc.getLookRange());
        yaml.set("proximity-range", npc.getProximityRange());
        Skin skin = npc.getSkin();
        if (skin != null) skin.write(yaml.createSection("skin"));

        File target = file(npc.getId());
        File temp = new File(directory, target.getName() + ".tmp");
        yaml.save(temp);
        Utils.moveReplacing(temp, target);
    }

    /** Settings read from a file, before they become an {@link Npc}. */
    record Data(String name, boolean nameVisible, @Nullable String description, Location location,
                boolean lookAtPlayers, double lookRange, double proximityRange, @Nullable Skin skin) {

        void applyTo(Npc npc) {
            npc.setNameVisible(nameVisible);
            npc.setDescription(description);
            npc.setLookAtPlayers(lookAtPlayers);
            npc.setLookRange(lookRange);
            npc.setProximityRange(proximityRange);
            npc.setSkin(skin);
        }
    }

    /**
     * @throws IOException if the file can't be read, or its world isn't loaded (in which case the file is left
     *                     alone so nothing is lost)
     */
    Data load(String id) throws IOException {
        File file = file(id);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            throw new IOException("Could not read " + file.getName() + ": " + e.getMessage(), e);
        }

        Location location = Utils.readLocation(yaml, "location");
        if (location == null) {
            ConfigurationSection section = yaml.getConfigurationSection("location");
            String world = section == null ? null : section.getString("world");
            throw new IOException(world == null
                    ? file.getName() + " has no location"
                    : file.getName() + " is in world '" + world + "', which isn't loaded");
        }

        return new Data(
                yaml.getString("name", id),
                yaml.getBoolean("name-visible", true),
                yaml.getString("description"),
                location,
                yaml.getBoolean("look-at-players", true),
                yaml.getDouble("look-range", Npc.DEFAULT_LOOK_RANGE),
                yaml.getDouble("proximity-range", Npc.DEFAULT_PROXIMITY_RANGE),
                Skin.read(yaml.getConfigurationSection("skin"))
        );
    }

    boolean delete(String id) {
        File file = file(id);
        return !file.exists() || file.delete();
    }
}
