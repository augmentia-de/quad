package de.augmentia.quad.quarkus.hitl;

import de.augmentia.quad.core.hitl.checkpoint.Checkpoint;
import de.augmentia.quad.core.hitl.checkpoint.CheckpointService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.List;
import java.util.concurrent.CountDownLatch;

@Singleton
@Path("/api/checkpoints")
public class CheckpointResource {

    @Inject
    HitlService hitlService;

    @Inject
    SSEChannel sseChannel;

    private CheckpointService service() {
        return hitlService.checkpointService();
    }

    @POST
    @Path("/{id}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response approve(@PathParam("id") String id, String body) {
        String feedback = extractFeedback(body);
        boolean ok = service().approve(id, feedback);
        if (!ok) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"Checkpoint nicht gefunden oder nicht mehr pending\"}")
                .build();
        }
        return Response.ok("{\"status\":\"APPROVED\"}").build();
    }

    @POST
    @Path("/{id}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reject(@PathParam("id") String id, String body) {
        String feedback = extractFeedback(body);
        boolean ok = service().reject(id, feedback);
        if (!ok) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"Checkpoint nicht gefunden oder nicht mehr pending\"}")
                .build();
        }
        return Response.ok("{\"status\":\"REJECTED\"}").build();
    }

    @GET
    @Path("/{sessionId}/pending")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Checkpoint> pending(@PathParam("sessionId") String sessionId) {
        return service().getPendingCheckpoints(sessionId);
    }

    @GET
    @Path("/stream/{sessionId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@PathParam("sessionId") String sessionId,
                       @Context Sse sse, @Context SseEventSink sink) {
        CountDownLatch closed = new CountDownLatch(1);
        var emitter = (java.util.function.Consumer<String>) payload -> {
            if (!sink.isClosed()) {
                try {
                    sink.send(sse.newEventBuilder()
                        .name("checkpoint")
                        .data(String.class, payload)
                        .build());
                } catch (Exception ignored) {
                }
            }
        };
        sseChannel.register(sessionId, emitter);
        try {
            while (!sink.isClosed()) {
                closed.await(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sseChannel.unregister(sessionId);
        }
    }

    private static String extractFeedback(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body);
            return node.has("feedback") ? node.get("feedback").asText() : "";
        } catch (Exception e) {
            return body;
        }
    }
}
