-- Per-Agent timeout: optionale Laufzeitbegrenzung in Sekunden.
-- NULL = kein individuelles Limit (erbt den globalen Default).
ALTER TABLE agents ADD COLUMN timeout_seconds integer;
