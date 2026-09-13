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

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@ApplicationScoped
public class FindTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_RESULTS = 100;

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

    @Tool("Find files by glob pattern (e.g., *.java, src/**/*.ts).")
    public String findFiles(
            @Param("pattern") String pattern,
            @Param(value = "path", required = false) String path,
            @Param(value = "maxResults", required = false) Integer maxResults) {

        var state = currentState();
        if (state == null) {
            return "Error: No active session found";
        }

        if (pattern == null || pattern.isBlank()) {
            return "Error: Glob pattern is required";
        }

        int max = (maxResults == null || maxResults <= 0) ? DEFAULT_MAX_RESULTS : maxResults;
        Path searchRoot;
        try {
            searchRoot = (path != null && !path.isBlank())
                    ? getWorkspaceResolver().resolveForRead(state, path)
                    : getWorkspaceResolver().workspaceDir();
        } catch (SecurityException e) {
            return "Access denied: " + e.getMessage();
        }

        String cleanPattern = pattern.replace("\\", "/");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + cleanPattern);

        // Java glob: a leading "**/" requires a directory boundary, so "**/*.md" never matches
        // files directly in the search root. Test the pattern without that prefix as well.
        PathMatcher topLevelMatcher = cleanPattern.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + cleanPattern.substring(3))
                : null;

        List<String> matchedFiles = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(searchRoot)) {
            List<Path> allPaths = walk.toList();

            for (Path filePath : allPaths) {
                if (matchedFiles.size() >= max) break;

                Path relativePath = searchRoot.relativize(filePath);
                String relativeStr = relativePath.toString().replace("\\", "/");

                if (matcher.matches(relativePath) || (topLevelMatcher != null && topLevelMatcher.matches(relativePath))) {
                    try {
                        if (Files.isRegularFile(filePath)) {
                            matchedFiles.add(relativeStr);
                        }
                    } catch (Exception e) {
                    }
                }
            }
        } catch (IOException e) {
            return "Error during file system traversal: " + e.getMessage();
        }

        ObjectNode json = MAPPER.createObjectNode();
        json.put("pattern", cleanPattern);
        json.put("total", matchedFiles.size());

        var filesArray = json.putArray("files");
        for (String f : matchedFiles) {
            filesArray.add(f);
        }
        json.put("truncated", matchedFiles.size() >= max);

        return json.toPrettyString();
    }
}