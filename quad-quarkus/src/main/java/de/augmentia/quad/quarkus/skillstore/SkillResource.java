package de.augmentia.quad.quarkus.skillstore;

import de.augmentia.quad.core.capability.skill.SkillEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * REST for unified skill management (file + DB, DB overrides file).
 *
 * <p>Skills are always "active" (available). Only skills assigned to an agent
 * via {@code agent_skills} table are actually used by that agent — analogous
 * to {@code agent_tools}.
 */
@ApplicationScoped
@jakarta.ws.rs.Path("/api/skills")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SkillResource {

    @Inject
    SkillService skillService;

    @Inject
    JdbcSkillStore store;

    @Inject
    JdbcAgentSkillStore agentSkillStore;

    /**
     * GET /api/skills — lists ALL skills (file + DB merged).
     * DB entries override file entries with the same name.
     * The {@code assigned} flag is always false (no agent context).
     */
    @GET
    public Response list() {
        var skills = skillService.loadAll();
        var result = new java.util.ArrayList<Map<String, Object>>();
        for (var entry : skills.entrySet()) {
            var view = entry.getValue();
            var map = new java.util.HashMap<String, Object>();
            map.put("name", view.name());
            map.put("description", view.description() != null ? view.description() : "");
            map.put("instructions", view.instructions() != null ? view.instructions() : "");
            map.put("allowedTools", view.allowedTools() != null ? view.allowedTools() : List.of());
            map.put("declaredTools", view.declaredTools() != null ? view.declaredTools() : List.of());
            map.put("source", view.source());
            map.put("assigned", view.assigned());
            result.add(map);
        }
        return Response.ok(result).build();
    }

    /**
     * GET /api/skills/agents/{agentId} — lists skills assigned to an agent.
     */
    @GET
    @Path("/agents/{agentId}")
    public Response listByAgent(@PathParam("agentId") String agentId) {
        var skills = agentSkillStore.listByAgent(agentId);
        return Response.ok(skills).build();
    }

    /**
     * GET /api/skills/{name} — get a single skill by name (DB overrides file).
     */
    @GET
    @Path("/{name}")
    public Response get(@PathParam("name") String name) {
        var skills = skillService.loadAll();
        var view = skills.get(name);
        if (view != null) {
            var map = new java.util.HashMap<String, Object>();
            map.put("name", view.name());
            map.put("description", view.description());
            map.put("instructions", view.instructions());
            map.put("allowedTools", view.allowedTools());
            map.put("declaredTools", view.declaredTools());
            map.put("source", view.source());
            map.put("assigned", view.assigned());
            return Response.ok(map).build();
        }
        // Fallback: DB-only lookup
        var skill = store.findByName(name);
        if (skill.isPresent()) {
            var s = skill.get();
            var map = new java.util.HashMap<String, Object>();
            map.put("name", s.name());
            map.put("description", s.description());
            map.put("instructions", s.instructions());
            map.put("allowedTools", s.allowedTools());
            map.put("declaredTools", s.declaredTools());
            map.put("source", "db");
            map.put("assigned", false);
            return Response.ok(map).build();
        }
        return Response.status(404).entity(Map.of("error", "not found")).build();
    }

    /**
     * PUT /api/skills/{name} — upsert a skill to DB (overrides file).
     */
    @PUT
    @Path("/{name}")
    public Response upsert(@PathParam("name") String name, Map<String, Object> payload) {
        String description = payload.get("description") != null ? String.valueOf(payload.get("description")) : "";
        String instructions = payload.get("instructions") != null ? String.valueOf(payload.get("instructions")) : "";
        List<String> allowedTools = parseList(payload.get("allowed_tools"));
        List<String> declaredTools = parseList(payload.get("declared_tools"));
        String id = payload.get("id") != null ? String.valueOf(payload.get("id")) : null;
        store.upsert(id, name, description, instructions, allowedTools, declaredTools, Map.of());
        var resp = new java.util.HashMap<String, Object>();
        resp.put("ok", true);
        resp.put("id", id != null ? id : "");
        resp.put("source", "db");
        return Response.ok(resp).build();
    }

    /**
     * DELETE /api/skills/{name} — delete a skill from DB.
     * After deletion, the file-based skill (if any) becomes visible again.
     */
    @DELETE
    @Path("/{name}")
    public Response delete(@PathParam("name") String name) {
        store.deleteByName(name);
        return Response.ok(Map.of("ok", true, "name", name, "source", "file")).build();
    }

    /**
     * POST /api/skills/{name}/assign — assign a skill to an agent.
     */
    @POST
    @Path("/{name}/assign")
    public Response assign(@PathParam("name") String name, Map<String, Object> payload) {
        String agentId = payload.get("agentId") != null ? String.valueOf(payload.get("agentId")) : null;
        if (agentId == null || agentId.isBlank()) {
            return Response.status(400).entity(Map.of("error", "agentId required")).build();
        }
        agentSkillStore.assign(agentId, name);
        return Response.ok(Map.of("ok", true, "agentId", agentId, "skillName", name)).build();
    }

    /**
     * DELETE /api/skills/{name}/assign/{agentId} — unassign a skill from an agent.
     */
    @DELETE
    @Path("/{name}/assign/{agentId}")
    public Response unassign(@PathParam("name") String name, @PathParam("agentId") String agentId) {
        agentSkillStore.unassign(agentId, name);
        return Response.ok(Map.of("ok", true, "agentId", agentId, "skillName", name)).build();
    }

    private List<String> parseList(Object value) {
        if (value == null || !(value instanceof List<?> l)) return List.of();
        return l.stream().filter(v -> v instanceof String s && !s.isBlank()).map(String.class::cast).toList();
    }
}