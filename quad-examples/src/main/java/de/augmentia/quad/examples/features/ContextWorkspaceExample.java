package de.augmentia.quad.examples.features;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.WorkspaceResolver;

/**
 * Feature 9: Context &amp; Workspace (AgentSessionState / WorkspaceResolver)
 *
 * Ein WorkspaceAwareAgent, der den Arbeitsordner der Session isoliert
 * und Session-Variablen (Tenant, Projekt) speichert.
 */
public class ContextWorkspaceExample extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Zeigt aktuellen Session-Kontext an")
    public String showContext() {
        AgentSessionState state = newSessionState();
        return "Tenant: " + state.getTenantId()
            + ", Projekt: " + state.getCurrentProject()
            + ", Session: " + state.getSessionId();
    }

    public static void main(String[] args) {
        AgentSessionState state = new AgentSessionState();
        state.setTenantId("tenant-1");
        state.setCurrentProject("Projekt-X");

        String sessionWorkspace = WorkspaceResolver.resolve(state.getSessionId());
        System.out.println("Workspace Pfad: " + sessionWorkspace);

        String sessionWorkspaceSub = WorkspaceResolver.resolve(state.getSessionId(), "output");
        System.out.println("Workspace Sub-Pfad: " + sessionWorkspaceSub);

        ContextWorkspaceExample agent = AgentBuilder.create(ContextWorkspaceExample.class)
            .withLlmFromEnv()
            .build();

        String result = agent.executeReAct("Zeige den aktuellen Session-Kontext", state);
        System.out.println(result);
    }
}
