package de.augmentia.quad.core.tool.builtin;

import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

@ApplicationScoped
public class WebFetchTool {

    private static final Pattern URL_PATTERN = Pattern.compile("^https?://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$");
    private static final int MAX_CHARS = 30000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Tool("Fetch a web page and extract its text content.")
    public String webfetch(
            @Param("url") String url) {

        if (url == null || url.isBlank()) {
            return "Error: URL is required";
        }

        if (!URL_PATTERN.matcher(url).matches()) {
            return "Error: Invalid URL format. Only http/https URLs are allowed.";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "QUAD-Bot/1.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "Error: HTTP " + response.statusCode() + " - " + getHttpErrorMessage(response.statusCode());
            }

            String body = response.body();
            String title = extractTitle(body);
            String text = extractText(body);

            StringBuilder result = new StringBuilder();
            if (title != null) {
                result.append("Title: ").append(title).append("\n\n");
            }
            result.append(text);

            if (result.length() > MAX_CHARS) {
                result.setLength(MAX_CHARS);
                result.append("\n\n[Output truncated]");
            }

            return result.toString();
        } catch (Exception e) {
            return "Error fetching URL: " + e.getMessage();
        }
    }

    private String extractTitle(String html) {
        int start = html.indexOf("<title>");
        if (start == -1) return null;
        int end = html.indexOf("</title>", start);
        if (end == -1) return null;
        return html.substring(start + 7, end).trim();
    }

    private String extractText(String html) {
        String text = html
                .replaceAll("(?s)<script[^>]*>.*?</script>", "")
                .replaceAll("(?s)<style[^>]*>.*?</style>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    private String getHttpErrorMessage(int statusCode) {
        return switch (statusCode) {
            case 404 -> "Page not found";
            case 403 -> "Access forbidden";
            case 401 -> "Authentication required";
            case 500, 502, 503, 504 -> "Server error";
            default -> "Request failed";
        };
    }
}