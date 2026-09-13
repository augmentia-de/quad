package de.augmentia.quad.quarkus.ui;

import de.augmentia.quad.quarkus.contract.ApiDtos;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class SharedState {

    private static final Logger log = Logger.getLogger(SharedState.class);

    @Inject
    PersistenceStore db;

    private final Map<String, ApiDtos.AgentDefinition> agents = new ConcurrentHashMap<>();
    private final Map<String, ApiDtos.WorkflowDef> workflows = new ConcurrentHashMap<>();
    private final AtomicLong metricsCounter = new AtomicLong(0);

    @PostConstruct
    void loadFromDb() {
        try {
            agents.putAll(db.loadAllAgents());
            log.infof("Loaded %d agents from database", agents.size());
        } catch (Exception e) {
            log.warnf(e, "Failed to load agents from database");
        }
        try {
            workflows.putAll(db.loadAllWorkflows());
            log.infof("Loaded %d workflows from database", workflows.size());
        } catch (Exception e) {
            log.warnf(e, "Failed to load workflows from database");
        }
    }

    public Map<String, ApiDtos.AgentDefinition> agents() { return agents; }
    public Map<String, ApiDtos.WorkflowDef> workflows() { return workflows; }
    public long incMetrics() { return metricsCounter.incrementAndGet(); }
    public long metricsValue() { return metricsCounter.get(); }

    // ── Write-through helpers (called by AgentController) ──

    public void putAgent(ApiDtos.AgentDefinition agent) {
        agents.put(agent.id, agent);
        db.upsertAgent(agent);
    }

    public ApiDtos.AgentDefinition removeAgent(String id) {
        ApiDtos.AgentDefinition removed = agents.remove(id);
        if (removed != null) db.deleteAgent(id);
        return removed;
    }

    public void putWorkflow(ApiDtos.WorkflowDef wf) {
        workflows.put(wf.id, wf);
        db.upsertWorkflow(wf);
    }

    public ApiDtos.WorkflowDef removeWorkflow(String id) {
        ApiDtos.WorkflowDef removed = workflows.remove(id);
        if (removed != null) db.deleteWorkflow(id);
        return removed;
    }
}
