package de.augmentia.quad.core.observability.provenance;

import java.time.Instant;

public record ProvenanceEntry(String toolName, String source, Instant at) {
}
