package de.augmentia.quad.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Template rendering in {@link PromptAssembler} (TODO2 B1).
 *
 * <p>The engine renders a workflow node's {@code userMessageTemplate} from the predecessor
 * outputs and hands the result to {@code initialMessages}. This class covers the second rendering
 * stage, which must leave an already-rendered plain-text prompt alone and only substitute when the
 * prompt really is a JSON document (the start-envelope case).
 */
class PromptAssemblerTemplateTest {

    private static final String TEMPLATE =
            "Step: {{architect.output}}\nBewerte: {{reviewer.output}}";

    @Test
    void engineRenderedValueSurvivesTheSecondRendering() {
        // What AgentNodeExecutor:82 passes as "prompt2": the placeholder is resolved.
        var prompt2 = "Step: PLAN-ABC\nBewerte: {{reviewer.output}}";

        var finalUserMessage = PromptAssembler.renderUserMessageTemplate(prompt2, TEMPLATE);

        assertThat(finalUserMessage)
                .as("TODO2 B1 fixed: a non-JSON prompt is already the finished prompt "
                        + "and must not be replaced by the template")
                .isEqualTo(prompt2);
    }

    @Test
    void plainTextInputIsPassedThroughUnchanged() {
        var plainText = "Step: PLAN-ABC\nEvaluate: still open";
        assertThat(PromptAssembler.renderUserMessageTemplate(plainText, TEMPLATE)).isEqualTo(plainText);
    }

    @Test
    void jsonInputResolvesPlaceholdersFromJsonFields() {
        // The start-envelope case: inputJson really is JSON, and substitution must work.
        var json = "{\"architect.output\":\"PLAN-ABC\"}";
        var rendered = PromptAssembler.renderUserMessageTemplate(json, TEMPLATE);

        assertThat(rendered).contains("PLAN-ABC");
    }

    @Test
    void nonTextualJsonValuesAreRenderedAsJson() {
        var json = "{\"reviewer.output\":{\"ok\":true}}";
        var rendered = PromptAssembler.renderUserMessageTemplate(json, TEMPLATE);

        assertThat(rendered).contains("\"ok\":true");
    }

    @Test
    void jsonArrayInputIsNotTreatedAsASubstitutionSource() {
        var json = "[1,2,3]";
        var rendered = PromptAssembler.renderUserMessageTemplate(json, TEMPLATE);

        assertThat(rendered)
                .as("an array carries no field names, so nothing is substituted")
                .isEqualTo(json);
    }

    @Test
    void blankInputReturnsTheTemplate() {
        assertThat(PromptAssembler.renderUserMessageTemplate("", TEMPLATE)).isEqualTo(TEMPLATE);
        assertThat(PromptAssembler.renderUserMessageTemplate(null, TEMPLATE)).isEqualTo(TEMPLATE);
    }

    @Test
    void blankTemplateReturnsTheInputUnchanged() {
        var json = "{\"a\":1}";
        assertThat(PromptAssembler.renderUserMessageTemplate(json, null)).isEqualTo(json);
        assertThat(PromptAssembler.renderUserMessageTemplate(json, "")).isEqualTo(json);
    }

    @Test
    void inputPlaceholderIsSubstitutedAfterTheFieldPass() {
        var json = "{\"task\":\"baue mir X\"}";
        var rendered = PromptAssembler.renderUserMessageTemplate(json, "Task: {{input}}");

        assertThat(rendered)
                .as("TODO2 B1 fixed: {{input}} is substituted after the field pass, so that an "
                        + "JSON field 'input' cannot mask the placeholder")
                .contains("baue mir X");
    }

    @Test
    void explicitInputFieldDoesNotShadowTheInputPlaceholder() {
        var json = "{\"input\":\"FIELDS\",\"task\":\"X\"}";
        var rendered = PromptAssembler.renderUserMessageTemplate(json, "Vorher {{input}}");

        assertThat(rendered)
                .as("{{input}} means the whole document, even when a field 'input' exists")
                .contains("FIELDS")
                .contains("X");
    }

    @Test
    void unresolvedPlaceholdersAreLeftVisible() {
        var json = "{\"architect.output\":\"PLAN-ABC\"}";
        var rendered = PromptAssembler.renderUserMessageTemplate(json, TEMPLATE);

        assertThat(rendered)
                .as("a field not present in the JSON remains visible as a placeholder, "
                        + "so that the gap is visible")
                .contains("PLAN-ABC")
                .contains("{{reviewer.output}}");
    }
}