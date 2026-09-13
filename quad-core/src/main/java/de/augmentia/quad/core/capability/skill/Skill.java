package de.augmentia.quad.core.capability.skill;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.session.AgentSessionState;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public abstract class Skill {
    protected transient Agent agent;
    protected Path sourceDir;
    protected List<String> allowedTools = List.of();
    protected List<String> declaredTools = List.of();
    protected Map<String, Object> metadata = Map.of();
    protected String license;
    protected String compatibility;

    public void attach(Agent agent) {
        this.agent = agent;
    }

    public void detach() {
        this.agent = null;
    }

    public abstract String name();

    public abstract String description();

    public abstract String instructions();

    public Path path() {
        return sourceDir;
    }

    public List<String> allowedTools() {
        return allowedTools;
    }

    public List<String> declaredTools() {
        return declaredTools;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public String license() {
        return license;
    }

    public String compatibility() {
        return compatibility;
    }

    public String status(AgentSessionState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Skill: ").append(name()).append("\n");
        sb.append("Description: ").append(description()).append("\n");
        if (!allowedTools.isEmpty())
            sb.append("Allowed tools: ").append(String.join(", ", allowedTools)).append("\n");
        if (!declaredTools.isEmpty())
            sb.append("Declared tools: ").append(String.join(", ", declaredTools)).append("\n");
        return sb.toString();
    }

    public Path getSourceDir() {
        return sourceDir;
    }

    public void setSourceDir(Path sourceDir) {
        this.sourceDir = sourceDir;
    }
}
