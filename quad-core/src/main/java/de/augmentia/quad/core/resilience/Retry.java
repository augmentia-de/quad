package de.augmentia.quad.core.resilience;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

public class Retry {

    private static final Predicate<Exception> DEFAULT_RETRYABLE = e ->
        !(e instanceof IllegalArgumentException)
            && !(e instanceof IllegalStateException)
            && e.getMessage() != null
            && !e.getMessage().contains("authentication")
            && !e.getMessage().contains("401")
            && !e.getMessage().contains("403");

    public static <T> T run(Callable<T> callable, RetryConfig config) throws Exception {
        return run(callable, config, DEFAULT_RETRYABLE);
    }

    public static <T> T run(Callable<T> callable, RetryConfig config,
                            Predicate<Exception> retryableCheck) throws Exception {
        Exception lastException = null;
        long delay = config.backoffDelayMs();

        for (int i = 0; i < config.maxAttempts(); i++) {
            try {
                return callable.call();
            } catch (Exception e) {
                lastException = e;
                if (!retryableCheck.test(e) || i == config.maxAttempts() - 1) {
                    throw e;
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                delay = (long) (delay * config.backoffMultiplier());
            }
        }
        throw lastException;
    }

    private Retry() {}
}
