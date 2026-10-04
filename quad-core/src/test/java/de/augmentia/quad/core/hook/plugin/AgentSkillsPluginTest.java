package de.augmentia.quad.core.hook.plugin;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.capability.skill.SkillParser;
import de.augmentia.quad.core.capability.skill.TextSkill;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentSkillsPluginTest {

    private Path createSkill(Path skillsDir, String name, String description, String body, String... extraYaml) throws Exception {
        Path skillDir = Files.createDirectories(skillsDir.resolve(name));
        StringBuilder yaml = new StringBuilder();
        yaml.append("---\n");
        yaml.append("name: ").append(name).append("\n");
        yaml.append("description: ").append(description).append("\n");
        for (String extra : extraYaml) yaml.append(extra).append("\n");
        yaml.append("---\n").append(body).append("\n");
        Files.writeString(skillDir.resolve("SKILL.md"), yaml.toString());
        return skillDir;
    }

    private List<Skill> loadSkills(Path skillsDir) throws Exception {
        var parsed = SkillParser.fromDirectory(skillsDir);
        var list = new java.util.ArrayList<Skill>();
        for (var p : parsed) list.add(TextSkill.fromParseResult(p, skillsDir.resolve(p.skillId())));
        return list;
    }

    @Test
    void shouldInjectOnlyCatalogNotFullInstructions() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-catalog");
        createSkill(skillsDir, "code-review", "Code review skill",
            "## Review process\n1. Check structure\n2. Find errors\n3. Check security",
            "allowed-tools:\n  - read_file\n  - grep_search");

        var skills = loadSkills(skillsDir);
        var plugin = new AgentSkillsPlugin(skills, List.of(), false);

        var additional = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
        var ctx = new HookContexts.BeforeModelCallContext(
            "session-1", new StringBuilder(), new java.util.ArrayList<>(),
            new java.util.ArrayList<>(), additional);

        var result = plugin.beforeModelCall(ctx);
        assertSame(HookResult.Continue.INSTANCE, result);
        assertEquals(1, additional.size());
        assertTrue(additional.get(0) instanceof SystemMessage);
        String catalog = ((SystemMessage) additional.get(0)).text();

        // Phase 1: Catalog contains Name + Description
        assertTrue(catalog.contains("## Available Skills"));
        assertTrue(catalog.contains("code-review"));
        assertTrue(catalog.contains("Code review skill"));

        // Phase 1: Catalog must NOT contain full instructions
        assertFalse(catalog.contains("Review process"), "Catalog must not contain full instructions");
        assertFalse(catalog.contains("Check structure"), "Catalog must not contain body content");
        assertFalse(catalog.contains("read_file"), "Catalog must not contain allowed tools");
    }

    @Test
    void shouldNotInjectWhenNoSkills() throws Exception {
        var plugin = new AgentSkillsPlugin(List.of(), List.of(), false);
        var additional = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
        var ctx = new HookContexts.BeforeModelCallContext(
            "session-1", new StringBuilder(), new java.util.ArrayList<>(),
            new java.util.ArrayList<>(), additional);

        var result = plugin.beforeModelCall(ctx);
        assertSame(HookResult.Continue.INSTANCE, result);
        assertTrue(additional.isEmpty());
    }

    @Test
    void shouldDeduplicateCatalogInjection() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-dedup");
        createSkill(skillsDir, "test", "Test skill", "Body");
        var skills = loadSkills(skillsDir);
        var plugin = new AgentSkillsPlugin(skills, List.of(), false);

        var additional = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
        var ctx = new HookContexts.BeforeModelCallContext(
            "session-1", new StringBuilder(), new java.util.ArrayList<>(),
            new java.util.ArrayList<>(), additional);

        plugin.beforeModelCall(ctx);
        int firstSize = additional.size();
        plugin.beforeModelCall(ctx);
        assertEquals(firstSize, additional.size(), "Catalog should not be re-injected");
    }

    @Test
    void shouldIncludeLoadSkillHint() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-hint");
        createSkill(skillsDir, "pdf", "PDF analysis", "Extract text from PDFs");
        var skills = loadSkills(skillsDir);
        var plugin = new AgentSkillsPlugin(skills, List.of(), false);

        var additional = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
        var ctx = new HookContexts.BeforeModelCallContext(
            "session-1", new StringBuilder(), new java.util.ArrayList<>(),
            new java.util.ArrayList<>(), additional);

        plugin.beforeModelCall(ctx);
        String catalog = ((SystemMessage) additional.get(0)).text();
        assertTrue(catalog.contains("load_skill"), "Catalog must point to load_skill tool");
    }

    @Test
    void shouldRespectMaxThreeInitialSkills() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-init");
        createSkill(skillsDir, "a", "A", "Body a");
        createSkill(skillsDir, "b", "B", "Body b");
        createSkill(skillsDir, "c", "C", "Body c");
        createSkill(skillsDir, "d", "D", "Body d");
        var skills = loadSkills(skillsDir);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> new AgentSkillsPlugin(skills, List.of("a", "b", "c", "d"), false));
        assertTrue(ex.getMessage().contains("max 3"));
    }

    @Test
    void shouldProvideSkillsAccessor() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-accessor");
        createSkill(skillsDir, "x", "X skill", "Body x");
        var skills = loadSkills(skillsDir);
        var plugin = new AgentSkillsPlugin(skills, List.of(), false);

        var skillsMap = plugin.skills();
        assertEquals(1, skillsMap.size());
        assertTrue(skillsMap.containsKey("x"));
    }
}