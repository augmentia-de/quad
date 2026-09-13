package de.augmentia.quad.quarkus.agent.config;

import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.config.ModelTier;
import de.augmentia.quad.core.config.TieredModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatModel wrapper with tier failover:
 * Primary tier (larger model) fails → automatic
 * fallback to the other tier (e.g. gpt-4o → gpt-4o-mini).
 * Models are created lazily per tier and cached.
 */
public class TieredChatModel implements ChatModel {

    private static final Logger log = Logger.getLogger(TieredChatModel.class);

    /** Creates the ChatModel for a tier — swappable for testing */
    @FunctionalInterface
    public interface ModelResolver {
        ChatModel resolve(ModelTier tier);
    }

    private final ModelResolver resolver;
    private final ModelTier primary;
    private final ModelTier fallback;
    private final Map<ModelTier, ChatModel> models = new ConcurrentHashMap<>();

    public TieredChatModel(TieredModelConfig config) {
        this(config, tier -> ModelFactory.createChatModel(tier, config));
    }

    public TieredChatModel(TieredModelConfig config, ModelResolver resolver) {
        this.resolver = resolver;
        this.primary = normalized(config.defaultTier());
        this.fallback = primary == ModelTier.ADVANCED ? ModelTier.SIMPLE : ModelTier.ADVANCED;
    }

    private static ModelTier normalized(ModelTier tier) {
        // ROUTING starts with SIMPLE (analysis) and escalates to ADVANCED if needed
        return tier == ModelTier.ADVANCED ? ModelTier.ADVANCED : ModelTier.SIMPLE;
    }

    public ModelTier primaryTier() { return primary; }

    public ModelTier fallbackTier() { return fallback; }

    public ChatModel model(ModelTier tier) {
        return models.computeIfAbsent(tier, resolver::resolve);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        try {
            return model(primary).chat(request);
        } catch (Exception e) {
            log.warnf("Tier '%s' failed (%s) — failing over to tier '%s'",
                primary, e.getMessage(), fallback);
            return model(fallback).chat(request);
        }
    }
}