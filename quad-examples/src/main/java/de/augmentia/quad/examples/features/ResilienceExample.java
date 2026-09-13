package de.augmentia.quad.examples.features;

import de.augmentia.quad.core.resilience.Retry;
import de.augmentia.quad.core.resilience.RetryConfig;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Feature 6: Resilience &amp; Fault Tolerance (Retry)
 *
 * A ResilientToolAgent that protects error-prone tool calls
 * with exponential backoff.
 */
class ResilienceExample {

    private final AtomicInteger attemptCounter = new AtomicInteger(0);

    public String executeUnstableTask() throws Exception {
        return Retry.run(() -> {
            int attempt = attemptCounter.incrementAndGet();
            System.out.println("  Versuch " + attempt + "...");

            if (attempt < 3) {
                throw new RuntimeException("Temporary API call failure");
            }
            return "Erfolg nach " + attempt + " Versuchen";
        }, RetryConfig.DEFAULT);
    }

    public static void main(String[] args) throws Exception {
        ResilienceExample agent = new ResilienceExample();

        System.out.println("=== Resilience Demo ===");
        String result = agent.executeUnstableTask();
        System.out.println("Ergebnis: " + result);

        System.out.println("\n=== Mit custom RetryConfig (5 Versuche, 500ms Backoff) ===");
        ResilienceExample agent2 = new ResilienceExample();
        RetryConfig customConfig = new RetryConfig(5, 500, 2.0);
        String result2 = Retry.run(() -> {
            int attempt = agent2.attemptCounter.incrementAndGet();
            if (attempt < 4) {
                throw new RuntimeException("Fehler #" + attempt);
            }
            return "Erfolg nach " + attempt + " Versuchen";
        }, customConfig);
        System.out.println("Ergebnis: " + result2);
    }
}
