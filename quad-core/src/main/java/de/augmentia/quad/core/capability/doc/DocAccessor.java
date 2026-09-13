package de.augmentia.quad.core.capability.doc;

/**
 * Delegates to {@link AgentDocExtractor}: generates LLM-readable
 * agent capability descriptions from class/object reflection.
 */
public class DocAccessor {

    public String doc(Class<?> agentClass) {
        return AgentDocExtractor.doc(agentClass);
    }

    public String doc(Object... objects) {
        return AgentDocExtractor.doc(objects);
    }
}
