package de.augmentia.quad.core.capability.skill;

import de.augmentia.quad.core.session.AgentSessionState;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class TextSkill extends Skill {

    private final String name;
    private final String description;
    private final String instructions;
    private final Path skillFile;

    public TextSkill() {
        this.name = "text-skill";
        this.description = "Generic text skill";
        this.instructions = "";
        this.skillFile = null;
    }

    public TextSkill(Path skillFile) {
        this.name = skillFile != null ? skillFile.getFileName().toString().replace(".skill", "") : "text-skill";
        this.description = "Text-based skill from " + (skillFile != null ? skillFile.toString() : "default");
        this.instructions = "";
        this.skillFile = skillFile;
    }

    public TextSkill(String name, String description, Path skillFile) {
        this(name, description, "", skillFile);
    }

    public TextSkill(String name, String description, String instructions, Path skillFile) {
        this.name = name;
        this.description = description;
        this.instructions = instructions != null ? instructions : "";
        this.skillFile = skillFile;
    }

    public static TextSkill fromParseResult(SkillParseResult result, Path skillDir) {
        TextSkill skill = new TextSkill(result.name(), result.description(), result.body(),
                skillDir.resolve("SKILL.md"));
        skill.sourceDir = skillDir;
        skill.allowedTools = result.allowedTools() != null ? result.allowedTools() : List.of();
        skill.declaredTools = result.declaredTools() != null ? result.declaredTools() : List.of();
        skill.metadata = result.metadata() != null ? result.metadata() : Map.of();
        skill.license = result.license();
        skill.compatibility = result.compatibility();
        return skill;
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() { return description; }

    @Override
    public String instructions() { return instructions; }

    @Override
    public Path path() { return skillFile; }

    @Override
    public String status(AgentSessionState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("Skill: ").append(name).append("\n");
        sb.append("Type: TEXT\n");
        sb.append("Description: ").append(description).append("\n");
        if (!instructions.isEmpty())
            sb.append("Instructions: ").append(instructions, 0, Math.min(200, instructions.length())).append("...\n");
        if (!allowedTools.isEmpty())
            sb.append("Allowed tools: ").append(String.join(", ", allowedTools)).append("\n");
        if (!declaredTools.isEmpty())
            sb.append("Declared tools: ").append(String.join(", ", declaredTools)).append("\n");
        if (skillFile != null)
            sb.append("Source: ").append(skillFile.toString()).append("\n");
        return sb.toString();
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInstructions() { return instructions; }
    public Path getSkillFile() { return skillFile; }
}
