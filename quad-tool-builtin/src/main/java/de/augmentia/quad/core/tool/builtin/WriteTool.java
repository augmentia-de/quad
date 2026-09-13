package de.augmentia.quad.core.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.session.WorkspaceResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@ApplicationScoped
public class WriteTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Tool("Write or overwrite a file with the specified content.")
    public String writeFile(
            @Param("filePath") String filePath,
            @Param("content") String content) {

        var state = currentState();
        if (state == null) {
            return "Error: No active session found";
        }

        if (filePath == null || filePath.isBlank()) {
            return "Error: filePath is required";
        }
        if (content == null) {
            return "Error: content is required";
        }

        try {
            Path targetPath;
            try {
                targetPath = getWorkspaceResolver().resolveForWrite(state, filePath);
            } catch (SecurityException e) {
                return "Access denied: " + e.getMessage();
            }

            Path parentDir = targetPath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            Files.writeString(targetPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            ObjectNode json = MAPPER.createObjectNode();
            json.put("filePath", filePath);
            json.put("bytes", content.length());
            json.put("success", true);

            return "Successfully wrote " + content.length() + " bytes to: " + filePath + "\n" + json.toPrettyString();
        } catch (Exception e) {
            return "Error writing file: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @Tool("Append content to an existing file.")
    public String appendFile(
            @Param("filePath") String filePath,
            @Param("content") String content) {

        var state = currentState();
        if (state == null) {
            return "Error: No active session found";
        }

        if (filePath == null || filePath.isBlank()) {
            return "Error: filePath is required";
        }
        if (content == null) {
            return "Error: content is required";
        }

        try {
            Path targetPath;
            try {
                targetPath = getWorkspaceResolver().resolveForWrite(state, filePath);
            } catch (SecurityException e) {
                return "Access denied: " + e.getMessage();
            }

            Path parentDir = targetPath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            Files.writeString(targetPath, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            return "Successfully appended " + content.length() + " bytes to: " + filePath;
        } catch (Exception e) {
            return "Error appending to file: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}