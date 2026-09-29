package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.capability.skill.SkillParser;
import de.augmentia.quad.core.capability.skill.SkillParseResult;
import de.augmentia.quad.core.capability.skill.TextSkill;
import de.augmentia.quad.core.session.AgentSessionState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoadToolTest {

    private Map<String, Skill> buildSkillMap(Path skillsDir) throws Exception {
        var parsed = SkillParser.fromDirectory(skillsDir);
        var map = new LinkedHashMap<String, Skill>();
        for (var p : parsed) {
            var skill = TextSkill.fromParseResult(p, skillsDir.resolve(p.skillId()));
            map.put(skill.name(), skill);
        }
        return map;
    }

    private Path createSkill(Path skillsDir, String name, String description, String body, String... extraYaml) throws Exception {
        Path skillDir = Files.createDirectories(skillsDir.resolve(name));
        StringBuilder yaml = new StringBuilder();
        yaml.append("---\n");
        yaml.append("name: ").append(name).append("\n");
        yaml.append("description: ").append(description).append("\n");
        for (String extra : extraYaml) {
            yaml.append(extra).append("\n");
        }
        yaml.append("---\n");
        yaml.append(body).append("\n");
        Files.writeString(skillDir.resolve("SKILL.md"), yaml.toString());
        return skillDir;
    }

    @Test
    void shouldLoadSkillByName() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-load");
        createSkill(skillsDir, "code-review", "Code review skill",
            "## Review-Prozess\n1. Struktur prüfen\n2. Fehler suchen\n3. Sicherheit prüfen",
            "allowed-tools:\n  - read_file\n  - grep_search");

        var skills = buildSkillMap(skillsDir);
        var tool = new SkillLoadTool(skills);
        var state = new AgentSessionState();

        var result = tool.execute("{\"skillName\": \"code-review\"}", state);

        assertTrue(result.text().contains("## Skill loaded: code-review"));
        assertTrue(result.text().contains("Code review skill"));
        assertTrue(result.text().contains("Review-Prozess"));
        assertTrue(result.text().contains("Struktur prüfen"));
        assertTrue(result.text().contains("Fehler suchen"));
        assertTrue(result.text().contains("read_file"));
    }

    @Test
    void shouldReturnErrorForUnknownSkill() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-load");
        createSkill(skillsDir, "known", "Known skill", "Body");
        var skills = buildSkillMap(skillsDir);
        var tool = new SkillLoadTool(skills);

        var result = tool.execute("{\"skillName\": \"unknown\"}", new AgentSessionState());

        assertTrue(result.text().contains("not found"));
        assertTrue(result.text().contains("known"));
    }

    @Test
    void shouldRequireSkillNameParameter() throws Exception {
        var tool = new SkillLoadTool(Map.of());
        var result = tool.execute("{}", new AgentSessionState());
        assertTrue(result.text().contains("required"));
    }

    @Test
    void shouldHandleNullArguments() throws Exception {
        var tool = new SkillLoadTool(Map.of());
        var result = tool.execute(null, new AgentSessionState());
        assertTrue(result.text().contains("required"));
    }

    @Test
    void shouldHandleInvalidJson() throws Exception {
        var tool = new SkillLoadTool(Map.of());
        var result = tool.execute("not json", new AgentSessionState());
        assertTrue(result.text().contains("Invalid JSON"));
    }

    @Test
    void shouldReturnCatalogForEmptySkills() throws Exception {
        var tool = new SkillLoadTool(Map.of());
        var result = tool.execute("{\"skillName\": \"anything\"}", new AgentSessionState());
        assertTrue(result.text().contains("not found"));
    }

    @Test
    void shouldIncludeDescriptionInLoadedSkill() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-load");
        createSkill(skillsDir, "docs", "Documentation skill",
            "## Writing\n1. Plan\n2. Write\n3. Review");

        var skills = buildSkillMap(skillsDir);
        var tool = new SkillLoadTool(skills);

        var result = tool.execute("{\"skillName\": \"docs\"}", new AgentSessionState());

        assertTrue(result.text().contains("Documentation skill"));
        assertTrue(result.text().contains("Writing"));
        assertTrue(result.text().contains("Plan"));
        assertTrue(result.text().contains("Write"));
    }

    @Test
    void shouldListAllSkillsWhenMultipleExist() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-load");
        createSkill(skillsDir, "alpha", "Alpha skill", "Body alpha");
        createSkill(skillsDir, "beta", "Beta skill", "Body beta");
        createSkill(skillsDir, "gamma", "Gamma skill", "Body gamma");

        var skills = buildSkillMap(skillsDir);
        var tool = new SkillLoadTool(skills);

        var result = tool.execute("{\"skillName\": \"beta\"}", new AgentSessionState());
        assertTrue(result.text().contains("## Skill loaded: beta"));
        assertTrue(result.text().contains("Body beta"));
    }

    @Test
    void shouldNotIncludeInstructionsWhenBlank() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-load");
        createSkill(skillsDir, "minimal", "Minimal skill", "");

        var skills = buildSkillMap(skillsDir);
        var tool = new SkillLoadTool(skills);

        var result = tool.execute("{\"skillName\": \"minimal\"}", new AgentSessionState());
        assertTrue(result.text().contains("## Skill loaded: minimal"));
        assertTrue(result.text().contains("Minimal skill"));
        assertFalse(result.text().contains("### Instructions"));
    }

    @Test
    void shouldIncludeLicenseIfPresent() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-load");
        createSkill(skillsDir, "licensed", "Licensed skill", "Body", "license: MIT");

        var skills = buildSkillMap(skillsDir);
        var tool = new SkillLoadTool(skills);

        var result = tool.execute("{\"skillName\": \"licensed\"}", new AgentSessionState());
        assertTrue(result.text().contains("License: MIT"));
    }
}