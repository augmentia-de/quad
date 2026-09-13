package de.augmentia.quad.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.gdpr.GdprDeleteTool;
import de.augmentia.quad.core.gdpr.GdprExportTool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.InMemorySessionManager;

/**
 * Demonstrates GDPR compliance tools for data export and deletion.
 */
public class GdprDemo {

    public static void main(String... args) throws Exception {
        InMemorySessionManager sessionManager = new InMemorySessionManager();
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("=== GDPR Compliance Demo ===\n");

        // Create test session
        AgentSessionState session = sessionManager.createSession();
        session.setTenantId("tenant-demo");
        session.addFinding("User requested data export");
        session.addFinding("Search history: AI trends 2024");

        GdprExportTool exportTool = new GdprExportTool(sessionManager);
        GdprDeleteTool deleteTool = new GdprDeleteTool(sessionManager);

        System.out.println("1. GDPR Export Tool:");
        String exportJson = "{ \"sessionId\": \"" + session.getSessionId() + "\" }";
        String exportResult = exportTool.execute(exportJson, session).text();
        System.out.println("   Export result:\n" + exportResult);

        System.out.println("\n2. GDPR Delete Tool:");
        String deleteJson = "{ \"sessionId\": \"" + session.getSessionId() + "\" }";
        String deleteResult = deleteTool.execute(deleteJson, session).text();
        System.out.println("   Delete result:\n" + deleteResult);

        System.out.println("\n3. Session exists after delete? " + (sessionManager.getSession(session.getSessionId()) == null));

        System.out.println("\n=== GDPR Demo Complete ===");
    }
}