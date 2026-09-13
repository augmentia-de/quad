package de.augmentia.quad.core.tool.sandbox;

import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.agent.runtime.SandboxClient;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;

public class BashSandboxTool {

    private final SandboxClient sandbox;

    public BashSandboxTool(SandboxClient sandbox) {
        this.sandbox = sandbox;
    }

    @Tool("Executes a bash command in a sandboxed Docker container with no network access and limited memory.")
    public String executeBash(@Param(value = "script", required = true) String script) {
        AgentSessionState state = CurrentSession.getCurrent();
        String sessionId = state != null ? state.getSessionId() : "default";
        return sandbox.run(script, sessionId);
    }
}
