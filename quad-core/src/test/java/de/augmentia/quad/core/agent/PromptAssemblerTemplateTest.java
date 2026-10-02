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
                .as("TODO2 B1 behoben: ein Nicht-JSON-Prompt ist bereits der fertige Prompt "
                        + "und darf nicht durch das Template ersetzt werden")
                .isEqualTo(prompt2);
    }

    @Test
    void plainTextInputIsPassedThroughUnchanged() {
        var plainText = "Step: PLAN-ABC\nBewerte: noch offen";
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
                .as("ein Array traegt keine Feldnamen, also wird nichts substituiert")
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
                .as("TODO2 B1 behoben: {{input}} wird nach dem Feld-Durchlauf ersetzt, damit ein "
                        + "JSON-Feld 'input' den Platzhalter nicht verdecken kann")
                .contains("baue mir X");
    }

    @Test
    void explicitInputFieldDoesNotShadowTheInputPlaceholder() {
        var json = "{\"input\":\"FELDER\",\"task\":\"X\"}";
        var rendered = PromptAssembler.renderUserMessageTemplate(json, "Vorher {{input}}");

        assertThat(rendered)
                .as("{{input}} meint das ganze Dokument, auch wenn ein Feld 'input' existiert")
                .contains("FELDER")
                .contains("X");
    }

    @Test
    void unresolvedPlaceholdersAreLeftVisible() {
        var json = "{\"architect.output\":\"PLAN-ABC\"}";
        var rendered = PromptAssembler.renderUserMessageTemplate(json, TEMPLATE);

        assertThat(rendered)
                .as("ein im JSON nicht vorhandenes Feld bleibt als Platzhalter sichtbar, "
                        + "damit der Lueckenschluss sichtbar ist")
                .contains("PLAN-ABC")
                .contains("{{reviewer.output}}");
    }
}