package de.augmentia.quad.quarkus.skillstore;

import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.capability.skill.SkillEntry;
import de.augmentia.quad.core.capability.skill.SkillParser;
import de.augmentia.quad.core.capability.skill.TextSkill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Unified Skill Service: merges file-based and DB-based skills.
 *
 * <h3>Priority: DB overrides File</h3>
 * <pre>
 * 1. Load all skills from filesystem (SKILL.md files)
 * 2. Load all skills from database (skills table)
 * 3. DB entries override file entries with the same name
 * </pre>
 *
 * <h3>Agent-Skill Assignment</h3>
 * <p>Skills are always "active" (available). Only skills assigned to an agent
 * via {@code agent_skills} table are actually used by that agent — analogous
 * to {@code agent_tools}.
 *
 * <p>Use {@link #loadAll(String)} to get the skill list with the {@code assigned}
 * flag set for a specific agent. Use {@link #loadAll()} for the full catalog
 * without assignment info.
 */
public class SkillService {

    public record SkillView(
        String name,
        String description,
        String instructions,
        List<String> allowedTools,
        List<String> declaredTools,
        String source,          // "file" | "db"
        boolean assigned        // assigned to the current agent
    ) {}

    private final Supplier<List<SkillEntry>> dbSupplier;
    private final List<Path> skillDirs;
    private final JdbcAgentSkillStore agentSkillStore;

    public SkillService(Supplier<List<SkillEntry>> dbSupplier, List<Path> skillDirs) {
        this(dbSupplier, skillDirs, null);
    }

    public SkillService(JdbcSkillStore store, List<Path> skillDirs) {
        this(store::listAll, skillDirs, null);
    }

    public SkillService(JdbcSkillStore store, List<Path> skillDirs, JdbcAgentSkillStore agentSkillStore) {
        this(store::listAll, skillDirs, agentSkillStore);
    }

    public SkillService(Supplier<List<SkillEntry>> dbSupplier, List<Path> skillDirs,
                        JdbcAgentSkillStore agentSkillStore) {
        this.dbSupplier = dbSupplier;
        this.skillDirs = skillDirs != null ? skillDirs : List.of(Path.of("skills"));
        this.agentSkillStore = agentSkillStore;
    }

    /**
     * Returns all skills merged: file-based first, then DB overrides.
     * DB always wins when a skill exists in both sources.
     * The {@code assigned} flag is false for all skills (no agent context).
     */
    public Map<String, SkillView> loadAll() {
        return loadAll(null);
    }

    /**
     * Returns all skills merged with the {@code assigned} flag set for the
     * given agent (skills assigned via {@code agent_skills} table).
     */
    public Map<String, SkillView> loadAll(String agentId) {
        var result = new LinkedHashMap<String, SkillView>();
        var assigned = resolveAssigned(agentId);

        // 1. File-based skills (lower priority)
        for (var s : loadFromFile()) {
            result.put(s.name(), new SkillView(
                s.name(), s.description(), s.instructions(),
                s.allowedTools(), s.declaredTools(), "file", assigned.contains(s.name())));
        }

        // 2. DB-based skills (higher priority — override file)
        for (var entry : dbSupplier.get()) {
            result.put(entry.name(), new SkillView(
                entry.name(), entry.description(), entry.instructions(),
                entry.allowedTools(), entry.declaredTools(), "db", assigned.contains(entry.name())));
        }

        return result;
    }

    /**
     * Loads skills for a specific agent (only assigned skills).
     */
    public Map<String, SkillView> loadForAgent(String agentId) {
        return loadAll(agentId).entrySet().stream()
            .filter(e -> e.getValue().assigned())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (a, b) -> a, LinkedHashMap::new));
    }

    private Set<String> resolveAssigned(String agentId) {
        if (agentId == null || agentId.isBlank() || agentSkillStore == null) {
            return Set.of();
        }
        try {
            return Set.copyOf(agentSkillStore.listByAgent(agentId));
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * Loads skills from the filesystem (SKILL.md files).
     */
    private List<Skill> loadFromFile() {
        var result = new ArrayList<Skill>();
        for (var dir : skillDirs) {
            if (!Files.exists(dir) || !Files.isDirectory(dir)) continue;
            try {
                var parsed = SkillParser.fromDirectory(dir);
                for (var p : parsed) {
                    var skill = TextSkill.fromParseResult(p, dir.resolve(p.skillId()));
                    result.add(skill);
                }
            } catch (Exception e) {
                // skip unparseable dirs
            }
        }
        return result;
    }
}