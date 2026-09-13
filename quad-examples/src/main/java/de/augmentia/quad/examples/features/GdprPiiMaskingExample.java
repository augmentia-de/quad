package de.augmentia.quad.examples.features;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.gdpr.PiiAnonymizerHook;
import de.augmentia.quad.core.session.AgentSessionState;

import java.util.Set;

/**
 * Feature 8: GDPR &amp; PII-Maskierung (PiiAnonymizerHook / AuditTrailHook)
 *
 * Ein PrivacyAgent, der sensible Personendaten wie E-Mail-Adressen
 * und Telefonnummern automatisch maskiert.
 */
class GdprPiiMaskingExample extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Speichert Kundendaten")
    public String saveCustomer(String data) {
        return "Gespeichert: " + data;
    }

    public static void main(String[] args) {
        GdprPiiMaskingExample agent = AgentBuilder.create(GdprPiiMaskingExample.class)
            .withLlmFromEnv()
            .build();

        agent.addHook(new PiiAnonymizerHook(
            Set.of(PiiAnonymizerHook.MaskType.EMAIL, PiiAnonymizerHook.MaskType.PHONE_NUMBER),
            PiiAnonymizerHook.BlockAction.REDACT,
            "[REDACTED]"
        ));

        String result = agent.executeReAct(
            "Speichere folgende Daten: max.mustermann@example.de, Telefon +49 30 12345678"
        );
        System.out.println(result);
    }
}
