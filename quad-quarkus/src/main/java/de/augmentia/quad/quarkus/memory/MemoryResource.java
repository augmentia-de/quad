package de.augmentia.quad.quarkus.memory;

import de.augmentia.quad.core.session.memory.MemoryEntry;
import de.augmentia.quad.core.session.memory.MemoryCategory;
import de.augmentia.quad.core.session.memory.PersistentMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * REST access to long-term memory (Port 01).
 */
@ApplicationScoped
@Path("/api/ui/memory")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MemoryResource {

    @Inject
    PersistentMemoryStore store;

    @GET
    public Response list(@QueryParam("scope") String scope) {
        if (scope != null && !scope.isBlank()) {
            return Response.ok(store.findByScope(scope)).build();
        }
        return Response.ok(store.listAll()).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") String id, Map<String, Object> body) {
        Object content = body.get("content");
        if (!(content instanceof String c)) {
            return Response.status(400).entity(Map.of("error", "content is required")).build();
        }
        boolean exists = store.listAll().stream().anyMatch(e -> id.equals(e.getId()));
        if (exists) {
            store.updateContent(id, c);
        } else {
            var entry = new MemoryEntry();
            entry.setId(id);
            entry.setScope("default");
            entry.setContent(c);
            entry.setCategory(MemoryCategory.USER_FACT);
            store.remember(entry);
        }
        return Response.ok(Map.of("ok", true, "id", id)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response forget(@PathParam("id") String id) {
        store.forget(id);
        return Response.ok(Map.of("ok", true, "id", id)).build();
    }

    @DELETE
    public Response clear() {
        store.deleteAll();
        return Response.ok(Map.of("ok", true)).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        for (MemoryEntry e : store.listAll()) {
            if (id.equals(e.getId())) {
                return Response.ok(e).build();
            }
        }
        return Response.status(404).entity(Map.of("error", "not found")).build();
    }
}
