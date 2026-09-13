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
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@ApplicationScoped
public class LsTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));

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

    @Tool("List directory contents with optional recursion and details.")
    public String listDirectory(
            @Param(value = "path", required = false) String path,
            @Param(value = "recursive", required = false) Boolean recursive,
            @Param(value = "details", required = false) Boolean details,
            @Param(value = "depth", required = false) Integer depth) {

        var state = currentState();
        if (state == null) {
            return "Error: No active session found";
        }

        Path targetDir;
        try {
            targetDir = (path != null && !path.isBlank())
                    ? getWorkspaceResolver().resolveForRead(state, path)
                    : getWorkspaceResolver().workspaceDir();
        } catch (SecurityException e) {
            return "Access denied: " + e.getMessage();
        }

        if (!Files.exists(targetDir)) {
            return "Error: Path does not exist: " + targetDir;
        }
        if (!Files.isDirectory(targetDir)) {
            return "Error: Path is not a directory: " + targetDir;
        }

        List<Path> entries = new ArrayList<>();
        try {
            if (Boolean.TRUE.equals(recursive)) {
                int maxDepth = (depth != null && depth > 0) ? depth : Integer.MAX_VALUE;
                try (Stream<Path> stream = Files.walk(targetDir, maxDepth)) {
                    stream.skip(1).forEach(entries::add);
                }
            } else {
                try (Stream<Path> stream = Files.list(targetDir)) {
                    stream.forEach(entries::add);
                }
            }
        } catch (IOException e) {
            return "Error listing directory: " + e.getMessage();
        }

        entries.sort(Comparator.comparing(Path::toString));

        ObjectNode json = MAPPER.createObjectNode();
        json.put("directory", path != null ? path : targetDir.toString());

        var entriesArray = json.putArray("entries");
        boolean showDetails = Boolean.TRUE.equals(details);

        for (Path entry : entries) {
            ObjectNode obj = entriesArray.addObject();
            String name = targetDir.relativize(entry).toString();
            obj.put("name", Files.isDirectory(entry) ? name + "/" : name);
            obj.put("type", Files.isDirectory(entry) ? "dir" : "file");
            if (showDetails) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    obj.put("size", attrs.size());
                    obj.put("modified", ISO_FORMAT.format(attrs.lastModifiedTime().toInstant()));
                } catch (IOException ignored) {}
            }
        }

        json.put("totalEntries", entriesArray.size());
        json.put("details", showDetails);

        return json.toPrettyString();
    }
}