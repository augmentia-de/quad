package de.augmentia.quad.quarkus.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowConditionTest {

    @Test
    void alwaysAndTrueMatchAnything() {
        assertTrue(WorkflowCondition.matches("always", "any text", 0));
        assertTrue(WorkflowCondition.matches("true", "", 7));
    }

    @Test
    void neverAndFalseNeverMatch() {
        assertFalse(WorkflowCondition.matches("never", "any text", 0));
        assertFalse(WorkflowCondition.matches("false", "any text", 1));
    }

    @Test
    void containsIsCaseInsensitive() {
        assertTrue(WorkflowCondition.matches("contains:done", "The build is DONE here", 1));
        assertFalse(WorkflowCondition.matches("contains:missing", "The build is DONE here", 1));
    }

    @Test
    void notcontainsNegates() {
        assertTrue(WorkflowCondition.matches("notcontains:error", "all good", 0));
        assertFalse(WorkflowCondition.matches("notcontains:error", "an error occurred", 0));
    }

    @Test
    void equalsComparesTrimmedText() {
        assertTrue(WorkflowCondition.matches("equals:pass", "  pass  ", 0));
        assertFalse(WorkflowCondition.matches("equals:fail", "  pass  ", 0));
    }

    @Test
    void matchesUsesRegex() {
        assertTrue(WorkflowCondition.matches("matches:iterations=\\d+", "Loop iterations=3", 0));
        assertFalse(WorkflowCondition.matches("matches:iterations=\\d+", "Loop iterations=none", 0));
        assertFalse(WorkflowCondition.matches("matches:[invalid", "anything", 0));
    }

    @Test
    void iterExpressionsCompareIteration() {
        assertTrue(WorkflowCondition.matches("iter==2", "", 2));
        assertFalse(WorkflowCondition.matches("iter==2", "", 3));
        assertTrue(WorkflowCondition.matches("iter!=2", "", 3));
        assertTrue(WorkflowCondition.matches("iter>2", "", 3));
        assertTrue(WorkflowCondition.matches("iter>=2", "", 2));
        assertTrue(WorkflowCondition.matches("iter<3", "", 2));
        assertTrue(WorkflowCondition.matches("iter<=3", "", 3));
    }

    @Test
    void emptyOrBlankOrUnknownExpressionsAreFalse() {
        assertFalse(WorkflowCondition.matches("", "text", 0));
        assertFalse(WorkflowCondition.matches(null, "text", 0));
        assertFalse(WorkflowCondition.matches("   ", "text", 0));
        assertFalse(WorkflowCondition.matches("wibble", "text", 0));
        assertFalse(WorkflowCondition.matches("empty", "text", 0));
    }
}