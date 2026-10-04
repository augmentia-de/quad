package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.capability.skill.SkillParser;
import de.augmentia.quad.core.capability.skill.TextSkill;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.SkillLoadTool;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillJitE2eTest {

    static class TestAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
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

    private Map<String, Skill> loadSkills(Path skillsDir) throws Exception {
        var parsed = SkillParser.fromDirectory(skillsDir);
        var map = new LinkedHashMap<String, Skill>();
        for (var p : parsed) {
            var skill = TextSkill.fromParseResult(p, skillsDir.resolve(p.skillId()));
            map.put(skill.name(), skill);
        }
        return map;
    }

    @Test
    void shouldLoadSkillViaToolCallAndInjectInstructions() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-jit-e2e");
        createSkill(skillsDir, "code-review", "Code review skill",
            "## Review process\n1. Check structure\n2. Find errors\n3. Check security",
            "allowed-tools:\n  - read_file\n  - grep_search");

        var skills = loadSkills(skillsDir);
        var loadSkillTool = new SkillLoadTool(skills);

        ToolExecutionRequest loadReq = ToolExecutionRequest.builder()
            .id("c1").name("load_skill")
            .arguments("{\"skillName\":\"code-review\"}").build();

        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
            .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(loadReq))).build(),
                        ChatResponse.builder().aiMessage(AiMessage.from("final")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        agent.setToolExecutor(new DirectToolExecutor());

        var registry = new de.augmentia.quad.core.tool.QuadToolRegistry(
            new de.augmentia.quad.core.tool.ToolArgsMapper(
                new com.fasterxml.jackson.databind.ObjectMapper()));
        registry.register("load_skill", loadSkillTool);
        agent.setToolRegistry(registry);

        String result = agent.run("Please review this code", new AgentSessionState());

        assertThat(result).isEqualTo("final");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        List<ChatMessage> secondCallMessages = captor.getAllValues().get(1).messages();

        boolean foundSkillInstructions = secondCallMessages.stream()
            .anyMatch(m -> m instanceof ToolExecutionResultMessage trem
                && trem.text().contains("Review process"));
        assertThat(foundSkillInstructions)
            .as("Skill instructions must be injected via load_skill tool result")
            .isTrue();
    }

    @Test
    void shouldNotInjectFullInstructionsAtStartup() throws Exception {
        Path skillsDir = Files.createTempDirectory("skills-jit-startup");
        createSkill(skillsDir, "code-review", "Code review skill",
            "## Review process\n1. Check structure\n2. Find errors\n3. Check security",
            "allowed-tools:\n  - read_file\n  - grep_search");

        var skills = loadSkills(skillsDir);
        var loadSkillTool = new SkillLoadTool(skills);

        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
            .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        agent.setToolExecutor(new DirectToolExecutor());

        var registry = new de.augmentia.quad.core.tool.QuadToolRegistry(
            new de.augmentia.quad.core.tool.ToolArgsMapper(
                new com.fasterxml.jackson.databind.ObjectMapper()));
        registry.register("load_skill", loadSkillTool);
        agent.setToolRegistry(registry);

        agent.run("task", new AgentSessionState());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        ChatMessage firstMsg = captor.getValue().messages().get(0);
        assertThat(firstMsg).isInstanceOf(SystemMessage.class);
        String systemPrompt = ((SystemMessage) firstMsg).text();

        // Without AgentSkillsPlugin registered, no skill catalog is injected.
        // The full instructions must never appear in the system prompt.
        assertThat(systemPrompt).doesNotContain("Review process");
        assertThat(systemPrompt).doesNotContain("Check structure");
        assertThat(systemPrompt).doesNotContain("Check security");
    }

    private static class DirectToolExecutor extends de.augmentia.quad.core.agent.runtime.ToolExecutor {
        DirectToolExecutor() {
            super(new de.augmentia.quad.core.agent.runtime.IdempotencyStore(),
                new de.augmentia.quad.core.agent.runtime.OTelAgentTracer(),
                new de.augmentia.quad.core.agent.runtime.AgentEventPublisher());
        }
        @Override
        public ToolResult execute(ToolMethod tool, String jsonArguments, AgentSessionState state) {
            try {
                return tool.execute(jsonArguments, state);
            } catch (Exception e) {
                return ToolResult.error("Error: " + e.getMessage());
            }
        }
    }
}