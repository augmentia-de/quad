package de.augmentia.quad.examples.quickstart;

import de.augmentia.quad.core.agent.Agents;

/**
 * Schritt 1 von 5 — Der einfachste Agent.
 *
 * <p>Zeigt: einen sofort einsatzfähigen Agenten ohne eigene Klasse und ohne Konfiguration.
 * Das Modell kommt aus der Umgebung ({@code OPENAI_API_KEY}, optional {@code OPENAI_MODEL}).</p>
 *
 * <p><b>Neu in diesem Schritt:</b> der Einstieg selbst. {@link Agents#simple()} verdrahtet
 * LLM, Logging, Tool-Registry, Event-Publisher und Hook-Registry und ist damit dieselbe
 * Basis, auf der die folgenden Schritte aufbauen.</p>
 *
 * <p>Ausführen: {@code mvn -pl quad-examples -am compile} und dann
 * {@code java -cp ... de.augmentia.quad.examples.quickstart.Step1_MinimalAgent}
 * (oder über die IDE). Benötigt {@code OPENAI_API_KEY}.</p>
 */
public final class Step1_MinimalAgent {

    private Step1_MinimalAgent() {
    }

    public static void main(String[] args) {
        String answer = Agents.simple()
                .run("Was ist ein KI-Agent? Antworte in zwei Sätzen.");
        System.out.println(answer);
    }
}
