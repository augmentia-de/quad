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
 * REST for DB-based skill management (Port 06).
 */
@ApplicationScoped
@Path("/api/skills")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SkillResource {

    @Inject
    JdbcSkillStore store;

    @GET
    public Response list() {
        return Response.ok(store.listAll()).build();
    }

    @GET
    @Path("/{name}")
    public Response get(@PathParam("name") String name) {
        java.util.Optional<SkillEntry> skill = store.findByName(name);
        if (skill.isPresent()) return Response.ok(skill.get()).build();
        return Response.status(404).entity(Map.of("error", "not found")).build();
    }

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
        return Response.ok(resp).build();
    }

    @DELETE
    @Path("/{name}")
    public Response delete(@PathParam("name") String name) {
        store.deleteByName(name);
        return Response.ok(Map.of("ok", true, "name", name)).build();
    }

    private List<String> parseList(Object value) {
        if (value == null || !(value instanceof List<?> l)) return List.of();
        return l.stream().filter(v -> v instanceof String s && !s.isBlank()).map(String.class::cast).toList();
    }
}
