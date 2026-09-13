package de.augmentia.quad.core.capability.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkillParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*(.*?)\\s*---\\s*(.*)$", Pattern.DOTALL);

    public static SkillParseResult parseFrontmatter(String content, Path skillDir) {
        if (!content.startsWith("---")) {
            throw new IllegalArgumentException("SKILL.md must start with YAML frontmatter (---)");
        }

        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid SKILL.md format");
        }

        String frontmatter = matcher.group(1);
        String body = matcher.group(2).trim();
        String skillId = normalizeSkillId(skillDir.getFileName().toString());

        try {
            var node = YAML_MAPPER.readTree(frontmatter);
            String name = node.has("name") ? node.get("name").asText() : skillId;
            String description = node.has("description") ? node.get("description").asText() : "";

            List<String> allowedTools = parseStringArray(node, "allowed-tools");
            List<String> declaredTools = parseStringArray(node, "declared-tools");

            Map<String, Object> metadata = Map.of();
            if (node.has("metadata") && node.get("metadata").isObject()) {
                metadata = JSON_MAPPER.convertValue(node.get("metadata"),
                    new TypeReference<Map<String, Object>>() {});
            }

            String license = node.has("license") ? node.get("license").asText() : null;
            String compatibility = node.has("compatibility") ? node.get("compatibility").asText() : null;

            return new SkillParseResult(skillId, description, body, name,
                allowedTools, declaredTools, metadata, license, compatibility);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse frontmatter", e);
        }
    }

    public static Path findSkillMdFile(Path skillDir) {
        Path upper = skillDir.resolve("SKILL.md");
        if (Files.exists(upper)) return upper;
        Path lower = skillDir.resolve("skill.md");
        if (Files.exists(lower)) return lower;
        return upper;
    }

    public static List<SkillParseResult> fromDirectory(Path dir) {
        List<SkillParseResult> results = new ArrayList<>();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return results;
        }

        try (var entries = Files.list(dir)) {
            entries.filter(Files::isDirectory)
                .filter(d -> Files.exists(d.resolve("SKILL.md")) || Files.exists(d.resolve("skill.md")))
                .forEach(skillDir -> {
                    try {
                        Path skillMd = findSkillMdFile(skillDir);
                        String content = Files.readString(skillMd);
                        results.add(parseFrontmatter(content, skillDir));
                    } catch (Exception e) {
                        // skip unparseable skills
                    }
                });
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan skill directory: " + dir, e);
        }

        return results;
    }

    private static List<String> parseStringArray(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node.has(field) && node.get(field).isArray()) {
            var list = new ArrayList<String>();
            node.get(field).forEach(t -> list.add(t.asText()));
            return List.copyOf(list);
        }
        return List.of();
    }

    private static String normalizeSkillId(String dirName) {
        return dirName.toLowerCase()
            .replace(" ", "-")
            .replace("_", "-");
    }

    public static List<Path> findSkillDirectories(Path baseDir) {
        List<Path> result = new ArrayList<>();
        if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            return result;
        }

        try (var entries = Files.list(baseDir)) {
            entries.filter(Files::isDirectory)
                .filter(d -> {
                    Path skillMd = d.resolve("SKILL.md");
                    Path skillMdLower = d.resolve("skill.md");
                    return Files.exists(skillMd) || Files.exists(skillMdLower);
                })
                .forEach(result::add);
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan skill directories: " + baseDir, e);
        }

        return result;
    }
}
