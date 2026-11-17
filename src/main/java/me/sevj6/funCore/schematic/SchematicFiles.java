package me.sevj6.funCore.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The schematics in WorldEdit's schematics folder, by the names {@code //schem load} takes: the path inside the folder
 * with {@code /} between subfolders, without the extension (kept only when two files differ by nothing else, e.g.
 * {@code tower.schem} and {@code tower.schematic}). Sorted, case-insensitively.
 */
public final class SchematicFiles {

    /** The file types WorldEdit loads as schematics. */
    static final List<String> EXTENSIONS = List.of(".schem", ".schematic");
    /** How deep into subfolders to look. */
    private static final int MAX_DEPTH = 8;

    private SchematicFiles() {
    }

    /** The schematics in {@code folder}; none if it doesn't exist. */
    public static List<String> list(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        List<String> relative = new ArrayList<>();
        try (Stream<Path> files = Files.walk(folder, MAX_DEPTH)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String name = folder.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (extension(name) != null) relative.add(name);
            });
        }
        return names(relative);
    }

    /** The names for these files (paths inside the folder, with extensions). */
    static List<String> names(List<String> files) {
        Map<String, Integer> sameBase = new HashMap<>();
        for (String file : files) {
            String ext = extension(file);
            if (ext != null) sameBase.merge(base(file, ext).toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        List<String> names = new ArrayList<>();
        for (String file : files) {
            String ext = extension(file);
            if (ext == null) continue;
            String base = base(file, ext);
            names.add(sameBase.get(base.toLowerCase(Locale.ROOT)) > 1 ? file : base);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** The names starting with what's typed so far (any case), or containing it if none start with it. */
    public static List<String> matching(List<String> names, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> starting = names.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
        if (!starting.isEmpty() || lower.isEmpty()) return starting;
        return names.stream().filter(name -> name.toLowerCase(Locale.ROOT).contains(lower)).toList();
    }

    private static String base(String file, String ext) {
        return file.substring(0, file.length() - ext.length());
    }

    private static String extension(String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext) && lower.length() > ext.length()) return file.substring(file.length() - ext.length());
        }
        return null;
    }
}
