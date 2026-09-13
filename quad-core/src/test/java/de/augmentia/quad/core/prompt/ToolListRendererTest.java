package de.augmentia.quad.core.prompt;

import de.augmentia.quad.core.capability.prompt.ToolListRenderer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolListRendererTest {

    private ToolListRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new ToolListRenderer();
    }

    @Test
    void shouldRenderEmptyToolList() {
        String result = renderer.render(List.of());

        assertEquals("No tools available", result);
    }

    @Test
    void shouldRenderNullToolList() {
        String result = renderer.render(null);

        assertEquals("No tools available", result);
    }

    @Test
    void shouldRenderSingleTool() {
        ToolSpecification tool = ToolSpecification.builder()
            .name("readFile")
            .description("Reads a file from disk")
            .parameters(JsonObjectSchema.builder()
                .addStringProperty("path", "File path")
                .required("path")
                .build())
            .build();

        String result = renderer.render(List.of(tool));

        assertTrue(result.contains("- readFile"));
        assertTrue(result.contains("Reads a file from disk"));
        assertTrue(result.contains("Parameter:"));
        assertTrue(result.contains("path"));
    }

    @Test
    void shouldRenderMultipleTools() {
        ToolSpecification fileReader = ToolSpecification.builder()
            .name("readFile")
            .description("Reads a file")
            .build();

        ToolSpecification fileWriter = ToolSpecification.builder()
            .name("writeFile")
            .description("Writes to a file")
            .build();

        String result = renderer.render(List.of(fileReader, fileWriter));

        assertTrue(result.contains("- readFile"));
        assertTrue(result.contains("- writeFile"));
        assertTrue(result.contains("Reads a file"));
        assertTrue(result.contains("Writes to a file"));
    }

    @Test
    void shouldReplaceToolPlaceholder() {
        String template = "Available tools: {tools}";
        ToolSpecification tool = ToolSpecification.builder()
            .name("search")
            .description("Web search")
            .build();

        String result = renderer.renderWithTools(template, List.of(tool));

        assertTrue(result.contains("Available tools:"));
        assertFalse(result.contains("{tools}"));
        assertTrue(result.contains("search"));
    }

    @Test
    void shouldHandleTemplateWithoutPlaceholder() {
        String template = "No tools section";
        ToolSpecification tool = ToolSpecification.builder()
            .name("search")
            .description("Search tool")
            .build();

        String result = renderer.renderWithTools(template, List.of(tool));

        assertEquals("No tools section", result);
    }

    @Test
    void shouldRenderComplexTool() {
        JsonObjectSchema params = JsonObjectSchema.builder()
            .addStringProperty("sessionId", "The session ID")
            .addIntegerProperty("limit", "Maximum results")
            .required("sessionId")
            .build();

        ToolSpecification complexTool = ToolSpecification.builder()
            .name("gdpr_export")
            .description("Exports GDPR data")
            .parameters(params)
            .build();

        String result = renderer.render(List.of(complexTool));

        assertTrue(result.contains("gdpr_export"));
        assertTrue(result.contains("Exports GDPR data"));
    }
}