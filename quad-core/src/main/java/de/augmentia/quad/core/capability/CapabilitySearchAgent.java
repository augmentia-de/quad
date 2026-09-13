package de.augmentia.quad.core.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.capability.skill.SkillParser;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CapabilitySearchAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatModel llm;

    public record ToolEnrichment(String skillName, List<String> enrichedTools) {
        public ToolEnrichment(String skillName) {
            this(skillName, List.of());
        }
    }

    public record Analysis(
        String analysis,
        List<String> recommendedSkills,
        List<String> recommendedTools,
        String reasoning,
        List<ToolEnrichment> toolEnrichments,
        int candidatesSearched
    ) {
        public String toJson() {
            try {
                var result = MAPPER.createObjectNode();
                result.put("analysis", analysis != null ? analysis : "");
                result.put("reasoning", reasoning != null ? reasoning : "");
                result.put("candidatesSearched", candidatesSearched);
                result.set("recommendedSkills", MAPPER.valueToTree(recommendedSkills != null ? recommendedSkills : List.of()));
                result.set("recommendedTools", MAPPER.valueToTree(recommendedTools != null ? recommendedTools : List.of()));
                var enrichments = MAPPER.valueToTree(toolEnrichments != null ? toolEnrichments : List.of());
                result.set("toolEnrichments", enrichments);
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            } catch (Exception e) {
                return "{\n  \"error\": \"Failed to serialize analysis\"\n}";
            }
        }
    }

    public CapabilitySearchAgent() {
        this.llm = null;
    }

    public CapabilitySearchAgent(ChatModel llm) {
        this.llm = llm;
    }

    public void setLlm(ChatModel llm) {
        this.llm = llm;
    }

    @Override
    protected AgentSessionState newSessionState() {
        return AgentSessionState.create("capability-search-agent-" + System.currentTimeMillis());
    }

    public Analysis selectTopTools(String task,
                                   Map<String, Skill> skills,
                                   List<Capability> candidates,
                                   int maxResults,
                                   AgentSessionState state) {
        if (llm == null) {
            return new Analysis("No LLM configured", List.of(), List.of(), 
                "LLM not available", List.of(), candidates.size());
        }

        String systemPrompt = buildSelectionPrompt(skills, candidates);
        String response;
        try {
            var llmResponse = llm.chat(SystemMessage.from(systemPrompt), UserMessage.from(task));
            response = llmResponse.aiMessage().text();
        } catch (Exception e) {
            return new Analysis("LLM error: " + e.getMessage(), List.of(), List.of(),
                "LLM call failed", List.of(), candidates.size());
        }

        return parseAnalysisResponse(response, candidates.size());
    }

    public Analysis analyze(String task, Map<String, Skill> skills,
                            List<Capability> defaultCapabilities) {
        if (llm == null) {
            return new Analysis("No LLM configured", List.of(), List.of(),
                "LLM not available", List.of(), 0);
        }
        var systemPrompt = buildPrompt(skills, defaultCapabilities);
        var response = llm.chat(SystemMessage.from(systemPrompt), UserMessage.from(task));
        return new Analysis(response.aiMessage().text(), List.of(), List.of(), "raw", List.of(), 0);
    }

    private Analysis parseAnalysisResponse(String response, int candidatesSearched) {
        try {
            JsonNode root = MAPPER.readTree(response);
            String analysis = root.has("analysis") ? root.get("analysis").asText() : "";
            String reasoning = root.has("reasoning") ? root.get("reasoning").asText() : "";

            List<String> recommendedSkills = new ArrayList<>();
            if (root.has("recommendedSkills")) {
                for (JsonNode node : root.get("recommendedSkills")) {
                    if (node.isTextual()) recommendedSkills.add(node.asText());
                }
            }

            List<String> recommendedTools = new ArrayList<>();
            if (root.has("recommendedTools")) {
                for (JsonNode node : root.get("recommendedTools")) {
                    if (node.isTextual()) recommendedTools.add(node.asText());
                }
            }

            List<ToolEnrichment> enrichments = new ArrayList<>();
            if (root.has("toolEnrichments")) {
                for (JsonNode node : root.get("toolEnrichments")) {
                    String skillName = node.has("skillName") ? node.get("skillName").asText() : "";
                    List<String> tools = new ArrayList<>();
                    if (node.has("enrichedTools")) {
                        for (JsonNode t : node.get("enrichedTools")) {
                            if (t.isTextual()) tools.add(t.asText());
                        }
                    }
                    enrichments.add(new ToolEnrichment(skillName, tools));
                }
            }

            return new Analysis(analysis, recommendedSkills, recommendedTools, 
                reasoning, enrichments, candidatesSearched);
        } catch (Exception e) {
            return new Analysis("Failed to parse LLM response: " + e.getMessage(),
                List.of(), List.of(), "parse error", List.of(), candidatesSearched);
        }
    }

    private String buildSelectionPrompt(Map<String, Skill> skills, List<Capability> candidates) {
        var sb = new StringBuilder();
        sb.append("You are a tool selection agent. Analyze the task and select the BEST matching tools.\n\n");

        if (skills != null && !skills.isEmpty()) {
            sb.append("## AVAILABLE SKILLS\n");
            for (var s : skills.values()) {
                sb.append("- ").append(s.name()).append(": ").append(s.description()).append("\n");
                if (s.declaredTools() != null && !s.declaredTools().isEmpty()) {
                    sb.append("  Tools: ").append(String.join(", ", s.declaredTools())).append("\n");
                }
                if (s.allowedTools() != null && !s.allowedTools().isEmpty()) {
                    sb.append("  Allowed: ").append(String.join(", ", s.allowedTools())).append("\n");
                }
            }
            sb.append("\n");
        }

        if (candidates != null && !candidates.isEmpty()) {
            sb.append("## PRE-FILTERED CANDIDATES (from vector search)\n");
            for (var c : candidates) {
                sb.append("- ").append(c.name()).append(": ").append(c.description()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Select the TOP ").append(Math.min(3, (candidates != null ? candidates.size() : 0))).append(" most relevant tools.\n\n");
        sb.append("Respond with valid JSON:\n");
        sb.append("{\n");
        sb.append("  \"analysis\": \"brief analysis of what tools are needed\",\n");
        sb.append("  \"recommendedSkills\": [\"skill1\", \"skill2\"],\n");
        sb.append("  \"recommendedTools\": [\"tool1\", \"tool2\"],\n");
        sb.append("  \"reasoning\": \"why these tools were selected\",\n");
        sb.append("  \"toolEnrichments\": [\n");
        sb.append("    {\"skillName\": \"x\", \"enrichedTools\": [\"y\", \"z\"]}\n");
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String buildPrompt(Map<String, Skill> skills,
                               List<Capability> defaultCapabilities) {
        var sb = new StringBuilder();
        sb.append("""
            You are a capability analysis agent. Your task is to find the best-matching
            skills and tools for a given task.

            RULES:
            1. When both a named skill and a default tool match, PREFER the skill.
            2. Group results by functional area.
            3. CLEARLY separate skills from bare tools.
            4. If no match, state that clearly.

            TOOL ENRICHMENT:
            Each skill may declare tools via `declaredTools`. Review for:
            - Missing essential standard tools → add to enrichedTools
            - Typos (e.g. "wite" → "write") → correct
            - Implied tools (e.g. "find" implies "read") → add
            """);

        if (!skills.isEmpty()) {
            sb.append("## Available Skills\n\n");
            for (var s : skills.values()) {
                sb.append("### ").append(s.name()).append("\n");
                sb.append("**Description:** ").append(s.description()).append("\n");
                sb.append("**Instructions:**\n");
                try {
                    var skillMd = SkillParser.findSkillMdFile(s.path());
                    sb.append(java.nio.file.Files.readString(skillMd)).append("\n\n");
                } catch (Exception e) {
                    sb.append(s.instructions() != null ? s.instructions() : "").append("\n\n");
                }
                if (s.allowedTools() != null && !s.allowedTools().isEmpty()) {
                    sb.append("**Allowed tools:** ").append(String.join(", ", s.allowedTools())).append("\n");
                }
                if (s.declaredTools() != null && !s.declaredTools().isEmpty()) {
                    sb.append("**Declared tools:** ").append(String.join(", ", s.declaredTools())).append("\n");
                }
            }
        }

        if (defaultCapabilities != null && !defaultCapabilities.isEmpty()) {
            sb.append("## Available Default Tools\n\n");
            for (var cap : defaultCapabilities) {
                sb.append("- ").append(cap.name());
                if (cap.description() != null && !cap.description().isBlank())
                    sb.append(": ").append(cap.description());
                sb.append("\n");
            }
        }

        sb.append("""
            Respond with valid JSON:
            {
              "analysis": "string",
              "recommendedSkills": ["string"],
              "recommendedTools": ["string"],
              "reasoning": "string",
              "toolEnrichments": [
                {"skillName": "string", "enrichedTools": ["string"]}
              ]
            }
            """);
        return sb.toString();
    }
}
