package de.augmentia.quad.core.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * ChatModel wrapper with automatic retry and exponential backoff.
 * <p>
 * Source: Python {@code src/quad/unifiedllm/retry.py}
 */
public class RetryingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final int maxRetries;
    private final long initialDelayMs;
    private final double backoffFactor;

    public RetryingChatModel(ChatModel delegate, int maxRetries, long initialDelayMs, double backoffFactor) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (initialDelayMs < 0) {
            throw new IllegalArgumentException("initialDelayMs must be >= 0");
        }
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
        this.backoffFactor = backoffFactor;
    }

    public static RetryingChatModel withDefaults(ChatModel delegate) {
        return new RetryingChatModel(delegate, 3, 1000, 2.0);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int attempt = 0;
        long delay = initialDelayMs;
        while (true) {
            try {
                return delegate.chat(request);
            } catch (Exception e) {
                if (++attempt > maxRetries) {
                    throw new IllegalStateException(
                            "LLM retries exhausted after " + maxRetries + " attempts", e);
                }
                if (delay > 0) {
                    sleep(delay);
                }
                delay = (long) (delay * backoffFactor);
            }
        }
    }

    public ChatModel delegate() {
        return delegate;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during retry", ie);
        }
    }
}
