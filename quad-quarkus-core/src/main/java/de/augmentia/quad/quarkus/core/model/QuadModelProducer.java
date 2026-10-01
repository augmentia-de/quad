package de.augmentia.quad.quarkus.core.model;

import de.augmentia.quad.core.config.ModelTier;
import de.augmentia.quad.core.config.TieredModelConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Stellt den Modell-Stack des Kerns als CDI-Beans bereit.
 *
 * <p>Bisher hat die App das inline getan
 * ({@code new TieredChatModel(TieredModelConfig.fromEnv())}). Der Kern liefert die
 * Konfiguration, aber kein Arc-Binding — genau das ist die Lücke, die diese Schicht
 * schließt.
 *
 * <p>Absichtlich {@link Singleton} statt {@link ApplicationScoped}: normale Scopes
 * erzeugen einen Client-Proxy, der einen No-Arg-Konstruktor braucht.
 * {@link TieredChatModel} hat keinen, Arc würde den Build mit
 * "no default constructor" abbrechen.
 *
 * <p>Hier wird bewusst kein unqualifizierter {@code ChatModel} produziert: die
 * App injiziert an mehreren Stellen {@code ChatModel}. Ein zusätzlicher
 * unqualifizierter Bean-Typ wäre dort eine mehrdeutige Abhängigkeit.
 */
@ApplicationScoped
public class QuadModelProducer {

    private static final Logger log = Logger.getLogger(QuadModelProducer.class);

    static final String DEFAULT_TIER_PROPERTY = "quad.model.default-tier";

    /**
     * Baut die Tier-Konfiguration aus der Umgebung. {@code TieredModelConfig.fromEnv()}
     * liest die Variablen mit den Präfixen OPENAI, LLM, SIMPLE und ADVANCED.
     * {@code quad.model.default-tier} überschreibt nur die Wahl des Primär-Tiers, damit
     * sie auch aus application.properties statt nur aus der Env gesetzt werden kann.
     */
    @Produces
    @Singleton
    public TieredModelConfig tieredModelConfig() {
        TieredModelConfig fromEnv = TieredModelConfig.fromEnv();
        return ConfigProvider.getConfig()
            .getOptionalValue(DEFAULT_TIER_PROPERTY, String.class)
            .map(raw -> {
                var tier = ModelTier.fromString(raw);
                log.infof("quad.model.default-tier=%s überschreibt LLM_DEFAULT_TIER=%s", tier, fromEnv.defaultTier());
                return new TieredModelConfig(fromEnv.simple(), fromEnv.advanced(), tier);
            })
            .orElse(fromEnv);
    }

    /** Ein einziger TieredChatModel pro Container; die Modelle selbst werden pro Tier lazy erzeugt. */
    @Produces
    @Singleton
    public TieredChatModel tieredChatModel(TieredModelConfig config) {
        return new TieredChatModel(config);
    }
}