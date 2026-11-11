package me.sevj6.funCore.tycoon.core;

import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.tycoon.core.template.TemplateLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Templates for tests: the shipped sample, or YAML written in the test. */
public final class TestTemplates {

    private TestTemplates() {
    }

    public static TemplateLoader.Result loadYaml(String yaml) {
        return TemplateLoader.load(new Yaml().load(yaml));
    }

    public static Template valid(String yaml) {
        TemplateLoader.Result result = loadYaml(yaml);
        assertTrue(result.ok(), () -> "expected a valid template, got " + result.errors());
        return result.template();
    }

    /** The sample template shipped in the jar. */
    public static TemplateLoader.Result sample() {
        try (InputStream in = TestTemplates.class.getResourceAsStream("/tycoon/template.yml")) {
            assertNotNull(in, "tycoon/template.yml is missing from the resources");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return TemplateLoader.load(new Yaml().load(reader));
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A small valid template: one belt (3 waypoints in a straight line, 2 blocks a second) into a collector, and one
     * dropper ($1 every 20 ticks) onto its first waypoint. {@code extraComponents} is YAML added under components.
     */
    public static String simple(String extraComponents) {
        return """
                schema: 2
                size: [32, 16, 32]
                base:
                  spawn: [1, 1, 1]
                  collectors: { main: [10, 1, 0] }
                start-with: [belt, dropper]
                components:
                  belt:
                    type: conveyor
                    speed: 2
                    path: [[0, 1, 0], [4, 1, 0], [8, 1, 0]]
                    collector: main
                  dropper:
                    type: dropper
                    requires: [belt]
                    spout: [0, 3, 0]
                    interval: 20
                    value: 1
                    route: { belt: belt, waypoint: 0 }
                """ + extraComponents.stripIndent().indent(2);
    }
}
