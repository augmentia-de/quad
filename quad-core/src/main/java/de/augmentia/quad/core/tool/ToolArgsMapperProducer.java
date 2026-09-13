package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class ToolArgsMapperProducer {

    @Produces
    @ApplicationScoped
    public ToolArgsMapper produceToolArgsMapper(ObjectMapper objectMapper) {
        return new ToolArgsMapper(objectMapper);
    }
}