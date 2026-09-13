package de.augmentia.quad.core.tool.builtin;

import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.session.WorkspaceResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class ReadFileTool {

    private static final int DEFAULT_LINE_LIMIT = 500;
    private static final int MAX_LINE_LIMIT = 2000;

    @Inject
    protected WorkspaceResolver workspaceResolver;

    @Inject
    protected CurrentSession currentSession;

    protected AgentSessionState currentState() {
        return currentSession != null ? currentSession.get() : CurrentSession.getCurrent();
    }

    protected WorkspaceResolver getWorkspaceResolver() {
        return workspaceResolver;
    }

    public void setWorkspaceResolver(WorkspaceResolver resolver) {
        this.workspaceResolver = resolver;
    }

    @Tool("Reads content from a file within the workspace. Supports line-level pagination.")
    public String readFile(
            @Param("path") String path,
            @Param(value = "offset", required = false) Integer offset,
            @Param(value = "limit", required = false) Integer limit) {

        var state = currentState();
        if (state == null) {
            return "Error: No active session found";
        }

        Path targetPath;
        try {
            targetPath = getWorkspaceResolver().resolveForRead(state, path);
        } catch (SecurityException e) {
            return e.getMessage();
        }

        if (!Files.exists(targetPath)) {
            return "Error: File not found at path '" + path + "'";
        }

        if (Files.isDirectory(targetPath)) {
            return "Error: Path '" + path + "' is a directory, not a file.";
        }

        int startLine = (offset == null || offset < 1) ? 1 : offset;
        int lineCount = (limit == null || limit <= 0) ? DEFAULT_LINE_LIMIT : Math.min(limit, MAX_LINE_LIMIT);

        try (Stream<String> lines = Files.lines(targetPath)) {
            List<String> selectedLines = lines
                    .skip(startLine - 1)
                    .limit(lineCount)
                    .toList();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("--- File: %s (Lines %d-%d) ---\n", path, startLine, startLine + selectedLines.size() - 1));

            int current = startLine;
            for (String line : selectedLines) {
                sb.append(String.format("%6d | %s\n", current++, line));
            }

            return sb.toString();
        } catch (IOException e) {
            return "Error reading file '" + path + "': " + e.getMessage();
        }
    }
}