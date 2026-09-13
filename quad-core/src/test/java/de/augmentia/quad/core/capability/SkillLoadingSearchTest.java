package de.augmentia.quad.core.capability;

import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.capability.skill.SkillParser;
import de.augmentia.quad.core.capability.skill.SkillParseResult;
import de.augmentia.quad.core.capability.skill.TextSkill;
import de.augmentia.quad.core.tool.ToolResult;
import de.augmentia.quad.core.tool.SkillSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoadingSearchTest {

    @TempDir
    Path tempDir;

    private Path skillsDir;

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
    }

    // ─── Helpers ───────────────────────────────────────────────

    private Path createSkill(String name, String nameVal, String description,
                              String body, String... extraYaml) throws IOException {
        Path skillDir = Files.createDirectories(skillsDir.resolve(name));
        StringBuilder yaml = new StringBuilder();
        yaml.append("---\n");
        yaml.append("name: ").append(nameVal).append("\n");
        yaml.append("description: ").append(description).append("\n");
        for (String extra : extraYaml) {
            yaml.append(extra).append("\n");
        }
        yaml.append("---\n");
        yaml.append(body).append("\n");
        Files.writeString(skillDir.resolve("SKILL.md"), yaml.toString());
        return skillDir;
    }

    // ─── SkillParser Tests ─────────────────────────────────────

    @Test
    void shouldParseSkillFromDirectory() throws IOException {
        createSkill("ki-research", "KI Research",
"Research on AI topics",
            "Use websearch for AI research.",
            "allowed-tools:\n  - websearch\n  - read_file",
            "declared-tools:\n  - research_agent",
            "metadata:\n  author: test\n  version: \"1.0\"",
            "license: MIT",
            "compatibility: quad-core >= 2.0");

        List<SkillParseResult> results = SkillParser.fromDirectory(skillsDir);

        assertEquals(1, results.size());
        SkillParseResult skill = results.get(0);
        assertEquals("ki-research", skill.skillId());
        assertEquals("KI Research", skill.name());
        assertEquals("Research on AI topics", skill.description());
        assertTrue(skill.body().contains("websearch"));
        assertEquals(List.of("websearch", "read_file"), skill.allowedTools());
        assertEquals(List.of("research_agent"), skill.declaredTools());
        assertEquals("test", skill.metadata().get("author"));
        assertEquals("1.0", skill.metadata().get("version"));
        assertEquals("MIT", skill.license());
        assertEquals("quad-core >= 2.0", skill.compatibility());
    }

    @Test
    void shouldParseMultipleSkills() throws IOException {
        createSkill("alpha", "Alpha", "First skill", "Body alpha");
        createSkill("beta", "Beta", "Second skill", "Body beta");
        createSkill("gamma", "Gamma", "Third skill", "Body gamma");

        List<SkillParseResult> results = SkillParser.fromDirectory(skillsDir);

        assertEquals(3, results.size());
        var names = results.stream().map(SkillParseResult::name).toList();
        assertTrue(names.contains("Alpha"));
        assertTrue(names.contains("Beta"));
        assertTrue(names.contains("Gamma"));
    }

    @Test
    void shouldSkipDirectoriesWithoutSkillMd() throws IOException {
        createSkill("valid", "Valid", "Has SKILL.md", "Body");
        Files.createDirectories(skillsDir.resolve("no-skill-file"));

        List<SkillParseResult> results = SkillParser.fromDirectory(skillsDir);

        assertEquals(1, results.size());
        assertEquals("valid", results.get(0).skillId());
    }

    @Test
    void shouldReturnEmptyForNonExistentDir() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        List<SkillParseResult> results = SkillParser.fromDirectory(nonExistent);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyDir() {
        List<SkillParseResult> results = SkillParser.fromDirectory(skillsDir);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldNormalizeSkillId() throws IOException {
        // Directory name "My Skill" → skillId "my-skill"
        createSkill("My Skill", "My Skill", "Test", "Body");

        List<SkillParseResult> results = SkillParser.fromDirectory(skillsDir);

        assertEquals(1, results.size());
        assertEquals("my-skill", results.get(0).skillId());
    }

    @Test
    void shouldFallbackToDirectoryNameAsName() throws IOException {
        Path skillDir = Files.createDirectories(skillsDir.resolve("fallback-skill"));
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\ndescription: No name field\n---\nBody content\n");

        List<SkillParseResult> results = SkillParser.fromDirectory(skillsDir);

        assertEquals(1, results.size());
        assertEquals("fallback-skill", results.get(0).name());
    }

    @Test
    void shouldHandleMinimalFrontmatter() throws IOException {
        Path skillDir = Files.createDirectories(skillsDir.resolve("minimal"));
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\ndescription: Minimal skill\n---\nJust body.\n");

        List<SkillParseResult> results = SkillParser.fromDirectory(skillsDir);

        assertEquals(1, results.size());
        SkillParseResult skill = results.get(0);
        assertEquals("minimal", skill.skillId());
        assertEquals("minimal", skill.name()); // fallback to dir name
        assertEquals("Minimal skill", skill.description());
        assertTrue(skill.body().contains("Just body."));
        assertTrue(skill.allowedTools().isEmpty());
        assertTrue(skill.declaredTools().isEmpty());
        assertTrue(skill.metadata().isEmpty());
        assertNull(skill.license());
        assertNull(skill.compatibility());
    }

    @Test
    void shouldHandleLowercaseSkillMd() throws IOException {
        Path skillDir = Files.createDirectories(skillsDir.resolve("lower"));
        Files.writeString(skillDir.resolve("skill.md"),
            "---\ndescription: Lowercase skill.md\n---\nBody.\n");

        List<SkillParseResult> results = SkillParser.fromDirectory(skillsDir);

        assertEquals(1, results.size());
        assertEquals("lower", results.get(0).skillId());
    }

    // ─── TextSkill fromParseResult Tests ───────────────────────

    @Test
    void shouldCreateTextSkillFromParseResult() throws IOException {
        createSkill("test-skill", "Test Skill", "A test skill", "Do testing stuff.",
            "allowed-tools:\n  - websearch");

        List<SkillParseResult> results = SkillParser.fromDirectory(skillsDir);
        SkillParseResult parsed = results.get(0);
        Skill skill = TextSkill.fromParseResult(parsed, skillsDir.resolve(parsed.skillId()));

        assertEquals("Test Skill", skill.name());
        assertEquals("A test skill", skill.description());
        assertEquals(List.of("websearch"), skill.allowedTools());
        assertNotNull(skill.instructions());
        assertTrue(skill.instructions().contains("testing stuff"));
    }

    // ─── CapabilityRegistry Tests ──────────────────────────────

    @Test
    void shouldDiscoverSkillsFromExplicitDir() throws IOException {
        createSkill("alpha", "Alpha", "First", "Body alpha");
        createSkill("beta", "Beta", "Second", "Body beta");

        CapabilityRegistry registry = CapabilityRegistry.builder()
            .skillDir(skillsDir)
            .build();

        List<CapabilityRegistry.Capability> caps = registry.discoverSkills();
        assertEquals(2, caps.size());

        var names = caps.stream().map(CapabilityRegistry.Capability::name).toList();
        assertTrue(names.contains("Alpha"));
        assertTrue(names.contains("Beta"));
        caps.forEach(c -> assertEquals(CapabilityRegistry.CapabilityType.SKILL, c.type()));
    }

    @Test
    void shouldResolveRelativePath() throws IOException {
        createSkill("test", "Test", "Desc", "Body");

        // Create a sub-directory and use relative path from there
        Path subDir = tempDir.resolve("sub");
        Files.createDirectories(subDir);

        // Use relative path "skills" from subDir — should NOT find skills
        // (relative to CWD, not to subDir)
        CapabilityRegistry registry = CapabilityRegistry.builder()
            .skillDir(Path.of("skills"))
            .build();

        // discoverSkills should either find via CWD traversal or return empty
        // depending on CWD — just verify no exception
        assertDoesNotThrow(registry::discoverSkills);
    }

    @Test
    void shouldNotFailForInvalidDir() {
        // Invalid dir is ignored; CWD traversal may or may not find skills
        CapabilityRegistry registry = CapabilityRegistry.builder()
            .skillDir(Path.of("/nonexistent/path"))
            .build();

        assertDoesNotThrow(registry::discoverSkills);
    }

    @Test
    void shouldDiscoverAllSkillsViaDiscoverAll() throws IOException {
        createSkill("s1", "Skill1", "First", "Body1");
        createSkill("s2", "Skill2", "Second", "Body2");

        CapabilityRegistry registry = CapabilityRegistry.builder()
            .skillDir(skillsDir)
            .build();

        List<CapabilityRegistry.Capability> all = registry.discoverAll();
        assertTrue(all.size() >= 2);

        var skillCaps = all.stream()
            .filter(c -> c.type() == CapabilityRegistry.CapabilityType.SKILL)
            .toList();
        assertEquals(2, skillCaps.size());
    }

    @Test
    void shouldGetSkillByName() throws IOException {
        createSkill("lookup", "Lookup Skill", "Find me", "Instructions here.");

        CapabilityRegistry registry = CapabilityRegistry.builder()
            .skillDir(skillsDir)
            .build();

        Skill found = registry.getSkill("Lookup Skill");
        assertNotNull(found);
        assertEquals("Lookup Skill", found.name());
    }

    @Test
    void shouldReturnNullForUnknownSkill() {
        CapabilityRegistry registry = CapabilityRegistry.builder()
            .skillDir(skillsDir)
            .build();

        Skill found = registry.getSkill("nonexistent");
        assertNull(found);
    }

    @Test
    void shouldDiscoverAllSkillsAsSkillObjects() throws IOException {
        createSkill("obj-skill", "Object Skill", "Test", "Body");

        CapabilityRegistry registry = CapabilityRegistry.builder()
            .skillDir(skillsDir)
            .build();

        List<Skill> skills = registry.discoverAllSkills();
        assertEquals(1, skills.size());
        assertEquals("Object Skill", skills.get(0).name());
    }

    @Test
    void shouldUseSkillDirNameFallback() {
        // No skills dir specified, no env var — should use CWD traversal
        CapabilityRegistry registry = CapabilityRegistry.builder().build();
        assertDoesNotThrow(registry::discoverSkills);
    }

    // ─── SkillSearchTool Tests ─────────────────────────────────

    @Test
    void shouldListAllSkills() throws Exception {
        createSkill("s1", "Alpha Search", "Search tools", "Search body");
        createSkill("s2", "Beta Analysis", "Analysis tools", "Analysis body");
        createSkill("s3", "Gamma Report", "Report tools", "Report body");

        Map<String, Skill> skillMap = buildSkillMap();
        SkillSearchTool tool = new SkillSearchTool(skillMap, null);

        ToolResult result = tool.execute("{}", null);

        assertTrue(result.text().contains("Alpha Search"));
        assertTrue(result.text().contains("Beta Analysis"));
        assertTrue(result.text().contains("Gamma Report"));
        assertTrue(result.text().contains("Available skills"));
    }

    @Test
    void shouldFilterSkillsByQuery() throws Exception {
        createSkill("s1", "Web Search", "Search the web", "Body");
        createSkill("s2", "File Reader", "Read files", "Body");
        createSkill("s3", "Web Fetch", "Fetch web content", "Body");

        Map<String, Skill> skillMap = buildSkillMap();
        SkillSearchTool tool = new SkillSearchTool(skillMap, null);

        ToolResult result = tool.execute("{\"query\": \"web\"}", null);

        assertTrue(result.text().contains("Web Search"));
        assertTrue(result.text().contains("Web Fetch"));
        assertFalse(result.text().contains("File Reader"));
    }

    @Test
    void shouldReturnNoSkillsForEmptyDir() throws Exception {
        Map<String, Skill> skillMap = Map.of();
        SkillSearchTool tool = new SkillSearchTool(skillMap, null);

        ToolResult result = tool.execute("{}", null);

        assertTrue(result.text().contains("No skills found"));
    }

    @Test
    void shouldReturnNoMatchForUnrelatedQuery() throws Exception {
        createSkill("s1", "Test Skill", "A test", "Body");

        Map<String, Skill> skillMap = buildSkillMap();
        SkillSearchTool tool = new SkillSearchTool(skillMap, null);

        ToolResult result = tool.execute("{\"query\": \"nonexistent\"}", null);

        assertTrue(result.text().contains("No skills found"));
    }

    @Test
    void shouldActivateSkillByName() throws Exception {
        createSkill("activate-me", "Activatable", "Can be activated", "Do the thing.");

        Map<String, Skill> skillMap = buildSkillMap();
        SkillSearchTool tool = new SkillSearchTool(skillMap, name -> {
            Skill s = skillMap.get(name);
            return "Activated: " + s.name() + " -> " + s.instructions();
        });

        ToolResult result = tool.execute("{\"skillName\": \"Activatable\"}", null);

        assertTrue(result.text().contains("Activated"));
        assertTrue(result.text().contains("Activatable"));
    }

    @Test
    void shouldReturnErrorForUnknownSkillActivation() throws Exception {
        createSkill("s1", "Known Skill", "Exists", "Body");

        Map<String, Skill> skillMap = buildSkillMap();
        SkillSearchTool tool = new SkillSearchTool(skillMap, null);

        ToolResult result = tool.execute("{\"skillName\": \"Unknown Skill\"}", null);

        assertTrue(result.text().contains("not found"));
        assertTrue(result.text().contains("Known Skill")); // shows available skills
    }

    @Test
    void shouldRejectBothQueryAndSkillName() throws Exception {
        createSkill("s1", "Test", "Test", "Body");

        Map<String, Skill> skillMap = buildSkillMap();
        SkillSearchTool tool = new SkillSearchTool(skillMap, null);

        ToolResult result = tool.execute("{\"query\": \"test\", \"skillName\": \"Test\"}", null);

        assertTrue(result.text().contains("Only one"));
    }

    @Test
    void shouldReturnSkillInstructionsOnActivationWithoutCallback() throws Exception {
        createSkill("instr-skill", "Instruction Skill", "Has instructions",
            "These are the instructions for the skill.");

        Map<String, Skill> skillMap = buildSkillMap();
        SkillSearchTool tool = new SkillSearchTool(skillMap, null);

        ToolResult result = tool.execute("{\"skillName\": \"Instruction Skill\"}", null);

        assertTrue(result.text().contains("Skill activated"));
        assertTrue(result.text().contains("Instruction Skill"));
        assertTrue(result.text().contains("instructions for the skill"));
    }

    @Test
    void shouldListSkillsCaseInsensitive() throws Exception {
        createSkill("s1", "My Skill", "A skill", "Body");

        Map<String, Skill> skillMap = buildSkillMap();
        SkillSearchTool tool = new SkillSearchTool(skillMap, null);

        ToolResult result = tool.execute("{\"query\": \"my\"}", null);
        assertTrue(result.text().contains("My Skill"));

        result = tool.execute("{\"query\": \"MY\"}", null);
        assertTrue(result.text().contains("My Skill"));
    }

    @Test
    void shouldListSkillsMatchingDescription() throws Exception {
        createSkill("s1", "Tool Finder", "Discovers available tools", "Body");

        Map<String, Skill> skillMap = buildSkillMap();
        SkillSearchTool tool = new SkillSearchTool(skillMap, null);

        ToolResult result = tool.execute("{\"query\": \"discovers\"}", null);
        assertTrue(result.text().contains("Tool Finder"));
    }

    // ─── Helpers ───────────────────────────────────────────────

    private Map<String, Skill> buildSkillMap() {
        CapabilityRegistry registry = CapabilityRegistry.builder()
            .skillDir(skillsDir)
            .build();
        var allSkills = registry.discoverAllSkills();
        var map = new LinkedHashMap<String, Skill>();
        for (var s : allSkills) map.put(s.name(), s);
        return map;
    }
}
