package me.sevj6.funCore.games.buildbattle;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The themes Build Battle picks from, kept in {@code plugins/FunCore/buildbattle/themes.yml} (a {@code themes:} list)
 * so admins can edit them without a restart: the file is read again at the start of every match. It's written with
 * the built-in list the first time.
 */
final class BuildBattleThemes {

    static final List<String> DEFAULTS = List.of(
            "House", "Castle", "Pirate Ship", "Treehouse", "Volcano", "Rocket", "Robot", "Dragon", "Lighthouse",
            "Windmill", "Igloo", "Haunted House", "Spaceship", "Submarine", "Train", "Hot Air Balloon", "Pyramid",
            "Temple", "Waterfall", "Island", "Bridge", "Skyscraper", "Farm", "Zoo", "Aquarium", "Circus",
            "Roller Coaster", "Ferris Wheel", "Campfire", "Snowman", "Gingerbread House", "Pumpkin Patch", "Ghost",
            "Cake", "Ice Cream", "Pizza", "Burger", "Sushi", "Fruit Bowl", "Coffee Cup", "Candy Shop", "Bakery",
            "Library", "School", "Fire Station", "Race Car", "Airplane", "Helicopter", "Tractor", "Bicycle", "Guitar",
            "Piano", "Computer", "Video Game", "Camera", "Television", "Clock", "Bedroom", "Kitchen", "Garden",
            "Flower", "Giant Mushroom", "Desert Oasis", "Jungle", "Coral Reef", "Shark", "Whale", "Octopus", "Turtle",
            "Frog", "Bee", "Butterfly", "Snail", "Cat", "Dog", "Horse", "Penguin", "Panda", "Owl", "Parrot",
            "Dinosaur", "Unicorn", "Mermaid", "Wizard Tower", "Knight", "Ninja", "Superhero", "Alien", "UFO",
            "Planet", "Moon Base", "Rainbow", "Tornado", "Fireworks", "Treasure Chest", "Crown", "Sword", "Potion",
            "Magic Portal", "Statue", "Fountain", "Throne", "Medieval Market", "Future City", "Space Station", "Beach",
            "Camping", "Amusement Park", "Fishing Trip", "Mine", "Stadium", "Chess", "Music Festival",
            "Birthday Party", "Graveyard", "Dungeon", "Ice Palace", "Steampunk", "Cyberpunk", "Shipwreck",
            "Sandcastle", "Snow Globe", "Hourglass", "Lava Lamp", "Bubble Tea", "Taco", "Popcorn", "Teapot");

    private BuildBattleThemes() {
    }

    /** The themes in the file (written with {@link #DEFAULTS} if it's missing), or the defaults if it has none. */
    static List<String> load(File dataFolder, Logger logger) {
        File file = new File(new File(dataFolder, "buildbattle"), "themes.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.isFile()) {
            yaml.options().setHeader(List.of(
                    "Build Battle themes: one is picked at random each match.",
                    "Edit the list freely; it's read again at the start of every match (no restart needed)."));
            yaml.set("themes", DEFAULTS);
            try {
                File parent = file.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("can't create " + parent);
                yaml.save(file);
            } catch (IOException e) {
                logger.warn("Could not write {}: {}", file, e.getMessage());
            }
            return DEFAULTS;
        }
        try {
            yaml.load(file);
        } catch (Exception e) {
            logger.warn("Could not read {} ({}); using the built-in themes", file, e.getMessage());
            return DEFAULTS;
        }
        List<String> themes = new ArrayList<>();
        for (String theme : yaml.getStringList("themes")) {
            if (theme != null && !theme.isBlank()) themes.add(theme.strip());
        }
        if (themes.isEmpty()) logger.warn("{} has no themes; using the built-in ones", file);
        return themes.isEmpty() ? DEFAULTS : themes;
    }

    /** A random theme, other than {@code last} when there's a choice. */
    static String pick(List<String> themes, @Nullable String last) {
        List<String> choices = new ArrayList<>(themes);
        if (choices.size() > 1 && last != null) choices.remove(last);
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }
}
