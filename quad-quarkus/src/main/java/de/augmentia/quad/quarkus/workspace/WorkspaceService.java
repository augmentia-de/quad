package de.augmentia.quad.quarkus.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Workspace orchestration: open/recent/temp/scratch workspaces, trusted-command
 * consent and per-session project bindings + root-folders. Persisted state in
 * {@code workspaces}/{@code project_bindings}.
 */
@ApplicationScoped
public class WorkspaceService {

    private static final Logger log = Logger.getLogger(WorkspaceService.class);

    private final Map<String, String> sessionWorkspace = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Boolean>> sessionRoots = new ConcurrentHashMap<>();

    @Inject
    WorkspaceStore store;

    @ConfigProperty(name = "quad.agent.workspace", defaultValue = ".")
    String workspacePath;

    public WorkspaceService() {}

    // -- recent / open / trust ----------------------------------------------------

    public List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkspaceRecord w : store.recent(limit)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", w.path());
            m.put("name", w.name());
            m.put("exists", Files.exists(Path.of(w.path())));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> open(String rawPath, boolean create) {
        String path = normalize(rawPath);
        Path dir = Path.of(path);
        if (!Files.exists(dir)) {
            if (create) {
                try { Files.createDirectories(dir); } catch (IOException e) {
                    return Map.of("ok", false, "error", "Cannot create workspace: " + e.getMessage());
                }
            } else {
                return Map.of("ok", false, "error", "Workspace not found: " + rawPath);
            }
        }
        if (!Files.isDirectory(dir)) {
            return Map.of("ok", false, "error", "Not a directory: " + rawPath);
        }
        String name = workspaceName(path);
        String branch = detectGitBranch(path);
        store.record(path, name, branch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("path", path);
        out.put("git_branch", branch);
        out.put("command_trust", commandTrust(path));
        return out;
    }

    public Map<String, Object> commandTrust(String path) {
        String normalized = normalize(path);
        boolean exists = Files.isDirectory(Path.of(normalized));
        Optional<WorkspaceRecord> w = store.byPath(normalized);
        boolean trusted = w.map(WorkspaceRecord::trusted).orElse(false);
        List<String> requests = readCommandTrust(w.map(WorkspaceRecord::commandTrust).orElse(null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspace", normalized);
        out.put("requested_commands", requests);
        out.put("trusted", trusted);
        out.put("required", false);
        out.put("exists", exists);
        return out;
    }

    public Map<String, Object> setTrust(String rawPath, boolean trusted, List<String> requestedCommands) {
        String path = normalize(rawPath);
        String trustJson = "[]";
        if (requestedCommands != null && !requestedCommands.isEmpty()) {
            trustJson = "[" + String.join(",", requestedCommands.stream()
                .map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList()) + "]";
        }
        if (trusted && !Files.isDirectory(Path.of(path))) {
            return Map.of("ok", false, "error", "Workspace not found: " + rawPath);
        }
        store.setTrusted(path, trustJson, trusted);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(commandTrust(path));
        return out;
    }

    public List<Map<String, Object>> trustedWorkspaces() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkspaceRecord w : store.trusted()) {
            out.add(commandTrust(w.path()));
        }
        return out;
    }

    // -- temp / scratch / save-as -------------------------------------------------

    public Map<String, Object> createTemp(String sessionId, boolean git) {
        String scratchBase = scratchBase();
        String path;
        try {
            path = normalize(scratchBase + File.separator + safeSegment(sessionId));
            Path dir = Path.of(path);
            Files.createDirectories(dir);
            if (git && !Files.exists(dir.resolve(".git"))) runGit(dir, "init");
        } catch (IOException e) {
            return Map.of("ok", false, "error", "Cannot create temp workspace: " + e.getMessage());
        }
        String branch = detectGitBranch(path);
        sessionWorkspace.put(sessionId, path);
        store.record(path, "scratch", branch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("path", path);
        out.put("git", git);
        return out;
    }

    public Map<String, Object> saveAsProject(String sessionId, String targetRaw) {
        if (targetRaw == null || targetRaw.isBlank()) {
            return Map.of("ok", false, "error", "path is required");
        }
        String target = normalize(targetRaw);
        Path dest = Path.of(target);
        String current = sessionWorkspace.get(sessionId);
        boolean moved = false;
        try {
            if (current != null && !normalize(current).equals(target) && Files.exists(Path.of(current))) {
                if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                try {
                    Files.move(Path.of(current), dest);
                } catch (IOException moveFailed) {
                    copyRecursively(Path.of(current), dest);
                    deleteRecursively(Path.of(current));
                }
                moved = true;
            } else if (!Files.exists(dest)) {
                Files.createDirectories(dest);
            }
        } catch (IOException e) {
            return Map.of("ok", false, "error", "Cannot save project: " + e.getMessage());
        }
        sessionWorkspace.put(sessionId, target);
        store.record(target, workspaceName(target), detectGitBranch(target));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("path", target);
        out.put("moved", moved);
        return out;
    }

    /** The active workspace directory of a session (temp/scratch overrides the default). */
    public String workspaceFor(String sessionId) {
        String bound = sessionWorkspace.get(sessionId);
        if (bound != null) return bound;
        return defaultWorkspacePath();
    }

    // -- roots ---------------------------------------------------------------------

    public List<Map<String, Object>> roots(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        String primary = workspaceFor(sessionId);
        if (primary != null && !primary.isBlank()) {
            out.add(rootInfo(primary, "workspace", true, true));
        }
        for (Map.Entry<String, Boolean> e : sessionRoots.getOrDefault(sessionId, Map.of()).entrySet()) {
            if (!e.getKey().equals(primary)) {
                out.add(rootInfo(e.getKey(), workspaceName(e.getKey()), false, e.getValue()));
            }
        }
        return out;
    }

    public Map<String, Object> addRoot(String sessionId, String path, boolean writable) {
        if (path == null || path.isBlank()) {
            return Map.of("ok", false, "error", "path is required");
        }
        String normalized = normalize(path);
        if (!Files.isDirectory(Path.of(normalized))) {
            return Map.of("ok", false, "error", "Not a directory: " + path);
        }
        sessionRoots.computeIfAbsent(sessionId, k -> new LinkedHashMap<>()).put(normalized, writable);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("roots", roots(sessionId));
        return out;
    }

    public Map<String, Object> removeRoot(String sessionId, String path) {
        if (path != null) {
            String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
            sessionRoots.getOrDefault(sessionId, Map.of()).remove(normalize(decoded));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("roots", roots(sessionId));
        return out;
    }

    // -- project menu / bindings ---------------------------------------------------

    public Map<String, Object> projectMenu(String sessionId, String kind) {
        String k = "board".equals(kind) ? "board" : "memory";
        Optional<String> bound = store.getBinding(sessionId, k);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", k);
        out.put("bound", bound.orElse(null));
        out.put("derived", derivedProject(sessionId));
        List<Map<String, Object>> named = new ArrayList<>();
        bound.ifPresent(name -> named.add(Map.of("name", name, "key", k + ":" + name)));
        out.put("named", named);
        return out;
    }

    public Map<String, Object> setBinding(String sessionId, String kind, String name) {
        String k = "board".equals(kind) ? "board" : "memory";
        store.setBinding(sessionId, k, name == null ? "" : name);
        return Map.of("ok", true);
    }

    public Map<String, Object> projectName(String sessionId, String kind, String name) {
        String k = "board".equals(kind) ? "board" : "memory";
        if (name == null || name.isBlank()) {
            store.setBinding(sessionId, k, "");
            return Map.of("ok", true, "name", "");
        }
        store.setBinding(sessionId, k, name);
        return Map.of("ok", true, "name", name);
    }

    private Map<String, Object> derivedProject(String sessionId) {
        String workspace = workspaceFor(sessionId);
        if (workspace != null && !workspace.isBlank()) {
            String branch = detectGitBranch(workspace);
            if (branch != null) {
                return Map.of("kind", "git", "label", branch, "full", branch, "key", workspace);
            }
            return Map.of("kind", "folder", "label", workspaceName(workspace), "full", workspace, "key", workspace);
        }
        return null;
    }

    // -- helpers -------------------------------------------------------------------

    private Map<String, Object> rootInfo(String path, String label, boolean primary, boolean writable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("writable", writable);
        m.put("label", label);
        m.put("primary", primary);
        m.put("exists", Files.exists(Path.of(path)));
        return m;
    }

    private String scratchBase() {
        return defaultWorkspacePath() + File.separator + "scratch";
    }

    private String defaultWorkspacePath() {
        return Path.of(workspacePath).toAbsolutePath().normalize().toString();
    }

    private String normalize(String path) {
        if (path == null || path.isBlank()) return defaultWorkspacePath();
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private static String safeSegment(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "-");
    }

    private static String workspaceName(String path) {
        String p = path;
        if (p.endsWith("/") || p.endsWith(File.separator)) p = p.substring(0, p.length() - 1);
        int idx = Math.max(p.lastIndexOf('/'), p.lastIndexOf(File.separatorChar));
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    private String detectGitBranch(String dir) {
        try {
            Path gitDir = Path.of(dir).resolve(".git");
            if (!Files.exists(gitDir)) return null;
            String out = runGit(Path.of(dir), "rev-parse", "--abbrev-ref", "HEAD");
            return out == null || out.isBlank() ? null : out.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String runGit(Path dir, String... args) {
        try {
            var cmd = new ArrayList<String>();
            cmd.add("git");
            cmd.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            return output;
        } catch (Exception e) {
            log.debugf("git command failed: %s", e.getMessage());
            return null;
        }
    }

    private List<String> readCommandTrust(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Object v = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
            if (v instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        } catch (Exception ignored) {}
        return List.of();
    }

    private void copyRecursively(Path source, Path dest) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path p : stream.toList()) {
                Path target = dest.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) { Files.createDirectories(target); }
                else { Files.copy(p, target); }
            }
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
