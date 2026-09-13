package de.augmentia.quad.quarkus.ui.alias;

import de.augmentia.quad.quarkus.provenance.ProvenanceResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * UI-Alias (Base-Path {@code /api/ui}) for {@link ProvenanceResource}.
 */
@ApplicationScoped
@Path("/api/ui/provenance")
@Produces(MediaType.APPLICATION_JSON)
public class UiProvenanceAlias {

    @Inject
    ProvenanceResource inner;

    @GET
    public Response list(@QueryParam("session") String sessionId) { return inner.list(sessionId); }

    @POST @Path("/reset")
    public Response reset(@QueryParam("session") String sessionId) { return inner.reset(sessionId); }
}