package me.sevj6.funCore.tycoon.core.template;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits made with the builder tools, kept in {@code template-edits.yml} next to the template and laid over it when it
 * loads. The template file stays as written (and keeps getting new samples); the edits file holds only what was
 * changed, so it's easy to review or undo.
 *
 * <p>Laying over: maps are merged key by key, anything else (a value, a list) replaces what the template has, and
 * {@code null} ({@code ~} in YAML) removes the key.
 */
public final class TemplateEdits {

    private TemplateEdits() {
    }

    /** {@code edits} laid over {@code base} (neither is changed). */
    public static @Nullable Object merge(@Nullable Object base, @Nullable Object edits) {
        if (edits == null) return copy(base);
        if (!(base instanceof Map<?, ?> baseMap) || !(edits instanceof Map<?, ?> editMap)) return copy(edits);
        Map<String, Object> merged = new LinkedHashMap<>();
        baseMap.forEach((key, value) -> merged.put(String.valueOf(key), copy(value)));
        editMap.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (value == null) merged.remove(name);
            else merged.put(name, merge(merged.get(name), value));
        });
        return merged;
    }

    /** A deep copy of plain YAML values (maps and lists copied, everything else shared). */
    public static @Nullable Object copy(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), copy(entry)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object entry : list) copy.add(copy(entry));
            return copy;
        }
        return value;
    }

    /** The value at {@code path} (map keys), or {@code null}. */
    public static @Nullable Object get(@Nullable Object root, List<String> path) {
        Object at = root;
        for (String key : path) {
            if (!(at instanceof Map<?, ?> map)) return null;
            at = map.get(key);
        }
        return at;
    }

    /** Sets {@code path} in {@code root} (a map), making maps along the way. {@code null} removes the key. */
    @SuppressWarnings("unchecked")
    public static void set(Map<String, Object> root, List<String> path, @Nullable Object value) {
        Map<String, Object> at = root;
        for (int i = 0; i < path.size() - 1; i++) {
            Object next = at.get(path.get(i));
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                at.put(path.get(i), next);
            }
            at = (Map<String, Object>) next;
        }
        if (value == null) at.remove(path.getLast());
        else at.put(path.getLast(), value);
    }

    /**
     * Takes the edit at {@code path} out of {@code root} (so the template's own value shows again), and any maps left
     * empty above it.
     *
     * @return whether there was an edit there
     */
    @SuppressWarnings("unchecked")
    public static boolean remove(Map<String, Object> root, List<String> path) {
        if (path.isEmpty()) return false;
        if (path.size() == 1) {
            if (!root.containsKey(path.getFirst())) return false;
            root.remove(path.getFirst());
            return true;
        }
        if (!(root.get(path.getFirst()) instanceof Map<?, ?> child)) return false;
        boolean removed = remove((Map<String, Object>) child, path.subList(1, path.size()));
        if (removed && child.isEmpty()) root.remove(path.getFirst());
        return removed;
    }

    /**
     * Every edit in {@code root}, as dotted paths: {@code base.spawn}, {@code components.test1}. A whole new component
     * is one path; changes to fields of a component the template has are one path each.
     *
     * @param template the template without edits, to tell new things from changed ones
     */
    public static List<String> paths(Map<String, Object> root, @Nullable Object template) {
        List<String> paths = new ArrayList<>();
        collect(root, template, "", paths);
        return paths;
    }

    private static void collect(Map<?, ?> edits, @Nullable Object template, String prefix, List<String> paths) {
        edits.forEach((key, value) -> {
            String path = prefix + key;
            Object base = template instanceof Map<?, ?> map ? map.get(String.valueOf(key)) : null;
            if (value instanceof Map<?, ?> child && !child.isEmpty() && base instanceof Map<?, ?>) collect(child, base, path + ".", paths);
            else paths.add(path);
        });
    }

    /** {@code [x, y, z]}, as the template writes a position. */
    public static List<Integer> position(Offset at) {
        return List.of(at.x(), at.y(), at.z());
    }
}
