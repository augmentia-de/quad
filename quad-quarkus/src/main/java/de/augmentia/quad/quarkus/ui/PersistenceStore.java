package de.augmentia.quad.quarkus.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import de.augmentia.quad.quarkus.contract.ApiDtos;
import de.augmentia.quad.quarkus.persistence.RunStore;
import de.augmentia.quad.quarkus.persistence.SqlDialect;

@ApplicationScoped
public class PersistenceStore {

    private static final Logger log = Logger.getLogger(PersistenceStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DataSource dataSource;

    @Inject
    SqlDialect dialect;

    /** Test-Hook (analog SessionStore.setDataSource): erlaubt Unit-Tests ohne CDI. */
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Test-Hook: erlaubt Unit-Tests ohne CDI for SqlDialect. */
    public void setDialect(SqlDialect dialect) {
        this.dialect = dialect;
    }

    // ── Agents ──────────────────────────────────────────────

    public void upsertAgent(ApiDtos.AgentDefinition agent) {
        String sql = dialect.upsert("agents",
            "id, name, description, category, model, temperature, max_tokens, top_p, " +
            "tools, guardrails_input, guardrails_output, hooks, agent_type, system_prompt, " +
            "user_message_template, json_output, json_output_schema, chat_parameters, active, timeout_seconds, created_at, updated_at",
            "id",
            "name=excluded.name, description=excluded.description, category=excluded.category, model=excluded.model, " +
            "temperature=excluded.temperature, max_tokens=excluded.max_tokens, top_p=excluded.top_p, tools=excluded.tools, " +
            "guardrails_input=excluded.guardrails_input, guardrails_output=excluded.guardrails_output, hooks=excluded.hooks, " +
            "agent_type=excluded.agent_type, system_prompt=excluded.system_prompt, user_message_template=excluded.user_message_template, " +
            "json_output=excluded.json_output, json_output_schema=excluded.json_output_schema, chat_parameters=excluded.chat_parameters, " +
            "active=excluded.active, timeout_seconds=excluded.timeout_seconds, updated_at=CURRENT_TIMESTAMP");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agent.id);
            ps.setString(2, agent.name);
            ps.setString(3, agent.description);
            ps.setString(4, agent.category);
            ps.setString(5, agent.model);
            ps.setDouble(6, agent.temperature);
            ps.setInt(7, agent.maxTokens);
            ps.setDouble(8, agent.topP);
            ps.setString(9, agent.tools != null ? String.join(",", agent.tools) : "");
            ps.setString(10, agent.guardrailsInput != null ? String.join(",", agent.guardrailsInput) : "");
            ps.setString(11, agent.guardrailsOutput != null ? String.join(",", agent.guardrailsOutput) : "");
            ps.setString(12, agent.hooks != null ? String.join(",", agent.hooks) : "");
            ps.setString(13, agent.agentType != null && !agent.agentType.isBlank() ? agent.agentType : "ua");
            ps.setString(14, agent.systemPrompt);
            ps.setString(15, agent.userMessageTemplate);
            ps.setBoolean(16, agent.jsonOutput);
            ps.setString(17, agent.jsonOutputSchema);
            ps.setString(18, agent.chatParameters);
            ps.setBoolean(19, agent.active);
            ps.setObject(20, agent.timeoutSeconds);
            ps.setTimestamp(21, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
            ps.setTimestamp(22, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf(e, "upsertAgent fehlgeschlagen: %s", agent.id);
        }
    }

    public void deleteAgent(String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("delete from agents where id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf(e, "deleteAgent fehlgeschlagen: %s", id);
        }
    }

    public Map<String, ApiDtos.AgentDefinition> loadAllAgents() {
        Map<String, ApiDtos.AgentDefinition> result = new LinkedHashMap<>();
        String sql = "select id, name, description, category, model, temperature, max_tokens, " +
            "top_p, tools, guardrails_input, guardrails_output, hooks, agent_type, system_prompt, " +
            "user_message_template, json_output, json_output_schema, chat_parameters, active, timeout_seconds from agents";
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                ApiDtos.AgentDefinition agent = new ApiDtos.AgentDefinition(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("category"),
                    rs.getString("model"),
                    rs.getDouble("temperature"),
                    rs.getInt("max_tokens"),
                    rs.getDouble("top_p"),
                    splitArr(rs.getString("tools")),
                    splitArr(rs.getString("guardrails_input")),
                    splitArr(rs.getString("guardrails_output")),
                    rs.getString("agent_type") != null ? rs.getString("agent_type") : "ua",
                    rs.getString("system_prompt"),
                    rs.getString("user_message_template"),
                    rs.getBoolean("json_output"),
                    rs.getString("chat_parameters"),
                    rs.getBoolean("active"),
                    splitArr(rs.getString("hooks"))
                );
                agent.jsonOutputSchema = rs.getString("json_output_schema");
                agent.timeoutSeconds = rs.getObject("timeout_seconds") != null ? rs.getInt("timeout_seconds") : null;
                result.put(agent.id, agent);
            }
        } catch (SQLException e) {
            log.warnf(e, "loadAllAgents fehlgeschlagen");
        }
        return result;
    }

    // ── Workflows ───────────────────────────────────────────

    public void upsertWorkflow(ApiDtos.WorkflowDef wf) {
        String sql = dialect.upsert("workflows", "id, name, initial_task, nodes, edges, created_at, updated_at",
            "id", "name=excluded.name, initial_task=excluded.initial_task, nodes=excluded.nodes, edges=excluded.edges, updated_at=excluded.updated_at");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, wf.id);
            ps.setString(2, wf.name);
            ps.setString(3, wf.initialTask);
            ps.setString(4, MAPPER.writeValueAsString(wf.nodes));
            ps.setString(5, MAPPER.writeValueAsString(wf.edges));
            ps.setString(6, wf.createdAt);
            ps.setString(7, wf.updatedAt);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warnf(e, "upsertWorkflow fehlgeschlagen: %s", wf.id);
        }
    }

    public void deleteWorkflow(String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("delete from workflows where id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf(e, "deleteWorkflow fehlgeschlagen: %s", id);
        }
    }

    public Map<String, ApiDtos.WorkflowDef> loadAllWorkflows() {
        Map<String, ApiDtos.WorkflowDef> result = new LinkedHashMap<>();
        String sql = "select id, name, initial_task, nodes, edges, created_at, updated_at from workflows";
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                List<Map<String, Object>> nodes = MAPPER.readValue(rs.getString("nodes"),
                    new TypeReference<List<Map<String, Object>>>() {});
                List<Map<String, Object>> edges = MAPPER.readValue(rs.getString("edges"),
                    new TypeReference<List<Map<String, Object>>>() {});
                ApiDtos.WorkflowDef wf = new ApiDtos.WorkflowDef(
                    rs.getString("id"), rs.getString("name"), nodes, edges, rs.getString("initial_task"),
                    rs.getString("created_at"), rs.getString("updated_at"));
                result.put(wf.id, wf);
            }
        } catch (Exception e) {
            log.warnf(e, "loadAllWorkflows fehlgeschlagen");
        }
        return result;
    }

    // ── Workflow-State (ersetzt: runs) ──────────────────────

    public void upsertRun(RunStore.RunState run) {
        String sql = dialect.upsert("workflow_state",
            "id, workflow_id, status, initial_data, step_results, executing_from, restart_count, " +
            "started_at, finished_at, duration_ms, kind, step_index, updated_at",
            "id",
            "workflow_id=excluded.workflow_id, status=excluded.status, initial_data=excluded.initial_data, " +
            "step_results=excluded.step_results, executing_from=excluded.executing_from, restart_count=excluded.restart_count, " +
            "started_at=excluded.started_at, finished_at=excluded.finished_at, duration_ms=excluded.duration_ms, " +
            "kind=excluded.kind, step_index=excluded.step_index, updated_at=CURRENT_TIMESTAMP");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, run.runId);
            ps.setString(2, run.workflowId);
            ps.setString(3, run.status);
            ps.setString(4, run.initialData);
            List<Map<String, Object>> nodeResults = new ArrayList<>();
            for (RunStore.NodeRunResult n : run.nodeResults) {
                var m = new LinkedHashMap<String, Object>();
                m.put("nodeId", n.nodeId());
                m.put("type", n.type() != null ? n.type() : "");
                m.put("title", n.title() != null ? n.title() : "");
                m.put("status", n.status() != null ? n.status() : "");
                m.put("output", n.output() != null ? n.output() : "");
                m.put("input", n.input() != null ? n.input() : "");
                nodeResults.add(m);
            }
            ps.setString(5, MAPPER.writeValueAsString(nodeResults));
            ps.setString(6, run.executingFrom);
            ps.setInt(7, run.restartCount);
            ps.setString(8, run.startedAt);
            ps.setString(9, run.finishedAt);
            ps.setLong(10, run.durationMs);
            ps.setString(11, run.kind != null ? run.kind : "WORKFLOW");
            ps.setInt(12, run.stepIndex);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warnf(e, "upsertRun fehlgeschlagen: %s", run.runId);
        }
    }

    public void deleteRun(String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("delete from workflow_state where id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warnf(e, "deleteRun fehlgeschlagen: %s", id);
        }
    }

    /** Row from workflow_state or null (returns the currently reachable DB persistence). */
    public RunStore.PersistedRun loadRunFromDb(String runId) {
        String sql = "select id, workflow_id, status, initial_data, step_results, executing_from, " +
            "restart_count, started_at, finished_at, duration_ms, kind, step_index " +
            "from workflow_state where id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return readRun(rs);
            }
        } catch (Exception e) {
            log.warnf(e, "loadRunFromDb fehlgeschlagen: %s", runId);
            return null;
        }
    }

    /** Alle Runs from workflow_state (neueste zuerst). */
    public List<RunStore.PersistedRun> listRunsFromDb() {
        String sql = "select id, workflow_id, status, initial_data, step_results, executing_from, " +
            "restart_count, started_at, finished_at, duration_ms, kind, step_index " +
            "from workflow_state " +
            "order by started_at desc";
        List<RunStore.PersistedRun> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) result.add(readRun(rs));
        } catch (Exception e) {
            log.warnf(e, "listRunsFromDb fehlgeschlagen");
        }
        return result;
    }

    private RunStore.PersistedRun readRun(ResultSet rs) throws SQLException {
        List<RunStore.NodeRunResult> results = new ArrayList<>();
        String json = rs.getString("step_results");
        if (json != null && !json.isBlank()) {
            try {
                var nodes = MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                for (var n : nodes) results.add(new RunStore.NodeRunResult(
                    String.valueOf(n.get("nodeId")),
                    n.get("type") != null ? String.valueOf(n.get("type")) : "",
                    n.get("title") != null ? String.valueOf(n.get("title")) : "",
                    n.get("status") != null ? String.valueOf(n.get("status")) : "pending",
                    n.get("output") != null ? String.valueOf(n.get("output")) : "",
                    n.get("input") != null ? String.valueOf(n.get("input")) : ""
                ));
            } catch (Exception e) {
                log.warnf("readRun: step_results not lesbar: %s", e.getMessage());
            }
        }
        return new RunStore.PersistedRun(
            rs.getString("id"), rs.getString("workflow_id"), rs.getString("status"),
            rs.getString("initial_data"), results,
            rs.getString("executing_from"), rs.getInt("restart_count"),
            rs.getString("started_at"), rs.getString("finished_at"), rs.getLong("duration_ms"),
            rs.getString("kind"), rs.getInt("step_index"));
    }

    // ── Helpers ─────────────────────────────────────────────

    private static String[] splitArr(String csv) {
        if (csv == null || csv.isBlank()) return new String[0];
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
}
