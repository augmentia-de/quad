package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.inject.Vetoed;

@Vetoed
public class ToolArgsMapper {

    private final ObjectMapper objectMapper;

    public ToolArgsMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}