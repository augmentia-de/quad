package de.augmentia.quad.core.gdpr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.SessionManager;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class GdprDeleteTool implements ToolMethod {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionManager sessionManager;
    private final ToolSpecification spec;

    public GdprDeleteTool(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.spec = ToolSpecification.builder()
            .name("gdpr_delete")
            .description("Deletes a session and all associated data (GDPR Art. 17)")
            .parameters(JsonObjectSchema.builder()
                .addStringProperty("sessionId", "ID of the session to delete")
                .addBooleanProperty("cascade", "Cascading delete (also dependent data)")
                .required("sessionId")
                .build())
            .build();
    }

    @Override
    public ToolSpecification spec() {
        return spec;
    }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        JsonNode node = MAPPER.readTree(jsonArguments != null ? jsonArguments : "{}");
        if (!node.has("sessionId")) {
            throw new RuntimeException("sessionId ist ein erforderlicher Parameter");
        }
        String sessionId = node.get("sessionId").asText();

        if (sessionId == null || sessionId.isBlank()) {
            throw new RuntimeException("sessionId must not be empty");
        }

        var session = sessionManager.getSession(sessionId);
        if (session == null) {
            return ToolResult.success("Session " + sessionId + " not found - nothing to delete");
        }

        sessionManager.removeSession(sessionId);

        var result = MAPPER.createObjectNode();
        result.put("action", "DELETE");
        result.put("sessionId", sessionId);
        result.put("deletedAt", java.time.Instant.now().toString());
        result.put("status", "DELETED");

        return ToolResult.success(result.toPrettyString());
    }
}
