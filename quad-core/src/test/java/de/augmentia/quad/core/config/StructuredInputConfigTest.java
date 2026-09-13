package de.augmentia.quad.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructuredInputConfigTest {

    record Analysis(String complexity, List<String> recommendedTools, int estimatedHours) {}

    @Test
    void fromTemplateCreatesConfig() {
        var config = StructuredInputConfig.fromTemplate("Task: {{task}}");
        assertEquals("Task: {{task}}", config.template());
        assertNull(config.resourcePath());
        assertEquals("Task: {{task}}", config.effectiveTemplate());
    }

    @Test
    void fromTemplateRejectsBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> StructuredInputConfig.fromTemplate("   "));
    }

    @Test
    void fromResourceCreatesConfig() {
        var config = StructuredInputConfig.fromResource("prompts/test-prompt.txt");
        assertEquals("prompts/test-prompt.txt", config.resourcePath());
        String template = config.effectiveTemplate();
        assertTrue(template.contains("{{task}}"), "resource template must be loaded");
    }

    @Test
    void renderReplacesVariables() {
        var config = StructuredInputConfig.fromTemplate(
            "Analyze: {{task}} (Priority: {{priority}})");
        String rendered = config.render(Map.of("task", "Weather App", "priority", 1));
        assertEquals("Analyze: Weather App (Priority: 1)", rendered);
    }

    @Test
    void renderSerializesRecordsToJson() {
        var config = StructuredInputConfig.fromTemplate("Analyse: {{analysis}}");
        var analysis = new Analysis("complex", List.of("find", "grep"), 4);
        String rendered = config.render(Map.of("analysis", analysis));
        assertTrue(rendered.contains("\"complexity\":\"complex\""), rendered);
        assertTrue(rendered.contains("\"recommendedTools\":[\"find\",\"grep\"]"), rendered);
    }

    @Test
    void renderConvertsNullToEmptyString() {
        var config = StructuredInputConfig.fromTemplate("X={{missing}}Y");
        var values = new java.util.HashMap<String, Object>();
        values.put("missing", null);
        assertEquals("X=Y", config.render(values));
    }

    @Test
    void renderHandlesBooleansAndNumbers() {
        var config = StructuredInputConfig.fromTemplate("{{a}}-{{b}}");
        assertEquals("true-3.5", config.render(Map.of("a", true, "b", 3.5)));
    }

    @Test
    void renderMissingVariableThrows() {
        var config = StructuredInputConfig.fromTemplate("Task: {{task}}");
        assertThrows(IllegalArgumentException.class, () -> config.render(Map.of()));
    }
}