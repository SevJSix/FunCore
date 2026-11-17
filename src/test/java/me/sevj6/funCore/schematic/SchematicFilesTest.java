package me.sevj6.funCore.schematic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchematicFilesTest {

    @Test
    void listsSchematicsByTheNamesWorldEditTakes(@TempDir Path folder) throws IOException {
        Files.createFile(folder.resolve("neon_arcade_25x25.schem"));
        Files.createFile(folder.resolve("Clutch_Tower.schem"));
        Files.createFile(folder.resolve("readme.txt"));
        Files.createFile(folder.resolve(".schem"));
        Files.createDirectories(folder.resolve("lobbies/old"));
        Files.createFile(folder.resolve("lobbies/old/spawn.schematic"));
        assertEquals(List.of("Clutch_Tower", "lobbies/old/spawn", "neon_arcade_25x25"), SchematicFiles.list(folder));
    }

    @Test
    void noFolderMeansNone(@TempDir Path folder) throws IOException {
        assertEquals(List.of(), SchematicFiles.list(folder.resolve("missing")));
    }

    @Test
    void keepsTheExtensionOnlyWhenTwoFilesShareAName() {
        assertEquals(List.of("arena", "tower.schem", "tower.SCHEMATIC"),
                SchematicFiles.names(List.of("tower.schem", "tower.SCHEMATIC", "arena.schem")));
    }

    @Test
    void matchesWhatsTyped() {
        List<String> names = List.of("candy_carnival_25x25", "crystal_caverns_50x50", "lobbies/spawn", "neon_arcade_25x25");
        assertEquals(List.of("candy_carnival_25x25", "crystal_caverns_50x50"), SchematicFiles.matching(names, "C"));
        assertEquals(List.of("lobbies/spawn"), SchematicFiles.matching(names, "lobbies/"));
        assertEquals(List.of("candy_carnival_25x25", "neon_arcade_25x25"), SchematicFiles.matching(names, "25x25"),
                "nothing starts with it, so anything containing it");
        assertEquals(names, SchematicFiles.matching(names, ""));
    }
}
