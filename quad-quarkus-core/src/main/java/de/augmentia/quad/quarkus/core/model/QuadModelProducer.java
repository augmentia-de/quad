package de.augmentia.quad.quarkus.core.model;

import de.augmentia.quad.core.config.ModelTier;
import de.augmentia.quad.core.config.TieredModelConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Provides the model stack of the core as CDI beans.
 *
 * <p>Previously the app did this inline
 * ({@code new TieredChatModel(TieredModelConfig.fromEnv())}). The core provides the
 * configuration but no Arc binding — that is exactly the gap this layer
 * closes.
 *
 * <p>Intentionally {@link Singleton} instead of {@link ApplicationScoped}: normal scopes
 * create a client proxy that needs a no-arg constructor.
 * {@link TieredChatModel} has none, and Arc would fail the build with
 * "no default constructor".
 *
 * <p>No unqualified {@code ChatModel} is deliberately produced here: the
 * app injects {@code ChatModel} in several places. An additional
 * unqualified bean type would be an ambiguous dependency there.
 */
@ApplicationScoped
public class QuadModelProducer {

    private static final Logger log = Logger.getLogger(QuadModelProducer.class);

    static final String DEFAULT_TIER_PROPERTY = "quad.model.default-tier";

    /**
     * Builds the tier configuration from the environment. {@code TieredModelConfig.fromEnv()}
     * reads the variables with the prefixes OPENAI, LLM, SIMPLE and ADVANCED.
     * {@code quad.model.default-tier} overrides only the choice of the primary tier, so that
     * it can also be set from application.properties instead of only from the env.
     */
    @Produces
    @Singleton
    public TieredModelConfig tieredModelConfig() {
        TieredModelConfig fromEnv = TieredModelConfig.fromEnv();
        return ConfigProvider.getConfig()
            .getOptionalValue(DEFAULT_TIER_PROPERTY, String.class)
            .map(raw -> {
                var tier = ModelTier.fromString(raw);
                log.infof("quad.model.default-tier=%s overrides LLM_DEFAULT_TIER=%s", tier, fromEnv.defaultTier());
                return new TieredModelConfig(fromEnv.simple(), fromEnv.advanced(), tier);
            })
            .orElse(fromEnv);
    }

    /** A single TieredChatModel per container; the models themselves are created lazily per tier. */
    @Produces
    @Singleton
    public TieredChatModel tieredChatModel(TieredModelConfig config) {
        return new TieredChatModel(config);
    }
}