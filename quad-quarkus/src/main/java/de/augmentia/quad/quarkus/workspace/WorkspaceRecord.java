package de.augmentia.quad.quarkus.workspace;

import java.time.Instant;

/**
 * A row of {@code workspaces} — the workspace registry the UI surfaces in the
 * workspace picker/recent list and for trusted-command prompts.
 */
public record WorkspaceRecord(
    String path,
    String name,
    boolean trusted,
    String commandTrust,
    String gitBranch,
    Instant lastAccessedAt
) {}
