package de.augmentia.quad.core.capability.skill;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.capability.context.ContextManager;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SkillRegistry {
    private static final Logger log = Logger.getLogger(SkillRegistry.class);

    private final Agent agent;
    private final ContextManager contextManager;
    private final Map<String, Skill> discovered = new HashMap<>();
    private final Map<String, Skill> loaded = new HashMap<>();
    private final Map<String, Skill> activated = new HashMap<>();
    private final Map<String, Skill> activatedByPattern = new HashMap<>();
    private final List<Path> skillDirectories = new ArrayList<>();

    public SkillRegistry(Agent agent, ContextManager contextManager) {
        this.agent = agent;
        this.contextManager = contextManager;
    }

    public void discoverSkillsDirs(List<Path> dirs) {
        for (Path dir : dirs) {
            skillDirectories.add(dir);
            List<SkillParseResult> parsed = SkillParser.fromDirectory(dir);
            for (SkillParseResult result : parsed) {
                Path skillDir = dir.resolve(result.skillId());
                TextSkill skill = TextSkill.fromParseResult(result, skillDir);
                discovered.put(result.skillId(), skill);
                log.info("Discovered skill: " + result.skillId());
            }
        }
    }

    public void load(List<String> patterns) {
        for (String pattern : patterns) {
            for (Map.Entry<String, Skill> entry : discovered.entrySet()) {
                if (entry.getValue() != null && !loaded.containsKey(entry.getKey())
                        && matchesPattern(entry.getKey(), pattern)) {
                    Skill skill = entry.getValue();
                    if (agent != null) skill.attach(agent);
                    loaded.put(entry.getKey(), skill);
                    log.info("Loaded skill: " + entry.getKey());
                }
            }
        }
    }

    private boolean matchesPattern(String name, String pattern) {
        return name.equals(pattern) || (pattern.contains("*") && name.matches(pattern.replace("*", ".*")));
    }

    public void activate(List<String> patterns) {
        for (String pattern : patterns) {
            for (Map.Entry<String, Skill> entry : loaded.entrySet()) {
                if (entry.getValue() != null && matchesPattern(entry.getKey(), pattern)) {
                    activateSkill(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void activateSkill(String name, Skill skill) {
        if (activated.containsKey(name)) {
            return;
        }

        String attrName = normalizeAttrName(name);
        if (agent != null) agent.setAttribute(attrName, skill);
        activated.put(name, skill);
        activatedByPattern.put(name, skill);

        if (contextManager != null && skill instanceof TextSkill) {
            contextManager.addBlock(new ContextManager.DynamicBlock("skills", () -> skill.status(null)));
        }

        log.info("Activated skill: " + name);
    }

    private String normalizeAttrName(String skillId) {
        int dot = skillId.lastIndexOf('.');
        return (dot >= 0 ? skillId.substring(dot + 1) : skillId).replace("-", "_");
    }

    public void deactivate(List<String> patterns) {
        for (String pattern : patterns) {
            Skill skill = activated.remove(pattern);
            if (skill != null) {
                String attrName = normalizeAttrName(pattern);
                if (agent != null) agent.setAttribute(attrName, null);
                if (contextManager != null) contextManager.remove("skills");
                log.info("Deactivated skill: " + pattern);
            }
        }
    }

    public List<String> listDiscovered() {
        return new ArrayList<>(discovered.keySet());
    }

    public List<String> listLoaded() {
        return new ArrayList<>(loaded.keySet());
    }

    public List<String> listActivated() {
        return new ArrayList<>(activated.keySet());
    }

    public Skill getSkill(String name) {
        Skill s = activated.get(name);
        if (s != null) return s;
        s = loaded.get(name);
        if (s != null) return s;
        return discovered.get(name);
    }

    /** Returns all loaded + activated skills as a map. */
    public Map<String, Skill> getSkills() {
        var result = new LinkedHashMap<String, Skill>();
        for (var entry : discovered.entrySet()) {
            if (entry.getValue() != null) result.put(entry.getKey(), entry.getValue());
        }
        for (var entry : loaded.entrySet()) {
            result.putIfAbsent(entry.getKey(), entry.getValue());
        }
        for (var entry : activated.entrySet()) {
            result.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public List<Path> skillDirectories() {
        return List.copyOf(skillDirectories);
    }
}
