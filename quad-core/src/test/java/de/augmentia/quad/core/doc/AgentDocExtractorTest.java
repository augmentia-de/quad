package de.augmentia.quad.core.doc;

import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.capability.doc.AgentDocExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDocExtractorTest {

    static class TestAgent {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Tool(description = "Multipliziert zwei Zahlen")
        public int multiply(int a, int b) {
            return a * b;
        }

        @Tool("Berechnet die Summe")
        public int sum(int a, int b) {
            return a + b;
        }

        @Override
        public String toString() {
            return "TestAgent";
        }
    }

    static class Lc4jAgent {
        @dev.langchain4j.agent.tool.Tool("Holt eine URL")
        public String fetch(String url) {
            return "";
        }
    }

    @Test
    void shouldDocumentClassAndToolMethods() {
        String doc = AgentDocExtractor.doc(TestAgent.class);

        assertThat(doc).contains("# Agent Capabilities");
        assertThat(doc).contains("## TestAgent");
        assertThat(doc).contains("- `multiply(int, int)` - Multipliziert zwei Zahlen");
        assertThat(doc).contains("- `sum(int, int)` - Berechnet die Summe");
    }

    @Test
    void shouldExcludeGettersSettersAndToString() {
        String doc = AgentDocExtractor.doc(TestAgent.class);

        assertThat(doc).doesNotContain("getName");
        assertThat(doc).doesNotContain("setName");
        assertThat(doc).doesNotContain("toString");
    }

    @Test
    void shouldSupportLangChain4jToolAnnotation() {
        String doc = AgentDocExtractor.doc(Lc4jAgent.class);

        assertThat(doc).contains("- `fetch(String)` - Holt eine URL");
    }

    @Test
    void shouldDocumentVarargsObjects() {
        String doc = AgentDocExtractor.doc(TestAgent.class, Lc4jAgent.class);

        assertThat(doc).contains("## TestAgent");
        assertThat(doc).contains("## Lc4jAgent");
    }

    @Test
    void shouldHandleNullArguments() {
        assertThat(AgentDocExtractor.doc((Object) null)).isEmpty();
    }
}
