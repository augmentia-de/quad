package de.augmentia.quad.e2e.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import de.augmentia.quad.core.config.ModelFactory;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

public class LlamaEvaluator {
    private static final Logger log = Logger.getLogger(LlamaEvaluator.class);
    private final ChatModel chatModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlamaEvaluator() {
        this.chatModel = ModelFactory.createOpenAiFromEnv();
    }

    public EvaluationResult evaluate(String task, String expected, String actual, Criteria... criteria) {
        String prompt = buildEvaluationPrompt(task, expected, actual);
        
        long start = System.currentTimeMillis();
        String response = chatModel.chat(prompt);
        long latency = System.currentTimeMillis() - start;

        try {
            Map<String, Object> result = mapper.readValue(response, Map.class);
            int score = ((Number) result.getOrDefault("score", 0)).intValue();
            String feedback = (String) result.getOrDefault("feedback", "No feedback");
            List<String> issues = (List<String>) result.getOrDefault("issues", List.of());

            return new EvaluationResult(score, feedback, issues, latency, response);
        } catch (Exception e) {
            log.error("Failed to parse evaluation result, treating as low score", e);
            return new EvaluationResult(1, "Parse error: " + e.getMessage(), List.of("evaluation_parse_error"), latency, response);
        }
    }

    private String buildEvaluationPrompt(String task, String expected, String actual) {
        return """
            Evaluate the agent response against the expected outcome.
            
            Task: %s
            Expected: %s
            Actual: %s
            
            Scoring Criteria (1-5 scale):
            1 = Completely wrong or failed
            2 = Partially correct but major issues
            3 = Acceptable but could be better
            4 = Good, meets most criteria
            5 = Excellent, exceeds expectations
            
            Output strict JSON: {"score": N, "feedback": "summary", "issues": ["list of problems"]}
            Do NOT include any other text.
            """.formatted(task, expected, actual);
    }

    public record Criteria(String name, boolean required) {}
}