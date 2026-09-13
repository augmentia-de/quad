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
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class GrepTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_RESULTS = 250;

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

    @Tool("Search for a pattern within files. Supports regex.")
    public String grepSearch(
            @Param("pattern") String pattern,
            @Param(value = "path", required = false) String path,
            @Param(value = "include", required = false) String include,
            @Param(value = "caseSensitive", required = false) Boolean caseSensitive,
            @Param(value = "maxResults", required = false) Integer maxResults) {

        var state = currentState();
        if (state == null) {
            return "Error: No active session found";
        }

        if (pattern == null || pattern.isBlank()) {
            return "Error: Search pattern is required";
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

        int flags = Boolean.TRUE.equals(caseSensitive) ? 0 : Pattern.CASE_INSENSITIVE;
        Pattern regexPattern = Pattern.compile(pattern, flags);

        StringBuilder results = new StringBuilder();
        int matchCount = 0;

        try (Stream<Path> walk = Files.walk(searchRoot)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();

            for (Path file : files) {
                if (matchCount >= max) break;

                String fileName = file.getFileName().toString();
                if (include != null && !include.isBlank()) {
                    String globPattern = include.replace("*", ".*").replace("?", ".");
                    if (!Pattern.matches(globPattern, fileName)) {
                        continue;
                    }
                }

                String contentType = Files.probeContentType(file);
                if (contentType != null && (contentType.contains("image") || contentType.contains("video") || contentType.contains("zip"))) {
                    continue;
                }

                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size() && matchCount < max; i++) {
                    String line = lines.get(i);
                    if (regexPattern.matcher(line).find()) {
                        Path relPath = searchRoot.relativize(file);
                        results.append(relPath).append(":").append(i + 1).append(": ").append(line.trim()).append("\n");
                        matchCount++;
                    }
                }
            }
        } catch (IOException e) {
            return "Error during grep: " + e.getMessage();
        }

        ObjectNode json = MAPPER.createObjectNode();
        json.put("pattern", pattern);
        json.put("total", matchCount);

        if (matchCount == 0) {
            return "No matches found for pattern: " + pattern;
        }

        var matchesArray = json.putArray("matches");
        String[] resultLines = results.toString().split("\n");
        for (String line : resultLines) {
            if (!line.isBlank()) {
                ObjectNode matchObj = matchesArray.addObject();
                matchObj.put("text", line);
            }
        }

        return results.append(json.toPrettyString()).toString();
    }
}