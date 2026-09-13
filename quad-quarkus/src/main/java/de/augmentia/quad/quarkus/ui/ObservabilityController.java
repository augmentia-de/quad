package de.augmentia.quad.quarkus.ui;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@ApplicationScoped
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@RegisterForReflection
public class ObservabilityController {

    @GET @Path("/health")
    @Produces(MediaType.TEXT_PLAIN)
    public String health() { return "QUAD Java is running"; }
}
