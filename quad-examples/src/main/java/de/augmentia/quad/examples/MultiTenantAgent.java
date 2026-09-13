package de.augmentia.quad.examples;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.capability.TenantContext;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.session.InMemorySessionManager;

/**
 * Demonstrates multi-tenancy with ThreadLocal tenant context isolation.
 * Each request can set a tenant context that is automatically cleaned up.
 */
public class MultiTenantAgent extends Agent {

    private final InMemorySessionManager sessionManager = new InMemorySessionManager();

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    public String processRequest(String prompt, String tenantId) {
        try (TenantContext.Scope scope = TenantContext.withTenant(tenantId)) {
            AgentSessionState state = sessionManager.createSession();
            state.setTenantId(tenantId);

            System.out.println("Processing for tenant: " + TenantContext.current().tenantId());
            
            // LLM call would happen here
            return "Processed for " + tenantId + ": " + prompt;
        }
    }

    public static void main(String... args) {
        MultiTenantAgent agent = new MultiTenantAgent();
        agent.setLlm(ModelFactory.createOpenAiFromEnv());

        String result1 = agent.processRequest("Hello from tenant A", "tenant-a");
        String result2 = agent.processRequest("Hello from tenant B", "tenant-b");

        System.out.println(result1);
        System.out.println(result2);
    }
}