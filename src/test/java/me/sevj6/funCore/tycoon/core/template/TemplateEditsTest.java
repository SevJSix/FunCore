package me.sevj6.funCore.tycoon.core.template;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TemplateEditsTest {

    @Test
    void editsAreLaidOverTheTemplate() {
        Map<String, Object> template = Map.of(
                "base", Map.of("spawn", List.of(20, 1, 40), "rebirth-pad", List.of(34, 1, 40)),
                "components", Map.of("a", Map.of("price", 5, "pad", List.of(1, 1, 1)), "b", Map.of("price", 7)));
        Map<String, Object> edits = new LinkedHashMap<>();
        TemplateEdits.set(edits, List.of("base", "spawn"), List.of(21, 1, 40));
        TemplateEdits.set(edits, List.of("components", "a", "pad"), List.of(2, 1, 2));
        TemplateEdits.set(edits, List.of("components", "c"), Map.of("price", 9));
        Map<String, Object> deleteB = new LinkedHashMap<>();
        deleteB.put("b", null);
        ((Map<String, Object>) edits.get("components")).putAll(deleteB);

        Object merged = TemplateEdits.merge(template, edits);
        assertEquals(List.of(21, 1, 40), TemplateEdits.get(merged, List.of("base", "spawn")), "edited");
        assertEquals(List.of(34, 1, 40), TemplateEdits.get(merged, List.of("base", "rebirth-pad")), "kept");
        assertEquals(5, TemplateEdits.get(merged, List.of("components", "a", "price")), "kept beside an edit");
        assertEquals(List.of(2, 1, 2), TemplateEdits.get(merged, List.of("components", "a", "pad")));
        assertEquals(9, TemplateEdits.get(merged, List.of("components", "c", "price")), "added");
        assertNull(TemplateEdits.get(merged, List.of("components", "b")), "removed with ~");
        assertFalse(((Map<?, ?>) TemplateEdits.get(template, List.of("components"))).containsKey("c"), "the template itself is untouched");
    }

    @Test
    void listsAreReplacedNotMerged() {
        Object merged = TemplateEdits.merge(Map.of("path", List.of(1, 2, 3)), Map.of("path", List.of(9)));
        assertEquals(List.of(9), TemplateEdits.get(merged, List.of("path")));
    }
}
