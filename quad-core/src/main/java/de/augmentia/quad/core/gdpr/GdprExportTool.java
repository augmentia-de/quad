package de.augmentia.quad.core.gdpr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.SessionManager;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

public class GdprExportTool implements ToolMethod {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionManager sessionManager;
    private final ToolSpecification spec;

    public GdprExportTool(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.spec = ToolSpecification.builder()
            .name("gdpr_export")
            .description("Exportiert eine Session in maschinenlesbarem JSON-Format (Art. 20 DSGVO)")
            .parameters(JsonObjectSchema.builder()
                .addStringProperty("sessionId", "ID der zu exportierenden Session")
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

        AgentSessionState session = sessionManager.getSession(sessionId);
        if (session == null) {
            throw new RuntimeException("Session nicht gefunden: " + sessionId);
        }

        var export = MAPPER.createObjectNode();
        export.put("exportType", "GDPR_DATA_EXPORT");
        export.put("exportDate", java.time.Instant.now().toString());
        export.put("sessionId", session.getSessionId());
        export.put("tenantId", session.getTenantId());
        export.put("currentProject", session.getCurrentProject());
        export.put("createdAt", java.time.Instant.now().toString());

        var findingsArray = export.putArray("findings");
        for (var finding : session.findings()) {
            findingsArray.add(finding);
        }

        return ToolResult.success(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(export));
    }
}
