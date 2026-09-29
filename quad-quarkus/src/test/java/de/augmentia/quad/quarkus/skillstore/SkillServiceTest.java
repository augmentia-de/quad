package de.augmentia.quad.quarkus.skillstore;

import de.augmentia.quad.core.capability.skill.SkillEntry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SkillServiceTest {

    /** Minimal in-memory SkillEntry supplier for testing. */
    private static List<SkillEntry> inMemoryDb() {
        return new ArrayList<>();
    }

    private static Supplier<List<SkillEntry>> supplier(List<SkillEntry> entries) {
        return () -> entries;
    }

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

    private SkillEntry entry(String name, String description, String instructions, List<String> allowedTools) {
        return new SkillEntry(
            UUID.randomUUID().toString(), name, description, instructions,
            allowedTools != null ? allowedTools : List.of(),
            List.of(), Map.of(), null);
    }

    @Test
    void shouldLoadFileSkills() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-test");
        createSkill(skillsDir, "code-review", "Code review", "Review process");
        createSkill(skillsDir, "ki-research", "KI Research", "Research process");

        var service = new SkillService(supplier(inMemoryDb()), List.of(skillsDir), null);

        var result = service.loadAll();

        assertEquals(2, result.size());
        assertTrue(result.containsKey("code-review"));
        assertTrue(result.containsKey("ki-research"));
        assertEquals("file", result.get("code-review").source());
        assertEquals("Review process", result.get("code-review").instructions());
        assertFalse(result.get("code-review").assigned());
    }

    @Test
    void shouldDbOverrideFile() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-override");
        createSkill(skillsDir, "code-review", "File description", "File instructions");

        var db = new ArrayList<SkillEntry>();
        db.add(entry("code-review", "DB description", "DB instructions", List.of("read_file")));
        var service = new SkillService(supplier(db), List.of(skillsDir), null);

        var result = service.loadAll();

        assertEquals(1, result.size());
        assertEquals("db", result.get("code-review").source());
        assertEquals("DB description", result.get("code-review").description());
        assertEquals("DB instructions", result.get("code-review").instructions());
        assertEquals(List.of("read_file"), result.get("code-review").allowedTools());
    }

    @Test
    void shouldMergeFileAndDbSkills() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-merge");
        createSkill(skillsDir, "file-skill", "File skill", "File body");

        var db = new ArrayList<SkillEntry>();
        db.add(entry("db-skill", "DB skill", "DB body", List.of()));
        var service = new SkillService(supplier(db), List.of(skillsDir), null);

        var result = service.loadAll();

        assertEquals(2, result.size());
        assertEquals("file", result.get("file-skill").source());
        assertEquals("db", result.get("db-skill").source());
    }

    @Test
    void shouldReturnEmptyWhenNoSkills() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-empty");
        var service = new SkillService(supplier(inMemoryDb()), List.of(skillsDir), null);

        var result = service.loadAll();

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleNonExistentSkillDir() {
        var service = new SkillService(supplier(inMemoryDb()), List.of(Path.of("/nonexistent/path")), null);

        var result = service.loadAll();

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleDbSkillsWithoutFile() {
        var db = new ArrayList<SkillEntry>();
        db.add(entry("only-db", "DB only", "DB body", List.of("read_file", "write_file")));
        var service = new SkillService(supplier(db), List.of(Path.of("/nonexistent")), null);

        var result = service.loadAll();

        assertEquals(1, result.size());
        assertEquals("db", result.get("only-db").source());
        assertEquals(List.of("read_file", "write_file"), result.get("only-db").allowedTools());
    }

    @Test
    void shouldReturnAssignedFalseWhenNoAgentId() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-noagent");
        createSkill(skillsDir, "test-skill", "Test skill", "Test body");

        var service = new SkillService(supplier(inMemoryDb()), List.of(skillsDir), null);

        // loadAll() without agentId → all assigned=false
        var result = service.loadAll();
        assertFalse(result.get("test-skill").assigned());

        // loadAll(null) → same
        var resultNull = service.loadAll(null);
        assertFalse(resultNull.get("test-skill").assigned());
    }
}