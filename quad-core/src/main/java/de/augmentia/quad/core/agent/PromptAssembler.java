package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.capability.context.ContextManager;
import de.augmentia.quad.core.capability.doc.AgentDocExtractor;
import de.augmentia.quad.core.capability.prompt.SkillListRenderer;
import de.augmentia.quad.core.capability.prompt.ToolListRenderer;
import de.augmentia.quad.core.capability.skill.SkillRegistry;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.WorkspaceResolver;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the initial chat messages (system + user) for an LLM call.
 * Extracted from Agent.java to separate prompt construction from agent orchestration.
 */
public class PromptAssembler {

    private final AgentDocExtractor docExtractor;
    private final QuadToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final ContextManager contextManager;
    private final ToolListRenderer toolListRenderer;
    private final SkillListRenderer skillListRenderer;
    private final WorkspaceResolver workspaceResolver;

    public PromptAssembler(AgentDocExtractor docExtractor,
                           QuadToolRegistry toolRegistry,
                           SkillRegistry skillRegistry,
                           ContextManager contextManager,
                           ToolListRenderer toolListRenderer,
                           SkillListRenderer skillListRenderer) {
        this(docExtractor, toolRegistry, skillRegistry, contextManager, toolListRenderer, skillListRenderer, null);
    }

    public PromptAssembler(AgentDocExtractor docExtractor,
                           QuadToolRegistry toolRegistry,
                           SkillRegistry skillRegistry,
                           ContextManager contextManager,
                           ToolListRenderer toolListRenderer,
                           SkillListRenderer skillListRenderer,
                           WorkspaceResolver workspaceResolver) {
        this.docExtractor = docExtractor;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.contextManager = contextManager;
        this.toolListRenderer = toolListRenderer;
        this.skillListRenderer = skillListRenderer;
        this.workspaceResolver = workspaceResolver;
    }

    /**
     * Builds the initial chat messages for an LLM call.
     */
    public List<ChatMessage> buildInitialMessages(String prompt, AgentSessionState state,
                                                  String systemPrompt, boolean jsonInput,
                                                  String userMessageTemplate,
                                                  String jsonOutputSchema,
                                                  String cachePrefix) {
        String stateDoc = docExtractor != null ? docExtractor.doc(Object.class, state) : "snapshot";

        List<ToolSpecification> specs = toolRegistry != null
                ? toolRegistry.getSpecifications().stream().map(s -> (ToolSpecification) s).toList()
                : List.of();

        // Static cache prefix (Block A) goes FIRST, byte-identical across agents,
        // so the provider's prefix-cache serves the shared template content.
        StringBuilder sysMsgBuilder = new StringBuilder();
        if (cachePrefix != null && !cachePrefix.isBlank()) {
            sysMsgBuilder.append(cachePrefix).append("\n\n");
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sysMsgBuilder.append(systemPrompt).append("\n\n");
        }
        sysMsgBuilder.append(stateDoc);

        String workspacePath = getWorkspaceInfo(state);
        if (workspacePath != null && !workspacePath.isBlank()) {
            sysMsgBuilder.append("\n\n## Workspace\n");
            sysMsgBuilder.append("You are operating within a sandboxed workspace. All file operations must be confined to the workspace and any granted directories below.\n");
            sysMsgBuilder.append("- **Session workspace:** `").append(workspacePath).append("`  \n");
            sysMsgBuilder.append("- Use relative paths for files within this workspace.\n");
            sysMsgBuilder.append("- Absolute paths outside the workspace and granted directories will be rejected by the security layer.\n");
        }

        if (state != null && !state.grantedDirectories().isEmpty()) {
            sysMsgBuilder.append("\n## Granted Directories\n");
            sysMsgBuilder.append("You are additionally permitted to access these user-granted directories:\n");
            for (var gd : state.grantedDirectories()) {
                String access = gd.isWritable() ? "read/write" : "read-only";
                sysMsgBuilder.append("- `").append(gd.path()).append("` (").append(access).append(")\n");
            }
        }

        if (toolListRenderer != null && !specs.isEmpty()) {
            String withTools = toolListRenderer.renderWithTools(sysMsgBuilder.toString(), specs);
            sysMsgBuilder.setLength(0);
            sysMsgBuilder.append(withTools);
        }

        if (skillRegistry != null && !skillRegistry.listDiscovered().isEmpty()) {
            String catalog = skillListRenderer.renderCatalog(sysMsgBuilder.toString(), skillRegistry);
            sysMsgBuilder.setLength(0);
            sysMsgBuilder.append(catalog);
        }

        List<ChatMessage> messages = new ArrayList<>();

        String sysMsgText = sysMsgBuilder.toString();
        if (contextManager != null && contextManager.hasBlocks()) {
            messages.add(contextManager.renderSystemMessage(sysMsgText, null));
        } else {
            messages.add(new SystemMessage(sysMsgText));
        }

        // Structured-output/JSON mode: always a real UserMessage so the task stays out of the
        // system prompt. The schema is enforced exclusively via `response_format` (responseFormat),
        // not via a duplicated schema section in the system prompt.
        if (jsonInput) {
            // A workflow node may have rendered the template already (AgentNodeExecutor does that
            // from the predecessor outputs). In that case `prompt` is plain text and rendering
            // again would throw the result away. Template substitution therefore applies only when
            // the prompt really is a JSON document, which is the start-envelope case.
            String rendered = renderUserMessageTemplate(prompt, userMessageTemplate);
            messages.add(dev.langchain4j.data.message.UserMessage.from(rendered));
        } else if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(dev.langchain4j.data.message.UserMessage.from(prompt));
        } else {
            String sysMsgWithPrompt = sysMsgText + "\nPrompt: " + prompt;
            messages.set(0, contextManager != null && contextManager.hasBlocks()
                    ? contextManager.renderSystemMessage(sysMsgWithPrompt, null)
                    : new SystemMessage(sysMsgWithPrompt));
        }

        return messages;
    }

    private String getWorkspaceInfo(AgentSessionState state) {
        if (workspaceResolver == null || state == null) {
            return null;
        }
        try {
            return workspaceResolver.sessionDir(state.getSessionId()).toString();
        } catch (Exception e) {
            return WorkspaceResolver.WORKSPACE_BASE + "/" + state.getSessionId();
        }
    }

    /**
     * Renders the system-prompt section that instructs the model to reply with a single JSON
     * object matching the configured schema. Empty when no schema is provided.
     */
    public static String jsonOutputSection(String jsonOutputSchema) {
        if (jsonOutputSchema == null || jsonOutputSchema.isBlank()) return "";
        return "\n## JSON Output\n"
            + "Your final answer must be a single JSON object that conforms exactly to this JSON schema:\n"
            + jsonOutputSchema + "\n"
            + "Return only that JSON object \u2014 no markdown code fences and no text before or after it.\n";
    }

    /**
     * Renders a user-message template against a JSON input document.
     *
     * <p>Substitutes {@code {{key}}} with the fields of {@code inputJson}. If the input is not a
     * JSON object — the common case once a workflow node has already rendered its template — the
     * input is returned unchanged. It is already the final prompt in that case, and substituting
     * against a non-JSON string used to discard it and return the raw template, which lost every
     * resolved value.
     *
     * @param inputJson prompt text or a JSON document
     * @param template  template to apply; may be null or blank
     */
    public static String renderUserMessageTemplate(String inputJson, String template) {
        if (template == null || template.isBlank()) {
            return inputJson != null ? inputJson : "";
        }
        if (inputJson == null || inputJson.isBlank()) {
            return template;
        }

        com.fasterxml.jackson.databind.JsonNode tree;
        try {
            tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(inputJson);
        } catch (Exception notJson) {
            // Plain-text prompt: a workflow node already rendered it, so it is final. Applying the
            // template again would discard every resolved value.
            return inputJson;
        }
        if (tree == null || !tree.isObject()) {
            // A JSON array or scalar carries no field names to substitute.
            return inputJson;
        }

        // {{input}} means "the whole document". Resolve it before the field pass so a JSON field
        // literally named "input" cannot claim the placeholder.
        String result = template;
        if (result.contains("{{input}}")) {
            result = result.replace("{{input}}", inputJson);
        }

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var fields = tree.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String placeholder = "{{" + entry.getKey() + "}}";
            if (!result.contains(placeholder)) continue;
            String value = entry.getValue().isTextual()
                    ? entry.getValue().asText()
                    : writeValue(mapper, entry.getValue());
            result = result.replace(placeholder, value);
        }
        return result;
    }

    private static String writeValue(com.fasterxml.jackson.databind.ObjectMapper mapper,
                                     com.fasterxml.jackson.databind.JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return node.asText();
        }
    }
}
