package de.augmentia.quad.examples.quickstart;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.agent.AgentResult;
import de.augmentia.quad.core.session.AgentSessionState;

/**
 * Schritt 4 von 5 — Typsichere Ausgabe (Structured Output).
 *
 * <p>Zeigt: ein Java-{@code record} als Ausgabeformat. LangChain4j erzeugt daraus das
 * JSON-Schema, der Agent liefert {@code executeStructured(...)} und man erhält die
 * strukturierte Antwort als {@link AgentResult}.</p>
 *
 * <p><b>Neu gegenüber Schritt 3:</b> kein Freitext, sondern ein definierter Rückgabetyp.
 * Alternativ zu einer Klasse lässt sich das Schema dynamisch setzen
 * ({@code withStructuredOutputSchema(json)}).</p>
 */
public final class Step4_StructuredOutput {

    private Step4_StructuredOutput() {
    }

    /** Ausgabetyp — daraus wird automatisch das JSON-Schema generiert. */
    public record MovieReview(String title, int year, double rating, String summary) {
    }

    /** Minimaler Agent — die Struktur wird beim Bauen übergeben, nicht in der Klasse. */
    public static class ReviewerAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    public static void main(String[] args) {
        Agent agent = AgentBuilder.create(ReviewerAgent.class)
                .withLlmFromEnv()
                .withSystemPrompt("Du bist Filmkritiker und antwortest strukturiert.")
                .withStructuredOutput(MovieReview.class)
                .build();

        AgentResult result = agent.executeStructured("Bewerte den Film 'Inception' (2010).");

        System.out.println("Antwort:      " + result.finalAnswer());
        System.out.println("Strukturiert: " + result.structuredOutput());
    }
}
