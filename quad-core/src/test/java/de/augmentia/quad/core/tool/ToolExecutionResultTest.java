package de.augmentia.quad.core.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionResultTest {

    @Test
    void recordCreation() {
        ToolExecutionResult result = new ToolExecutionResult("tc-1", "search", "found it", false);
        assertEquals("tc-1", result.toolCallId());
        assertEquals("search", result.toolName());
        assertEquals("found it", result.result());
        assertFalse(result.isError());
    }

    @Test
    void errorResult() {
        ToolExecutionResult result = new ToolExecutionResult("tc-2", "fail", "timeout", true);
        assertTrue(result.isError());
        assertEquals("timeout", result.result());
    }
}
