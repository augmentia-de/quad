package de.augmentia.quad.core.capability;

import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.capability.skill.SkillParser;
import de.augmentia.quad.core.capability.skill.TextSkill;
import de.augmentia.quad.core.tool.QuadToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Unified discovery registry for Skills (SKILL.md), MCP-Tools and Standard-Tools.
 */
public class CapabilityRegistry {

    public record Capability(
        String name,
        String description,
        String source,
        CapabilityType type
    ) {}

    public enum CapabilityType { SKILL, MCP_TOOL, DEFAULT }

    private final List<Path> skillDirectories;
    private final List<McpServerConfig> mcpServers;
    private final boolean includeStandardTools;
    private final List<Capability> extraDefaultTools;

    public CapabilityRegistry(List<Path> skillDirectories, List<McpServerConfig> mcpServers,
                               boolean includeStandardTools, List<Capability> extraDefaultTools) {
        this.skillDirectories = List.copyOf(skillDirectories);
        this.mcpServers = List.copyOf(mcpServers);
        this.includeStandardTools = includeStandardTools;
        this.extraDefaultTools = List.copyOf(extraDefaultTools);
    }

    private static final String DEFAULT_SKILLS_DIR = "skills";

    private List<Path> resolveSkillDirs() {
        var resolved = skillDirectories.stream()
            .map(d -> d.isAbsolute() ? d : Path.of("").toAbsolutePath().resolve(d))
            .filter(Files::isDirectory)
            .toList();
        if (!resolved.isEmpty()) return resolved;

        String envDir = System.getenv("QUAD_SKILLS_DIR");
        String dirName = (envDir != null && !envDir.isBlank()) ? envDir : DEFAULT_SKILLS_DIR;
        Path path = Path.of(dirName);
        if (!path.isAbsolute()) path = Path.of("").toAbsolutePath().resolve(path);
        if (Files.isDirectory(path)) return List.of(path);
        return List.of();
    }

    public List<Capability> discoverSkills() {
        var results = new ArrayList<Capability>();
        for (var dir : resolveSkillDirs()) {
            try {
                var parsed = SkillParser.fromDirectory(dir);
                for (var p : parsed) {
                    results.add(new Capability(p.name(), p.description(),
                        dir.resolve(p.skillId()).toString(),
                        CapabilityType.SKILL));
                }
            } catch (Exception e) {
                results.add(new Capability("error",
                    "Failed to scan: " + dir + " - " + e.getMessage(),
                    dir.toString(), CapabilityType.SKILL));
            }
        }
        return results;
    }

    public List<Capability> discoverTools() {
        return new ArrayList<>(extraDefaultTools);
    }

    public List<Capability> discoverAll() {
        var results = new ArrayList<Capability>();
        results.addAll(discoverTools());
        results.addAll(discoverSkills());
        return results;
    }

    public List<Skill> discoverAllSkills() {
        return resolveSkillDirs().stream()
            .flatMap(d -> {
                try { return SkillParser.fromDirectory(d).stream()
                    .map(p -> (Skill) TextSkill.fromParseResult(p, d.resolve(p.skillId())));
                } catch (Exception e) { return java.util.stream.Stream.of(); }
            })
            .toList();
    }

    public Skill getSkill(String name) {
        return discoverAllSkills().stream()
            .filter(s -> s.name().equals(name))
            .findFirst().orElse(null);
    }

    public Set<String> knownToolNames(QuadToolRegistry registry) {
        if (registry == null) return Set.of();
        return registry.getAll().stream()
            .map(t -> t.spec().name())
            .collect(java.util.stream.Collectors.toSet());
    }

    public List<Path> skillDirectories() { return skillDirectories; }
    public List<McpServerConfig> mcpServers() { return mcpServers; }

    public record McpServerConfig(String name, String url, Map<String, Object> clientConfig) {
        public McpServerConfig(String name, String url) { this(name, url, Map.of()); }
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<Path> skillDirectories = new ArrayList<>();
        private final List<McpServerConfig> mcpServers = new ArrayList<>();
        private final List<Capability> extraDefaultTools = new ArrayList<>();
        private boolean includeStandardTools = false;

        public Builder skillDir(Path dir) { skillDirectories.add(dir); return this; }
        public Builder skillDirs(Path... dirs) { skillDirectories.addAll(List.of(dirs)); return this; }
        public Builder mcpServer(String name, String url) {
            mcpServers.add(new McpServerConfig(name, url)); return this; }
        public Builder includeStandardTools(boolean v) { includeStandardTools = v; return this; }
        public Builder registerDefaultTool(String name, String description) {
            extraDefaultTools.add(new Capability(name, description, "default", CapabilityType.DEFAULT));
            return this; }

        public CapabilityRegistry build() {
            return new CapabilityRegistry(skillDirectories, mcpServers,
                includeStandardTools, extraDefaultTools);
        }
    }
}
