package de.augmentia.quad.core.capability.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YamlPromptManager implements PromptManager {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{(doc\\([^)]+\\)|tools|skills|task|state)\\}");

    private final Map<String, String> prompts = new HashMap<>();

    public YamlPromptManager(String classpathResource) {
        this(classpathResource, null);
    }

    public YamlPromptManager(String classpathResource, Path overrideDir) {
        loadFromClasspath(classpathResource);
        if (overrideDir != null && Files.isDirectory(overrideDir)) {
            loadFromDirectory(overrideDir);
        }
    }

    public YamlPromptManager(Path yamlFile) {
        this(yamlFile, null);
    }

    public YamlPromptManager(Path yamlFile, Path overrideDir) {
        loadFromFile(yamlFile);
        if (overrideDir != null && Files.isDirectory(overrideDir)) {
            loadFromDirectory(overrideDir);
        }
    }

    public YamlPromptManager(Path directory, boolean isDirectory) {
        if (Files.isDirectory(directory)) {
            loadFromDirectory(directory);
        } else {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }
    }

    private void loadFromClasspath(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Prompt resource not found: " + resource);
            }
            mergeYaml(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load prompts from classpath: " + resource, e);
        }
    }

    private void loadFromFile(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Prompt file not found: " + path);
        }
        try (InputStream in = Files.newInputStream(path)) {
            mergeYaml(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load prompts from file: " + path, e);
        }
    }

    private void loadFromDirectory(Path dir) {
        try (var files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml"))
                .sorted()
                .forEach(this::loadFromFile);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load prompts from directory: " + dir, e);
        }
    }

    private void mergeYaml(InputStream in) throws Exception {
        JsonNode root = YAML_MAPPER.readTree(in);
        JsonNode promptsNode = root.get("prompts");
        if (promptsNode != null && promptsNode.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = promptsNode.fields(); it.hasNext();) {
                var entry = it.next();
                String key = entry.getKey();
                String value = entry.getValue().asText();
                prompts.put(key, value);
            }
        }
    }

    @Override
    public String get(String key, Object... args) {
        String template = prompts.get(key);
        if (template == null) {
            return null;
        }
        if (args.length == 0) {
            return template;
        }
        return String.format(template, args);
    }

    public String renderWithPlaceholders(String key, Map<String, String> values) {
        String template = prompts.get(key);
        if (template == null) {
            template = key;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = values.getOrDefault(placeholder, "{" + placeholder + "}");
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public int size() {
        return prompts.size();
    }
}
