package de.augmentia.quad.core.hitl;

import de.augmentia.quad.core.guardrails.ApprovalResult;

public interface HITLProvider {
    ApprovalResult requestApproval(String action, String context);
}
