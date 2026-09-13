-- Per-Agent-Hooks: benannte Lifecycle-Hooks (z.B. hitl), die wie Guardrails
-- pro Agent aus der UI aktiviert werden.
ALTER TABLE agents ADD COLUMN hooks text;