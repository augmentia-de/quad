package de.augmentia.quad.core.tool.builtin;

import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.session.WorkspaceResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@ApplicationScoped
public class MultiEditTool {

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

    @Tool("Perform multiple string replacements across one or more files atomically.")
    public String multiEdit(
            @Param("edits") List<EditOperation> edits) {

        var state = currentState();
        if (state == null) {
            return "Error: No active session found";
        }

        if (edits == null || edits.isEmpty()) {
            return "Error: No edits provided";
        }

        StringBuilder log = new StringBuilder("Execution summary:\n");

        for (EditOperation edit : edits) {
            try {
                Path targetPath;
                try {
                    targetPath = getWorkspaceResolver().resolveForWrite(state, edit.filePath());
                } catch (SecurityException e) {
                    return "Access denied: " + e.getMessage();
                }

                if (!Files.exists(targetPath)) {
                    return "Error: File does not exist: " + edit.filePath();
                }

                String content = Files.readString(targetPath);
                if (!content.contains(edit.oldString())) {
                    return "Error: Old string not found in file: " + edit.filePath();
                }

                String newContent;
                if (Boolean.TRUE.equals(edit.replaceAll())) {
                    newContent = content.replace(edit.oldString(), edit.newString());
                } else {
                    int firstIdx = content.indexOf(edit.oldString());
                    int lastIdx = content.lastIndexOf(edit.oldString());
                    if (firstIdx != lastIdx && !Boolean.TRUE.equals(edit.replaceAll())) {
                        return "Error: Multiple occurrences in " + edit.filePath() + ". Set replaceAll to true.";
                    }
                    newContent = content.substring(0, firstIdx) + edit.newString() + content.substring(firstIdx + edit.oldString().length());
                }

                Files.writeString(targetPath, newContent);
                log.append("Modified: ").append(edit.filePath()).append("\n");
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        return log.toString();
    }

    public static record EditOperation(String filePath, String oldString, String newString, Boolean replaceAll) {}
}