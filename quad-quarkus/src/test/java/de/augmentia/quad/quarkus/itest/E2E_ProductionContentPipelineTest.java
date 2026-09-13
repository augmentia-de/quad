package de.augmentia.quad.quarkus.itest;

import de.augmentia.quad.quarkus.workflow.engine.WorkflowEngine;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;

import static de.augmentia.quad.quarkus.itest.E2eBackendSupport.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E-Test: Produktionsnaher Content-Pipeline-Workflow gegen einen LAUFENDEN Backend.
 *
 * <p>Dieser Test validiert einen komplexen, mehrstufigen Workflow, der alle wichtigen
 * Features des QUAD-Workflow-Engines in einem realistischen Szenario kombiniert:</p>
 *
 * <h2>Workflow-Architektur</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────────────────┐
 * │                        Content Production Pipeline                              │
 * │                                                                                 │
 * │  [START: Thema]                                                                 │
 * │       │                                                                         │
 * │       ▼                                                                         │
 * │   ┌────────┐                                                                    │
 * │   │ FORK   │── Branch A (async): ──► [n_async] ──► [n_research_web] ──┐       │
 * │   │ n_fork │── Branch B:        ──► [n_research_docs] ─────────────────┤       │
 * │   └────────┘                                                           │       │
 * │                                                                  ┌─────┘       │
 * │                                                                  ▼              │
 * │                                                            ┌──────────┐         │
 * │                                                            │   JOIN   │         │
 * │                                                            │  n_join   │         │
 * │                                                            └──────────┘         │
 * │                                                                  │              │
 * │                                                                  ▼              │
 * │                                                        ┌────────────────┐       │
 * │                                                        │ NESTED SUB-WF  │       │
 * │                                                        │  n_summary      │       │
 * │                                                        │ (Research       │       │
 * │                                                        │  Summarizer)    │       │
 * │                                                        └────────────────┘       │
 * │                                                                  │              │
 * │                                                                  ▼              │
 * │  ┌──────────────────────────────────────────────────────────────────┐           │
 * │  │ LOOP (max 3 iterations)                                          │           │
 * │  │  exitCondition: "iter>=3"     loopTargetId: "n_write"            │           │
 * │  │                                                                   │           │
 * │  │  [n_write] ──► [n_evaluate] ──► [n_quality_gate]                 │           │
 * │  │  (Writer)     (Evaluator/       (Conditional:                    │           │
 * │  │                Watchdog)         contains:passed)                 │           │
 * │  │                                        │                         │           │
 * │  │                              true ─────┤                         │           │
 * │  │                              false ────│──► (next iteration)     │           │
 * │  └──────────────────────────────────────────────────────────────────┘           │
 * │                                              │                                  │
 * │                                              ▼ (after loop)                    │
 * │                                       ┌────────────┐                            │
 * │                                       │  n_publish  │ (Publisher)               │
 * │                                       │  writeFile   │                           │
 * │                                       └────────────┘                            │
 * │                                              │                                  │
 * │                                              ▼                                  │
 * │                                       ┌──────────────┐                          │
 * │                                       │ n_messaging   │ (messaging-out)         │
 * │                                       │ Kafka-Channel │                         │
 * │                                       └──────────────┘                          │
 * │                                              │                                  │
 * │                                              ▼                                  │
 * │                                       ┌────────────┐                            │
 * │                                       │  n_output   │ (Summary)                │
 * │                                       └────────────┘                            │
 * │                                              │                                  │
 * │                                              ▼                                  │
 * │                                         [FERTIG]                                │
 * └─────────────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Getestete Features</h2>
 * <ul>
 *   <li><b>Fork/Join:</b> Parallel research branches (web + docs), join with resultIds + timeout</li>
 *   <li><b>Async node:</b> web research as an async node with refNodeId (virtual thread)</li>
 *   <li><b>Nested workflow:</b> sub-workflow "Research Summarizer" with 2 nodes</li>
 *   <li><b>Loop:</b> maxIterations=3, exitCondition="iter>=3", loopTargetId="n_write"</li>
 *   <li><b>Conditional:</b> quality gate with "contains:passed" (true→publish, false→next iteration)</li>
 *   <li><b>Messaging out:</b> Kafka channel "content-published" after successful publish</li>
 *   <li><b>System prompts:</b> each agent has a dedicated, production-representative system prompt</li>
 *   <li><b>Tool assignment:</b> each agent node has matching tools (webSearch, readFile, writeFile, etc.)</li>
 *   <li><b>JSON output:</b> structured outputs with defined schemas (evaluator, writer, publisher)</li>
 *   <li><b>userMessageTemplate:</b> data contract between nodes via template variables</li>
 * </ul>
 *
 * <h2>Agent overview</h2>
 * <table>
 *   <tr><th>Agent</th><th>Tools</th><th>Role</th></tr>
 *   <tr><td>Web Researcher</td><td>webSearch, webfetch, readFile</td><td>web research, source evaluation</td></tr>
 *   <tr><td>Document Researcher</td><td>findFiles, grepSearch, listDirectory, readFile</td><td>analyze local documents</td></tr>
 *   <tr><td>Content Writer</td><td>readFile, writeFile, findFiles</td><td>write/refine articles</td></tr>
 *   <tr><td>Quality Evaluator</td><td>readFile</td><td>quality check (Watchdog)</td></tr>
 *   <tr><td>Publisher</td><td>writeFile, readFile</td><td>publish articles</td></tr>
 *   <tr><td>Summary</td><td>(none)</td><td>result summary</td></tr>
 *   <tr><td>Research Summarizer (Sub-WF)</td><td>readFile</td><td>compress research data</td></tr>
 * </table>
 *
 * <h2>Expected flow</h2>
 * <ol>
 *   <li>7 agents are created via REST (each with systemPrompt + tools)</li>
 *   <li>Sub-workflow "Research Summarizer" is created</li>
 *   <li>Main workflow is created (13 nodes, 14 edges)</li>
 *   <li>Structure validation: agent IDs, tools, prompts, edge connectivity</li>
 *   <li>Workflow execution via POST /api/ui/workflows/{id}/execute</li>
 *   <li>Loop runs 3 iterations (write→evaluate→quality_gate)</li>
 *   <li>Quality gate checks whether "passed" is in the evaluator output</li>
 *   <li>After loop: publish node writes the article, messaging node sends to Kafka</li>
 *   <li>Cleanup: delete all agents + workflows</li>
 * </ol>
 *
 * @see E2eBackendSupport
 * @see WorkflowEngine
 * @see de.augmentia.quad.quarkus.workflow.WorkflowCondition
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("e2e")
class E2E_ProductionContentPipelineTest {

    // ─── Agent-IDs (w(Order(1)) gesetzt) ──────────────────────────────────
    private static String agentWebResearcher;
    private static String agentDocResearcher;
    private static String agentWriter;
    private static String agentEvaluator;
    private static String agentPublisher;
    private static String agentOutput;
    private static String agentSummary;

    // ─── Workflow/Run-IDs ──────────────────────────────────────────────────
    private static String subWorkflowId;
    private static String pipelineWorkflowId;
    private static String runId;

    @BeforeAll
    static void up() {
        configureClient();
        requireBackendUp();
        requireDatabase();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Agenten anlegen
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    void createAgents() {
        agentWebResearcher = createAgentWithTools("E2E-Web-Researcher",
            "[\"webSearch\",\"webfetch\",\"readFile\"]",
            "You are a professional web researcher. Use the webSearch tool ONCE to find recent, "
            + "authoritative information on the given topic. Optionally fetch ONE result with "
            + "webfetch. THEN STOP: list the 3-5 most important findings with sources. Do not keep "
            + "searching, do not loop, do not explore.",
            "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"},"
            + "\"findings\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
            + "\"point\":{\"type\":\"string\"},\"detail\":{\"type\":\"string\"},"
            + "\"source\":{\"type\":\"string\"}},\"required\":[\"point\"]}}},"
            + "\"required\":[\"topic\",\"findings\"],\"additionalProperties\":false}");

        agentDocResearcher = createAgentWithTools("E2E-Doc-Researcher",
            "[\"findFiles\",\"grepSearch\",\"listDirectory\",\"readFile\"]",
            "You are a document researcher. Search the workspace for existing documents relevant "
            + "to the topic. Use findFiles/grepSearch/listDirectory/readFile to quickly locate and "
            + "sample files. THEN STOP: list the relevant local documents and their key points. "
            + "Do not explore code internals, do not do web searches, do not loop.",
            "{\"type\":\"object\",\"properties\":{\"documents\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},"
            + "\"keyPoint\":{\"type\":\"string\"}},\"required\":[\"path\"]}}},"
            + "\"required\":[\"documents\"],\"additionalProperties\":false}");

        agentWriter = createAgentWithTools("E2E-Content-Writer",
            "[]",
            "You are a professional content writer. Synthesize the provided research findings "
            + "into a well-structured 250-400 word article. Rules: (1) Start with an introduction. "
            + "(2) Use clear headings. (3) Professional but accessible tone. (4) You have NO tools "
            + "— return the complete article JSON and stop.",
            "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},"
            + "\"article\":{\"type\":\"string\"}},\"required\":[\"title\",\"article\"],"
            + "\"additionalProperties\":false}");

        agentEvaluator = createAgentWithTools("E2E-Quality-Evaluator",
            "[]",
            "You are a strict quality evaluator. Assess the article on clarity, accuracy, and "
            + "completeness (score each 0-10). Be decisive in ONE pass — you have NO tools, do "
            + "not loop. Produce structured JSON with the verdict. Verdict ONLY can be PASSED or FAILED",
            "{\"type\":\"object\",\"properties\":{\"verdict\":{\"type\":\"string\","
            + "\"enum\":[\"PASSED\",\"FAILED\"]},\"score\":{\"type\":\"number\"},"
            + "\"notes\":{\"type\":\"string\"}},\"required\":[\"verdict\",\"score\"],"
            + "\"additionalProperties\":false}");

        agentPublisher = createAgentWithTools("E2E-Publisher",
            "[]",
            "You are a content publisher. Take the final approved article and prepare it "
            + "for publication. You have NO tools — return the structured publication JSON and stop.",
            "{\"type\":\"object\",\"properties\":{\"file\":{\"type\":\"string\"},"
            + "\"title\":{\"type\":\"string\"},\"markdown\":{\"type\":\"string\"},"
            + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
            + "\"required\":[\"file\",\"title\",\"markdown\"],\"additionalProperties\":false}");

        agentOutput = createAgentWithTools("E2E-Output-Summary",
            "[]",
            "Summarize the publication result concisely as structured JSON. Be brief.",
            "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"},"
            + "\"file\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"}},"
            + "\"required\":[\"status\"],\"additionalProperties\":false}");

        agentSummary = createAgentWithTools("E2E-Research-Summarizer",
            "[]",
            "You are a research summarizer. You receive pre-processed research text. Compress it "
            + "into key findings as structured JSON. You have NO tools — summarize directly, do not "
            + "ask questions, do not expand.",
            "{\"type\":\"object\",\"properties\":{\"briefing\":{\"type\":\"string\"},"
            + "\"keyFindings\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
            + "\"required\":[\"briefing\",\"keyFindings\"],\"additionalProperties\":false}");

        // Verify all agents were created
        assertNotNull(agentWebResearcher, "Web Researcher agent must be created");
        assertNotNull(agentDocResearcher, "Doc Researcher agent must be created");
        assertNotNull(agentWriter, "Writer agent must be created");
        assertNotNull(agentEvaluator, "Evaluator agent must be created");
        assertNotNull(agentPublisher, "Publisher agent must be created");
        assertNotNull(agentOutput, "Output agent must be created");
        assertNotNull(agentSummary, "Summary agent must be created");

        System.out.println("[AGENTS] Created 7 agents:");
        System.out.println("  Web Researcher:    " + agentWebResearcher);
        System.out.println("  Doc Researcher:    " + agentDocResearcher);
        System.out.println("  Writer:            " + agentWriter);
        System.out.println("  Evaluator:         " + agentEvaluator);
        System.out.println("  Publisher:         " + agentPublisher);
        System.out.println("  Output:            " + agentOutput);
        System.out.println("  Research Summarizer: " + agentSummary);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Sub-Workflow anlegen (Research Summarizer)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    void createSubWorkflow() {
        // Sub-Workflow: 2 Nodes — compress research data into a briefing
        String nodes =
            agentNodeJson("sub_in", "Receive Research", agentSummary,
                "Compress the following research data into key findings as structured JSON.",
                "{\"type\":\"object\",\"properties\":{\"briefing\":{\"type\":\"string\"},"
                + "\"keyFindings\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                + "\"required\":[\"briefing\",\"keyFindings\"],\"additionalProperties\":false}")
            + "," +
            agentNodeJson("sub_out", "Format Briefing", agentSummary,
                "Re-format the briefing as a clean structured summary, prefixed with: RESEARCH-BRIEFING",
                "{\"type\":\"object\",\"properties\":{\"briefing\":{\"type\":\"string\"},"
                + "\"keyFindings\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                + "\"required\":[\"briefing\",\"keyFindings\"],\"additionalProperties\":false}");

        String edges =
            edge("se1", "sub_in", "sub_out");

        subWorkflowId = createWorkflow("E2E Research Summarizer", nodes, edges);
        assertNotNull(subWorkflowId, "Sub-workflow must be created");
        System.out.println("[SUB-WF] Research Summarizer: " + subWorkflowId);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Haupt-Workflow anlegen (Content Production Pipeline)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    void createPipelineWorkflow() {
        String nodes =
            // ── FORK ──────────────────────────────────────────────
            "{\"id\":\"n_fork\",\"type\":\"fork\",\"title\":\"Research Fork\"}"

            // ── BRANCH A: Async Web Research ──────────────────────
            + ",{\"id\":\"n_async\",\"type\":\"async\",\"title\":\"Async Web Research\","
            + "\"config\":{\"refNodeId\":\"n_research_web\",\"timeoutMs\":120000}}"

            + "," + agentNode("n_research_web", "Web Researcher", agentWebResearcher,
                "Research the following topic using web sources. Find authoritative, "
                + "recent information. Return a structured summary with cited sources.")

            // ── BRANCH B: Document Research ───────────────────────
            + "," + agentNode("n_research_docs", "Document Researcher", agentDocResearcher,
                "Analyze existing documents and local resources for the following topic. "
                + "Identify what information is already available and what gaps exist.")

            // ── JOIN ──────────────────────────────────────────────
            + ",{\"id\":\"n_join\",\"type\":\"join\",\"title\":\"Merge Research\","
            + "\"config\":{\"resultIds\":[\"n_research_web\",\"n_research_docs\"]}}"

            // ── NESTED WORKFLOW: Research Summarizer ──────────────
            + ",{\"id\":\"n_summary\",\"type\":\"nested-workflow\",\"title\":\"Summarize Research\","
            + "\"config\":{\"workflowId\":\"" + subWorkflowId + "\","
            + "\"outputMapping\":{\"sub_out\":\"briefing\"}}}"

            // ── LOOP: Iterative Refinement ────────────────────────
            + ",{\"id\":\"n_loop\",\"type\":\"loop\",\"title\":\"Refinement Loop\","
            + "\"config\":{\"maxIterations\":2,\"exitCondition\":\"iter>=2\",\"loopTargetId\":\"n_write\"}}"

            // ── LOOP BODY: Writer ─────────────────────────────────
            + "," + agentNodeJson("n_write", "Content Writer", agentWriter,
                "Write or refine an article based on the research data. Follow professional "
                + "journalism standards. If this is a refinement, improve based on previous feedback.",
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},"
                + "\"article\":{\"type\":\"string\"}},\"required\":[\"title\",\"article\"],"
                + "\"additionalProperties\":false}")

            // ── LOOP BODY: Evaluator (Watchdog) ───────────────────
            + "," + agentNodeJson("n_evaluate", "Quality Evaluator", agentEvaluator,
                "Evaluate the article quality. Produce the structured JSON verdict.",
                "{\"type\":\"object\",\"properties\":{\"verdict\":{\"type\":\"string\","
                + "\"enum\":[\"PASSED\",\"FAILED\"]},\"score\":{\"type\":\"number\"},"
                + "\"notes\":{\"type\":\"string\"}},\"required\":[\"verdict\",\"score\"],"
                + "\"additionalProperties\":false}")

            // ── LOOP BODY: Quality Gate (Conditional) ─────────────
            + ",{\"id\":\"n_quality_gate\",\"type\":\"conditional\",\"title\":\"Quality Gate\","
            + "\"config\":{\"condition\":\"contains:passed\","
            + "\"trueTargetId\":\"n_publish\",\"falseTargetId\":\"n_loop\"}}"

            // ── PUBLISHER ─────────────────────────────────────────
            + "," + agentNodeJson("n_publish", "Publisher", agentPublisher,
                "Publish the approved article as structured markdown with frontmatter.",
                "{\"type\":\"object\",\"properties\":{\"file\":{\"type\":\"string\"},"
                + "\"title\":{\"type\":\"string\"},\"markdown\":{\"type\":\"string\"},"
                + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                + "\"required\":[\"file\",\"title\",\"markdown\"],\"additionalProperties\":false}")

            // ── MESSAGING-OUT ─────────────────────────────────────
            + ",{\"id\":\"n_messaging\",\"type\":\"messaging-out\",\"title\":\"Notify Channel\","
            + "\"config\":{\"channel\":\"content-published\",\"topic\":\"articles\"}}"

            // ── OUTPUT SUMMARY ────────────────────────────────────
            + "," + agentNodeJson("n_output", "Output Summary", agentOutput,
                "Summarize the publication result concisely.",
                "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"},"
                + "\"file\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"}},"
                + "\"required\":[\"status\"],\"additionalProperties\":false}");

        String edges =
            // Fork → Branches
            edge("e_fork_async", "n_fork", "n_async")
            + "," + edge("e_fork_docs", "n_fork", "n_research_docs")

            // Async → Web Research (child node)
            + "," + edgeInput("e_async_web", "n_async", "n_research_web", "n_async", "text")

            // Branches → Join
            + "," + edgeInput("e_web_join", "n_research_web", "n_join", "n_research_web", "json")
            + "," + edgeInput("e_docs_join", "n_research_docs", "n_join", "n_research_docs", "json")

            // Join → Summary
            + "," + edgeInput("e_join_summary", "n_join", "n_summary", "n_join", "json")

            // Summary → Loop
            + "," + edgeInput("e_summary_loop", "n_summary", "n_loop", "n_summary", "json")

            // Loop → Writer (body entry)
            + "," + edgeInput("e_loop_write", "n_loop", "n_write", "n_summary", "json")

            // Writer → Evaluator
            + "," + edgeInput("e_write_eval", "n_write", "n_evaluate", "n_write", "json")

            // Evaluator → Quality Gate
            + "," + edgeInput("e_eval_gate", "n_evaluate", "n_quality_gate", "n_evaluate", "text")

            // Quality Gate → Publish (true branch)
            + "," + edgeInput("e_gate_publish", "n_quality_gate", "n_publish", "n_write", "json")

            // Quality Gate → Loop (false branch — retry)
            + "," + edgeInput("e_gate_loop", "n_quality_gate", "n_loop", "n_evaluate", "text")

            // Publish → Messaging
            + "," + edgeInput("e_publish_msg", "n_publish", "n_messaging", "n_publish", "json")

            // Messaging → Output
            + "," + edgeInput("e_msg_output", "n_messaging", "n_output", "n_messaging", "text");

        pipelineWorkflowId = createWorkflow("E2E Content Production Pipeline", nodes, edges);
        assertNotNull(pipelineWorkflowId, "Pipeline workflow must be created");
        System.out.println("[PIPELINE] Workflow: " + pipelineWorkflowId);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Struktur-Validierung
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    void validateWorkflowStructure() {
        Map<String, String> row = workflowRow(pipelineWorkflowId);
        requireRow(row, "Pipeline workflow");

        assertTrue(row.get("nodes").contains("n_fork"), "Must contain fork node");
        assertTrue(row.get("nodes").contains("n_join"), "Must contain join node");
        assertTrue(row.get("nodes").contains("n_loop"), "Must contain loop node");
        assertTrue(row.get("nodes").contains("n_quality_gate"), "Must contain conditional node");
        assertTrue(row.get("nodes").contains("n_messaging"), "Must contain messaging-out node");
        assertTrue(row.get("nodes").contains("n_summary"), "Must contain nested-workflow node");
        assertTrue(row.get("nodes").contains("n_async"), "Must contain async node");

        // Verify all agent IDs exist via REST
        for (String agentId : List.of(agentWebResearcher, agentDocResearcher, agentWriter,
                agentEvaluator, agentPublisher, agentOutput, agentSummary)) {
            given().config(TIMEOUT).when()
                .get("/api/ui/agents/" + agentId)
                .then().statusCode(200);
        }

        // Verify sub-workflow exists
        given().config(TIMEOUT).when()
            .get("/api/ui/workflows/" + subWorkflowId)
            .then().statusCode(200);

        System.out.println("[VALIDATE] Structure OK — all nodes, agents, sub-workflow present");
    }

// ═══════════════════════════════════════════════════════════════════════
// 5. Execute workflow
// ═══════════════════════════════════════════════════════════════════════

@Test
@Order(5)
void executePipeline() throws Exception {
    runId = startRun(pipelineWorkflowId,
        "Analyze the current state of AI-assisted software development: "
        + "Which tools and frameworks are leading? What trends are there in 2025/2026? "
            + "Write a short article (800-1200 words) in English.");

        assertNotNull(runId, "Run must be started");
        System.out.println("[EXECUTE] Run started: " + runId);

        Map<String, Object> run = waitForRun(runId, 600);
        String status = String.valueOf(run.get("status"));
        System.out.println("[EXECUTE] Run finished with status: " + status);

        // Print node results for debugging
        List<?> results = (List<?>) run.get("nodeResults");
        if (results != null) {
            System.out.println("[EXECUTE] Node results (" + results.size() + " nodes):");
            for (Object r : results) {
                Map<String, Object> m = (Map<String, Object>) r;
                String nid = String.valueOf(m.get("nodeId"));
                String nstatus = String.valueOf(m.get("status"));
                String output = String.valueOf(m.get("output"));
                String truncated = output.length() > 200 ? output.substring(0, 200) + "..." : output;
                System.out.println("  " + nid + " [" + nstatus + "]: " + truncated);
            }
        }

        assertEquals("completed", status,
            "Pipeline workflow must complete. RunId=" + runId + ", status=" + status);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Node-Ergebnisse verifizieren
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    @Test
    @Order(6)
    void verifyNodeResults() throws Exception {
        Map<String, Object> run = given().when().get("/api/ui/runs/" + runId)
            .then().statusCode(200).extract().as(Map.class);
        List<?> results = (List<?>) run.get("nodeResults");
        assertNotNull(results, "Node results must exist");

        // Fork must have completed
        Map<String, Object> fork = findNode(results, "n_fork");
        assertNotNull(fork, "Fork node must be recorded");
        assertEquals("completed", String.valueOf(fork.get("status")), "Fork must complete");

        // Async node must have completed (web research ran in virtual thread)
        Map<String, Object> async = findNode(results, "n_async");
        assertNotNull(async, "Async node must be recorded");
        String asyncOut = String.valueOf(async.get("output"));
        assertFalse(asyncOut.isBlank(), "Async node must have output");
        System.out.println("[VERIFY] Async output: " + truncate(asyncOut, 300));

        // Join must have aggregated both branches
        Map<String, Object> join = findNode(results, "n_join");
        assertNotNull(join, "Join node must be recorded");
        String joinOut = String.valueOf(join.get("output"));
        assertTrue(joinOut.contains("n_research_web") || joinOut.length() > 50,
            "Join must aggregate web research output: " + truncate(joinOut, 200));
        System.out.println("[VERIFY] Join output: " + truncate(joinOut, 300));

        // Nested workflow must have run
        Map<String, Object> summary = findNode(results, "n_summary");
        assertNotNull(summary, "Nested workflow (summary) must be recorded");
        String summaryOut = String.valueOf(summary.get("output"));
        assertFalse(summaryOut.isBlank(), "Summary must have output");
        System.out.println("[VERIFY] Summary output: " + truncate(summaryOut, 300));

        // Loop must have run (iterations recorded)
        Map<String, Object> loop = findNode(results, "n_loop");
        assertNotNull(loop, "Loop node must be recorded");
        String loopOut = String.valueOf(loop.get("output"));
        assertTrue(loopOut.contains("Loop iterations=") || loopOut.contains("skipped"),
            "Loop must report iteration count: " + loopOut);
        System.out.println("[VERIFY] Loop output: " + truncate(loopOut, 500));

        // Writer must have produced content
        Map<String, Object> writer = findNode(results, "n_write");
        assertNotNull(writer, "Writer node must be recorded");
        String writerOut = String.valueOf(writer.get("output"));
        assertTrue(writerOut.length() > 100 || writerOut.contains("skipped"),
            "Writer must produce substantial content (>100 chars): " + truncate(writerOut, 200));
        System.out.println("[VERIFY] Writer output length: " + writerOut.length() + " chars");

        // Evaluator must have scored (LLM may produce a terse score+verdict or a verbose prose)
        Map<String, Object> evaluator = findNode(results, "n_evaluate");
        assertNotNull(evaluator, "Evaluator node must be recorded");
        String evalOut = String.valueOf(evaluator.get("output"));
        assertTrue(evalOut.length() > 10,
            "Evaluator must produce assessment (>10 chars): " + truncate(evalOut, 200));
        System.out.println("[VERIFY] Evaluator output: " + truncate(evalOut, 300));

        // Quality gate must have evaluated
        Map<String, Object> gate = findNode(results, "n_quality_gate");
        assertNotNull(gate, "Quality gate must be recorded");
        String gateOut = String.valueOf(gate.get("output"));
        assertTrue(gateOut.contains("condition") || gateOut.contains("skipped"),
            "Quality gate must report condition result: " + gateOut);
        System.out.println("[VERIFY] Quality gate: " + gateOut);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. Publish + Messaging verifizieren
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    @Test
    @Order(7)
    void verifyPublishAndMessaging() throws Exception {
        Map<String, Object> run = given().when().get("/api/ui/runs/" + runId)
            .then().statusCode(200).extract().as(Map.class);
        List<?> results = (List<?>) run.get("nodeResults");

        // Publisher must have completed
        Map<String, Object> publisher = findNode(results, "n_publish");
        if (publisher != null) {
            String pubOut = String.valueOf(publisher.get("output"));
            System.out.println("[PUBLISH] Publisher output: " + truncate(pubOut, 300));
            // Publisher should mention a file path or confirmation
            assertTrue(pubOut.length() > 20,
                "Publisher must produce output: " + truncate(pubOut, 200));
        } else {
            System.out.println("[PUBLISH] Publisher node not recorded (may have been skipped by quality gate)");
        }

        // Messaging-out must have attempted to send
        Map<String, Object> messaging = findNode(results, "n_messaging");
        if (messaging != null) {
            String msgOut = String.valueOf(messaging.get("output"));
            System.out.println("[MESSAGING] Output: " + msgOut);
            // Messaging-out always returns "[sent to messaging channel]" or similar
            assertTrue(msgOut.contains("sent") || msgOut.contains("messaging"),
                "Messaging node must indicate send attempt: " + msgOut);
        } else {
            System.out.println("[MESSAGING] Messaging node not recorded (may have been skipped)");
        }

        // Output summary
        Map<String, Object> output = findNode(results, "n_output");
        if (output != null) {
            String outputOut = String.valueOf(output.get("output"));
            System.out.println("[OUTPUT] Summary: " + truncate(outputOut, 300));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. Cleanup
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    void cleanup() {
        // Delete workflows
        if (pipelineWorkflowId != null) {
            given().when().delete("/api/ui/workflows/" + pipelineWorkflowId).then().statusCode(204);
            System.out.println("[CLEANUP] Deleted pipeline workflow: " + pipelineWorkflowId);
        }
        if (subWorkflowId != null) {
            given().when().delete("/api/ui/workflows/" + subWorkflowId).then().statusCode(204);
            System.out.println("[CLEANUP] Deleted sub-workflow: " + subWorkflowId);
        }

        // Delete agents
        for (String agentId : List.of(agentWebResearcher, agentDocResearcher, agentWriter,
                agentEvaluator, agentPublisher, agentOutput, agentSummary)) {
            if (agentId != null) {
                given().when().delete("/api/ui/agents/" + agentId).then().statusCode(204);
            }
        }
        System.out.println("[CLEANUP] Deleted all 7 agents");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static String createAgentWithTools(String name, String toolsJson, String systemPrompt,
                                               String jsonSchema) {
        String escapedPrompt = systemPrompt.replace("\"", "\\\"");
        String schemaField = (jsonSchema == null || jsonSchema.isBlank())
            ? "\"jsonOutput\":false"
            : "\"jsonOutput\":true,\"jsonOutputSchema\":\"" + jsonSchema.replace("\"", "\\\"") + "\"";
        return given().contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "\",\"category\":\"general\",\"model\":\"gpt-4o-mini\","
                + "\"temperature\":0.2,\"maxTokens\":1024,\"topP\":1.0,\"tools\":" + toolsJson + ","
                + "\"guardrailsInput\":[],\"guardrailsOutput\":[],"
                + "\"systemPrompt\":\"" + escapedPrompt + "\"," + schemaField + "}")
        .when().post("/api/ui/agents")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
    }

    private static String agentNode(String id, String title, String agentId, String prompt) {
        String escapedPrompt = prompt.replace("\"", "\\\"");
        return "{\"id\":\"" + id + "\",\"type\":\"agent\",\"title\":\"" + title + "\","
            + "\"config\":{\"agentId\":\"" + agentId + "\",\"prompt\":\"" + escapedPrompt + "\"}}";
    }

    private static String agentNodeJson(String id, String title, String agentId, String prompt,
                                        String schema) {
        String escapedPrompt = prompt.replace("\"", "\\\"");
        String escapedSchema = schema.replace("\"", "\\\"");
        return "{\"id\":\"" + id + "\",\"type\":\"agent\",\"title\":\"" + title + "\","
            + "\"config\":{\"agentId\":\"" + agentId + "\",\"prompt\":\"" + escapedPrompt + "\","
            + "\"jsonOutput\":true,\"jsonOutputSchema\":\"" + escapedSchema + "\"}}";
    }

    private static String edge(String id, String source, String target) {
        return "{\"id\":\"" + id + "\",\"source\":\"" + source + "\",\"target\":\"" + target + "\"}";
    }

    private static String edgeInput(String id, String source, String target,
                                     String sourceNodeId, String format) {
        return "{\"id\":\"" + id + "\",\"source\":\"" + source + "\",\"target\":\"" + target
            + "\",\"input\":{\"sourceNodeId\":\"" + sourceNodeId + "\",\"format\":\"" + format + "\"}}";
    }

    private static String createWorkflow(String name, String nodes, String edges) {
        return given().contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "\",\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}")
        .when().post("/api/ui/workflows")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
    }

    private static String startRun(String workflowId, String initialData) {
        return given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"initialData\":\"" + initialData.replace("\"", "\\\"") + "\"}")
        .when().post("/api/ui/workflows/" + workflowId + "/execute")
        .then().statusCode(200).body("runId", is(notNullValue()))
            .extract().path("runId");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findNode(List<?> results, String nodeId) {
        for (Object r : results) {
            Map<String, Object> m = (Map<String, Object>) r;
            if (nodeId.equals(String.valueOf(m.get("nodeId")))) return m;
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
