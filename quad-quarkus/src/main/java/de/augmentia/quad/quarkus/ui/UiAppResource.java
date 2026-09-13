package de.augmentia.quad.quarkus.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.util.stream.Collectors;

@ApplicationScoped
@Path("/ui")
public class UiAppResource {

    @Inject
    UiConfig config;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response getHtml() {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("ui/index.html")) {
            if (stream == null) {
                return Response.ok(createFallbackHtml()).build();
            }
            String html = new BufferedReader(new InputStreamReader(stream))
                .lines().collect(Collectors.joining("\n"));
            return Response.ok(html, MediaType.TEXT_HTML).build();
        } catch (Exception e) {
            return Response.ok(createFallbackHtml()).build();
        }
    }

    private String createFallbackHtml() {
        return "<!DOCTYPE html><html><head><title>QUAD Studio</title></head><body><div id=\"app\"></div><script src=\"/ui/assets/index.js\"></script></body></html>";
    }
}