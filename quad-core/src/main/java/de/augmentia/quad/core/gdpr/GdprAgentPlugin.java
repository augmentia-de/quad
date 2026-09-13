package de.augmentia.quad.core.gdpr;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.hook.plugin.Plugin;
import de.augmentia.quad.core.session.SessionManager;
import de.augmentia.quad.core.tool.ToolMethod;

import java.util.List;
import java.util.Set;

public class GdprAgentPlugin implements Plugin {

    private final SessionManager sessionManager;
    private final Set<PiiAnonymizerHook.MaskType> maskTypes;
    private final PiiAnonymizerHook.BlockAction blockAction;
    private final String replacement;
    private final AuditTrailHook.AuditStore auditStore;

    public GdprAgentPlugin(
            SessionManager sessionManager,
            Set<PiiAnonymizerHook.MaskType> maskTypes,
            PiiAnonymizerHook.BlockAction blockAction,
            String replacement,
            AuditTrailHook.AuditStore auditStore) {
        this.sessionManager = sessionManager;
        this.maskTypes = maskTypes;
        this.blockAction = blockAction;
        this.replacement = replacement;
        this.auditStore = auditStore;
    }

    @Override
    public String name() {
        return "gdpr-compliance";
    }

    @Override
    public List<ToolMethod> getTools() {
        return List.of(
            new GdprExportTool(sessionManager),
            new GdprDeleteTool(sessionManager)
        );
    }

    @Override
    public void initAgent(Agent agent) {
        if (maskTypes != null && !maskTypes.isEmpty()) {
            agent.addHook(new PiiAnonymizerHook(maskTypes, blockAction, replacement));
        }
        if (auditStore != null) {
            agent.addHook(new AuditTrailHook(auditStore));
        }
    }
}
