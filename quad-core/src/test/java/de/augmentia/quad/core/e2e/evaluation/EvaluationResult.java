package de.augmentia.quad.e2e.evaluation;

import java.util.List;

public record EvaluationResult(int score, String feedback, List<String> issues, long latencyMs, String rawResponse) {}