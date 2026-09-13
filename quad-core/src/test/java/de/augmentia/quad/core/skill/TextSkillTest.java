package de.augmentia.quad.core.skill;

import de.augmentia.quad.core.capability.skill.TextSkill;
import de.augmentia.quad.core.session.AgentSessionState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class TextSkillTest {

    @Test
    void shouldCreateTextSkillWithPath() {
        Path skillPath = Paths.get("/tmp/skill.skill");
        TextSkill skill = new TextSkill(skillPath);

        assertNotNull(skill);
        assertTrue(skill.getName().contains("skill"));
        assertTrue(skill.getSkillFile().toString().endsWith("skill.skill"));
    }

    @Test
    void shouldCreateTextSkillWithFullParameters() {
        Path skillPath = Paths.get("/tmp/custom-skill/");
        TextSkill skill = new TextSkill("custom-skill", "A custom skill description", skillPath);

        assertEquals("custom-skill", skill.getName());
        assertEquals("A custom skill description", skill.getDescription());
        assertEquals(skillPath, skill.getSkillFile());
    }

    @Test
    void shouldCreateDefaultTextSkill() {
        TextSkill skill = new TextSkill();

        assertEquals("text-skill", skill.getName());
        assertEquals("Generic text skill", skill.getDescription());
        assertNull(skill.getSkillFile());
    }

    @Test
    void shouldGenerateStatusWithNoState() {
        TextSkill skill = new TextSkill();

        String status = skill.status(null);

        assertTrue(status.contains("Skill: text-skill"));
        assertTrue(status.contains("Type: TEXT"));
        assertTrue(status.contains("Generic text skill"));
    }

    @Test
    void shouldGenerateStatusWithSessionState() {
        TextSkill skill = new TextSkill("test-skill", "Test skill description", null);
        AgentSessionState state = new AgentSessionState();
        state.setTenantId("tenant-123");
        state.setCurrentProject("Project Alpha");

        String status = skill.status(state);

        assertTrue(status.contains("Skill: test-skill"));
        assertTrue(status.contains("Description: Test skill description"));
    }

    @Test
    void shouldHandleNullSkillFile() {
        TextSkill skill = new TextSkill();

        assertEquals("text-skill", skill.getName());
    }

    @Test
    void shouldHandleStateWithNullValues() {
        TextSkill skill = new TextSkill();
        AgentSessionState state = new AgentSessionState();

        String status = skill.status(state);

        assertTrue(status.contains("Skill: text-skill"));
        assertTrue(status.contains("Description: Generic text skill"));
    }
}