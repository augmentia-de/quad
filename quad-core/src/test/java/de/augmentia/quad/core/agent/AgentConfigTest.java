package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.config.StructuredOutputConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {

    @Test
    void builderPopulatesFields() {
        AgentConfig cfg = AgentConfig.builder()
            .modelFromEnv()
            .systemPrompt("sys")
            .userMessageTemplate("tpl")
            .cachePrefix("prefix")
            .temperature(0.3)
            .maxOutputTokens(256)
            .maxReActIterations(5)
            .contextMaxTokens(1200)
            .allowedTools(Set.of("a", "b"))
            .skillDir(Path.of("skills"))
            .initialSkills(List.of("code-review"))
            .skillSearch(true)
            .workspace(Path.of("/tmp/ws"))
            .structuredOutput(StructuredOutputConfig.dynamicSchema("{}"))
            .build();

        assertTrue(cfg.modelFromEnv());
        assertEquals("sys", cfg.systemPrompt());
        assertEquals("tpl", cfg.userMessageTemplate());
        assertEquals("prefix", cfg.cachePrefix());
        assertEquals(0.3, cfg.temperature().doubleValue());
        assertEquals(256, cfg.maxOutputTokens().intValue());
        assertEquals(5, cfg.maxReActIterations().intValue());
        assertEquals(1200, cfg.contextMaxTokens().intValue());
        assertEquals(Set.of("a", "b"), cfg.allowedTools());
        assertEquals(List.of(Path.of("skills")), cfg.skillDirs());
        assertEquals(List.of("code-review"), cfg.initialSkills());
        assertTrue(cfg.skillSearchEnabled());
        assertEquals(Path.of("/tmp/ws"), cfg.workspace());
        assertNotNull(cfg.structuredOutput());
    }

    @Test
    void fromJsonParses() {
        AgentConfig cfg = AgentConfig.fromJson("""
            { "modelFromEnv": true,
              "systemPrompt": "from-json",
              "maxReActIterations": 7,
              "allowedTools": ["read_file", "write_file"],
              "skillDirs": ["skills"],
              "initialSkills": ["code-review"],
              "skillSearchEnabled": true,
              "structuredOutputSchema": "{}" }
            """);

        assertTrue(cfg.modelFromEnv());
        assertEquals("from-json", cfg.systemPrompt());
        assertEquals(7, cfg.maxReActIterations().intValue());
        assertEquals(Set.of("read_file", "write_file"), cfg.allowedTools());
        assertEquals(List.of(Path.of("skills")), cfg.skillDirs());
        assertEquals(List.of("code-review"), cfg.initialSkills());
        assertTrue(cfg.skillSearchEnabled());
        assertNotNull(cfg.structuredOutput());
    }

    @Test
    void fromJsonBlankIsEmptyConfig() {
        AgentConfig cfg = AgentConfig.fromJson("  ");
        assertNull(cfg.model());
        assertFalse(cfg.modelFromEnv());
        assertTrue(cfg.allowedTools().isEmpty());
    }

    @Test
    void toChatParametersNullWhenUnset() {
        assertNull(AgentConfig.builder().build().toChatParameters());
    }

    @Test
    void toChatParametersFromTemperature() {
        var params = AgentConfig.builder().temperature(0.5).maxOutputTokens(100).build().toChatParameters();
        assertNotNull(params);
        assertEquals(0.5, params.temperature().doubleValue());
        assertEquals(100, params.maxOutputTokens().intValue());
    }
}
