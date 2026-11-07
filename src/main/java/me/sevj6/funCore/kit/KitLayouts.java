package me.sevj6.funCore.kit;

import me.sevj6.funCore.util.Utils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Each player's chosen slots for one game's kit, saved in {@code plugins/FunCore/layouts/<game>.yml}:
 * <pre>
 * 069a79f4-44e9-4726-a5be-fca90e38aaf5:
 *   iron_sword: 0
 *   bow: 8
 * </pre>
 * Items a player hasn't moved use the kit's default slot.
 */
public final class KitLayouts {

    private final File file;
    private final Logger logger;
    private final Map<UUID, Map<String, Integer>> layouts = new HashMap<>();

    public KitLayouts(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    /** The player's layout (item id to slot); empty if they never saved one. */
    public Map<String, Integer> get(UUID player) {
        Map<String, Integer> layout = layouts.get(player);
        return layout == null ? Map.of() : Map.copyOf(layout);
    }

    public void set(UUID player, Map<String, Integer> layout) {
        if (layout.isEmpty()) layouts.remove(player);
        else layouts.put(player, new LinkedHashMap<>(layout));
        save();
    }

    public void reset(UUID player) {
        if (layouts.remove(player) != null) save();
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            logger.error("Could not read {}: {}", file, e.getMessage());
            return;
        }
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            try {
                UUID uuid = UUID.fromString(key);
                Map<String, Integer> layout = new LinkedHashMap<>();
                for (String id : section.getKeys(false)) layout.put(id, section.getInt(id));
                layouts.put(uuid, layout);
            } catch (IllegalArgumentException e) {
                logger.warn("{}: skipping '{}', which isn't a player UUID", file.getName(), key);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        layouts.forEach((uuid, layout) -> layout.forEach((id, slot) -> yaml.set(uuid + "." + id, slot)));
        File directory = file.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            logger.error("Could not create {}", directory);
            return;
        }
        File temp = new File(directory, file.getName() + ".tmp");
        try {
            yaml.save(temp);
            Utils.moveReplacing(temp, file);
        } catch (IOException e) {
            logger.error("Could not save {}", file, e);
        }
    }
}
