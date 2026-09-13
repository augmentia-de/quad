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
import java.nio.file.StandardCopyOption;

@ApplicationScoped
public class ApplyPatchTool {

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

    @Tool("Apply a patch with Add, Update, Move, Delete operations.")
    public String applyPatch(
            @Param("patchText") String patchText) {

        var state = currentState();
        if (state == null) {
            return "Error: No active session found";
        }

        if (patchText == null || patchText.isBlank()) {
            return "Error: patchText is required";
        }

        StringBuilder log = new StringBuilder("Patch Results:\n");
        Path currentFile = null;
        StringBuilder currentContent = new StringBuilder();
        String currentMode = "";

        String[] lines = patchText.split("\\r?\\n");
        for (String line : lines) {
            if (line.startsWith("*** Begin Patch") || line.startsWith("*** End Patch")) {
                continue;
            }

            if (line.startsWith("*** Add File:")) {
                executePending(currentFile, currentContent, currentMode, log, state);
                try {
                    currentFile = resolveWrite(state, line.substring(13).trim());
                } catch (SecurityException e) {
                    return "Access denied: " + e.getMessage();
                }
                currentContent = new StringBuilder();
                currentMode = "ADD";
            } else if (line.startsWith("*** Update File:")) {
                executePending(currentFile, currentContent, currentMode, log, state);
                try {
                    currentFile = resolveWrite(state, line.substring(16).trim());
                } catch (SecurityException e) {
                    return "Access denied: " + e.getMessage();
                }
                currentContent = new StringBuilder();
                currentMode = "UPDATE";
                if (Files.exists(currentFile)) {
                    try {
                        currentContent.append(Files.readString(currentFile));
                    } catch (IOException ignored) {}
                }
            } else if (line.startsWith("*** Delete File:")) {
                executePending(currentFile, currentContent, currentMode, log, state);
                try {
                    Path delFile = resolveWrite(state, line.substring(16).trim());
                    if (Files.deleteIfExists(delFile)) {
                        log.append("Deleted: ").append(delFile.getFileName()).append("\n");
                    }
                } catch (SecurityException e) {
                    return "Access denied: " + e.getMessage();
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
                currentFile = null;
                currentMode = "";
            } else if (line.startsWith("*** Move to:")) {
                if ("UPDATE".equals(currentMode) && currentFile != null) {
                    try {
                        Path moveTarget = resolveWrite(state, line.substring(12).trim());
                        executePending(currentFile, currentContent, currentMode, log, state);
                        if (Files.exists(currentFile)) {
                            Files.createDirectories(moveTarget.getParent());
                            Files.move(currentFile, moveTarget, StandardCopyOption.REPLACE_EXISTING);
                            log.append("Moved to: ").append(moveTarget.getFileName()).append("\n");
                            currentFile = moveTarget;
                            if (Files.exists(currentFile)) {
                                currentContent = new StringBuilder(Files.readString(currentFile));
                            }
                        }
                    } catch (SecurityException e) {
                        return "Access denied: " + e.getMessage();
                    } catch (IOException e) {
                        return "Error: " + e.getMessage();
                    }
                }
            } else {
                if ("ADD".equals(currentMode) && line.startsWith("+")) {
                    currentContent.append(line.substring(1)).append("\n");
                } else if ("UPDATE".equals(currentMode)) {
                    if (line.startsWith("+")) {
                        currentContent.append(line.substring(1)).append("\n");
                    } else if (line.startsWith("-")) {
                        String clean = line.substring(1);
                        int idx = currentContent.indexOf(clean);
                        if (idx != -1) {
                            currentContent.delete(idx, idx + clean.length());
                        }
                    }
                }
            }
        }
        executePending(currentFile, currentContent, currentMode, log, state);

        return log.toString();
    }

    private Path resolveWrite(AgentSessionState state, String subPath) {
        return getWorkspaceResolver().resolveForWrite(state, subPath);
    }

    private void executePending(Path file, StringBuilder content, String mode, StringBuilder log, AgentSessionState state) {
        if (file == null || mode == null || mode.isBlank()) return;
        if ("ADD".equals(mode) || "UPDATE".equals(mode)) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, content.toString());
                log.append("Saved (").append(mode).append("): ").append(file.getFileName()).append("\n");
            } catch (IOException e) {
                log.append("Error saving file: ").append(e.getMessage()).append("\n");
            }
        }
    }
}