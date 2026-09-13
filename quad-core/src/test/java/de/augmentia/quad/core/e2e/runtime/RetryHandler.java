package de.augmentia.quad.e2e.runtime;

import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class RetryHandler {
    private static final Logger log = Logger.getLogger(RetryHandler.class);
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);

    public interface Retryable<T> {
        T execute() throws Exception;
    }

    public <T> T executeWithRetry(Retryable<T> operation, String operationName) {
        Exception lastException = null;
        Duration backoff = INITIAL_BACKOFF;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return operation.execute();
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    log.warn("Attempt " + attempt + " failed for " + operationName + ", retrying in " + backoff.getSeconds() + "s: " + e.getMessage());
                    
                    try {
                        Thread.sleep(backoff.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                    
                    backoff = Duration.ofMillis((long) (backoff.toMillis() * 2.5));
                }
            }
        }

        throw new RuntimeException("Failed after " + MAX_RETRIES + " attempts for " + operationName, lastException);
    }

    public <T> CompletableFuture<T> executeAsync(Retryable<T> operation, String operationName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeWithRetry(operation, operationName);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static boolean isRecoverable(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("timeout") || 
               msg.contains("rate limit") || 
               msg.contains("500") || 
               msg.contains("503") ||
               msg.contains("connection") ||
               msg.contains("network");
    }
}