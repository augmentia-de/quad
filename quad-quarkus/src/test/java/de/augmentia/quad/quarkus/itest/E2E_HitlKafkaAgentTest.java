package de.augmentia.quad.quarkus.itest;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.hitl.HITLPlugin;
import de.augmentia.quad.core.hitl.checkpoint.Checkpoint;
import de.augmentia.quad.core.hitl.checkpoint.CheckpointService;
import de.augmentia.quad.core.hitl.checkpoint.KafkaCheckpointChannel;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.ReflectiveToolMethod;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import de.augmentia.quad.core.session.WorkspaceResolver;
import de.augmentia.quad.core.tool.builtin.WriteTool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E test: file write via an agent tool call that requires HITL approval,
 * and the checkpoint notification is delivered via Kafka to the live
 * topic {@code quad-hitl-checkpoints}.
 *
 * <p>Environment requirements:</p>
 * <ul>
 *   <li>Kafka reachable under {@code localhost:9092} (broker advertising on
 *       {@code PLAINTEXT://localhost:9092} — see {@code deploy/01-infrastructure.yml}),
 *       containers from {@code docker compose -f deploy/01-infrastructure.yml up -d kafka}.</li>
 *   <li>Run: {@code mvn -o -pl quad-quarkus test -Dtest=E2E_HitlKafkaAgentTest -Dgroups=e2e}</li>
 * </ul>
 *
 * <p>Covered:</p>
 * <ul>
 *   <li>Agent runs {@code writeFile}; HITL-Hook creates a checkpoint (PENDING), agent pauses.</li>
 *   <li>Approve → tool runs, file is written.</li>
 *   <li>Reject → tool is blocked, file is NOT written.</li>
 *   <li>Both checkpoints are published as JSON to the Kafka topic {@code quad-hitl-checkpoints}.</li>
 * </ul>
 *
 * <p>The ChatModel is a deterministic mock that first returns a
 * {@code writeFile} tool call and afterwards (after the HITL decision)
 * returns the final answer text. No real LLM, no flakiness.</p>
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2E_HitlKafkaAgentTest {

    private static final String TOPIC = "quad-hitl-checkpoints";
    private static final String BOOTSTRAP = "localhost:9092";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path WORKSPACE;

    static {
        Path tmp;
        try {
            tmp = Files.createTempDirectory("quad-hitl-e2e-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        WORKSPACE = tmp;
        System.setProperty("quad.workspace", WORKSPACE.toString());
    }

    private CheckpointService checkpointService;
    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private final ConcurrentLinkedQueue<String> kafkaCheckpointIds = new ConcurrentLinkedQueue<>();
    private final List<String> kafkaPayloads = new ArrayList<>();
    private final AtomicBoolean collecting = new AtomicBoolean(true);
    private ExecutorService agentExecutor;
    private Thread kafkaCollector;

    @BeforeAll
    void setUp() throws Exception {
        // 1. Real Kafka producer as the HITL Kafka sender (checkpoint JSON → topic).
        producer = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
        KafkaCheckpointChannel.registerSender(json ->
            producer.send(new ProducerRecord<>(TOPIC, json)));

        // 2. Collect checkpoint IDs from the topic in the background.
        consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
            ConsumerConfig.GROUP_ID_CONFIG, "e2e-hitl-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        consumer.subscribe(List.of(TOPIC));
        kafkaCollector = new Thread(() -> {
            while (collecting.get()) {
                var records = consumer.poll(Duration.ofMillis(250));
                for (var rec : records) {
                    synchronized (kafkaPayloads) {
                        kafkaPayloads.add(rec.value());
                    }
                    try {
                        var tree = MAPPER.readTree(rec.value());
                        if ("hitl.checkpoint".equals(tree.path("type").asText())) {
                            kafkaCheckpointIds.add(tree.path("checkpointId").asText());
                        }
                    } catch (Exception ignored) {
                        // Nicht-JSON / fremde Nachricht → ignorieren
                    }
                }
            }
        }, "kafka-hitl-e2e-collector");
        kafkaCollector.setDaemon(true);
        kafkaCollector.start();

        // 3. CheckpointService mit writeFile/readFile als Approval-Tools; Kafka als async Channel.
        checkpointService = new CheckpointService("writeFile,readFile", 30_000);
        checkpointService.registerAsyncChannel(new KafkaCheckpointChannel());

        agentExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterAll
    void tearDown() throws Exception {
        collecting.set(false);
        if (kafkaCollector != null) kafkaCollector.join(3000);
        if (producer != null) { producer.flush(); producer.close(); }
        if (consumer != null) consumer.close();
        if (agentExecutor != null) agentExecutor.shutdownNow();
        deleteRecursively(WORKSPACE);
    }

    // ── E2E-Szenarien ──

    @Test
    void approveDateiWriteToolCall() throws Exception {
        String sessionId = "e2e-approve-" + UUID.randomUUID();

        Future<String> executed = executeAgent(sessionId);

        // Agent pausiert am HITL-Checkpoint (writeFile) → warten bis PENDING.
        Checkpoint cp = awaitPendingCheckpoint(sessionId);
        assertNotNull(cp, "Checkpoint for writeFile must be created");
        assertEquals("writeFile", cp.toolName());
        assertEquals(Checkpoint.Status.PENDING, cp.status());

        // Kafka must have published the checkpoint already.
        awaitInKafka(cp.id());

        // Benutzer genehmigt → Agent setzt fort, Datei wird geschrieben.
        assertTrue(checkpointService.approve(cp.id(), "Freigabe erteilt"));

        String result = executed.get(25, TimeUnit.SECONDS);
        assertTrue(result.contains("toolExecutionResult"), "Agent-Ergebnis soll Tool-Ergebnis enthalten");

        Path written = WORKSPACE.resolve(sessionId).resolve("test.txt");
        assertTrue(Files.exists(written), "Datei muss nach Approval geschrieben existieren: " + written);
        assertEquals("Hallo", Files.readString(written));

        assertEquals(Checkpoint.Status.APPROVED, checkpointService.getCheckpoint(cp.id()).status());
        assertEquals("Freigabe erteilt", checkpointService.getCheckpoint(cp.id()).feedback());

        // Kafka delivery for this checkpoint confirmed (status + sessionId in payload).
        assertTrue(kafkaPayloads.stream().anyMatch(p -> p.contains("\"checkpointId\":\"" + cp.id() + "\"")
                && p.contains("\"sessionId\":\"" + sessionId + "\"")
                && p.contains("\"toolName\":\"writeFile\"")),
            "Kafka payload for approve checkpoint must contain checkpointId/sessionId/toolName");
    }

    @Test
    void rejectDateiWriteToolCall() throws Exception {
        String sessionId = "e2e-reject-" + UUID.randomUUID();

        Future<String> executed = executeAgent(sessionId);

        Checkpoint cp = awaitPendingCheckpoint(sessionId);
        assertNotNull(cp, "Checkpoint for writeFile must be created");
        assertEquals("writeFile", cp.toolName());

        // Kafka must have published the checkpoint already.
        awaitInKafka(cp.id());

        // User rejects → agent continues, tool is NOT executed.
        assertTrue(checkpointService.reject(cp.id(), "Nicht erlaubt"));

        String result = executed.get(25, TimeUnit.SECONDS);
        assertTrue(result.contains("Tool call cancelled") || result.contains("Nicht erlaubt"),
            "Agent-Ergebnis soll die Ablehnung widerspiegeln");

        Path written = WORKSPACE.resolve(sessionId).resolve("test.txt");
        assertFalse(Files.exists(written), "File must NOT exist after reject: " + written);

        assertEquals(Checkpoint.Status.REJECTED, checkpointService.getCheckpoint(cp.id()).status());
        assertEquals("Nicht erlaubt", checkpointService.getCheckpoint(cp.id()).feedback());

        assertTrue(kafkaPayloads.stream().anyMatch(p -> p.contains("\"checkpointId\":\"" + cp.id() + "\"")
                && p.contains("\"sessionId\":\"" + sessionId + "\"")),
            "Kafka payload for reject checkpoint must contain checkpointId/sessionId");
    }

    // ── Helfer ──

    private Future<String> executeAgent(String sessionId) throws Exception {
        Agent agent = buildAgent();
        AgentSessionState state = AgentSessionState.create(sessionId);
        return agentExecutor.submit(() -> agent.run("Schreibe die Datei test.txt mit dem Inhalt 'Hallo'", state));
    }

    private Agent buildAgent() throws Exception {
        Agent agent = AgentBuilder.create(HitlTestAgent.class).withWorkspace(WORKSPACE).build();

        // writeFile-Tool (WriteTool ohne CDI → Workspace-Resolver manuell setzen).
        WriteTool writeTool = new WriteTool();
        writeTool.setWorkspaceResolver(new TestWorkspaceResolver());
        var toolMethod = new ReflectiveToolMethod(
            WriteTool.class.getMethod("writeFile", String.class, String.class),
            writeTool, new ToolArgsMapper(new ObjectMapper()));
        agent.getToolRegistry().register("writeFile", toolMethod);

        // HITL-Hook aktivieren (gleiche HookRegistry wie ToolExecutor — siehe AgentBuilder.build()).
        HITLPlugin hitl = new HITLPlugin(checkpointService);
        hitl.initAgent(agent);
        agent.addHook(hitl);

        // Deterministischer ChatModel: 1. Antwort = writeFile-Tool-Call, 2./3. = finaler Text.
        ChatModel model = new ScriptedChatModel();
        agent.setLlm(model);
        return agent;
    }

    /**
     * Deterministischer ChatModel-Stub (kein Mockito): Der erste Aufruf liefert
     * einen {@code writeFile}-Tool-Call; alle weiteren Aufrufe (nach der
     * HITL-Entscheidung) liefern den finalen Antworttext inkl. Tool-Ergebnis.
     */
    static class ScriptedChatModel implements ChatModel {

        @Override
        public ChatResponse doChat(ChatRequest request) {
            List<ChatMessage> msgs = request.messages();
            ChatMessage last = msgs.get(msgs.size() - 1);
            if (last instanceof ToolExecutionResultMessage ter) {
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from("FERTIG | toolExecutionResult=" + ter.text()))
                    .build();
            }
            var toolCall = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("t-write-1")
                .name("writeFile")
                .arguments("{\"filePath\":\"test.txt\",\"content\":\"Hallo\"}")
                .build();
            return ChatResponse.builder().aiMessage(AiMessage.aiMessage(toolCall)).build();
        }
    }

    private Checkpoint awaitPendingCheckpoint(String sessionId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            var pending = checkpointService.getPendingCheckpoints(sessionId);
            if (!pending.isEmpty()) return pending.get(0);
            Thread.sleep(100);
        }
        return null;
    }

    private void awaitInKafka(String checkpointId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (kafkaCheckpointIds.contains(checkpointId)) return;
            // Sicherstellen, dass gesendete Nachrichten vor dem Check sichtbar werden.
            producer.flush();
            Thread.sleep(200);
        }
        assertTrue(kafkaCheckpointIds.contains(checkpointId),
            "Checkpoint " + checkpointId + " muss im Kafka-Topic " + TOPIC + " angekommen sein");
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) return;
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // Bestehen lassen
                    }
                });
            }
        } catch (IOException ignored) {
            // Bestehen lassen
        }
    }

/**
 * Tool resolver pointing at the test class's temp directory instead of
 * the (statically frozen) system property {@code quad.workspace}. Several
 * E2E classes in the same JVM would otherwise collide over the shared static.
 */
    private static class TestWorkspaceResolver extends WorkspaceResolver {
        @Override
        public Path sessionDir(String sessionId) {
            return WORKSPACE.resolve(sessionId);
        }
    }

    public static class HitlTestAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return AgentSessionState.create("default");
        }
    }
}