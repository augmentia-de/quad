package de.augmentia.quad.core.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@ApplicationScoped
public class WebSearchTool {

    private static final int MAX_RESULTS = 5;
    private final String apiKey;
    private final String baseUrl = "https://api.tavily.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WebSearchTool() {
        var key = System.getProperty("vault.TAVILY_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("TAVILY_API_KEY");
        }
        if (key == null || key.isBlank()) {
            key = System.getProperty("TAVILY_API_KEY");
        }
        this.apiKey = key;
    }


    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(2000))
            .build();

    @Tool("Performs a web search to retrieve relevant pages, summaries, and URLs for factual information.")
    public String webSearch(
            @Param("query") String query,
            @Param(value = "maxResults", required = false) Integer maxResults) {

        int numResults = (maxResults == null || maxResults <= 0) ? 5 : Math.min(maxResults, 10);

        try {
            var requestBody = String.format(
                    "{\"api_key\":\"%s\",\"query\":\"%s\",\"max_results\":%d}",
                    apiKey, query.replace("\"", "\\\""), MAX_RESULTS);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "Web search request failed with HTTP status: " + response.statusCode();
            }

            return formatSearchResults(query, response.body());
        } catch (Exception e) {
            return "Error performing web search for query '" + query + "': " + e.getMessage();
        }
    }

    private String formatSearchResults(String query, String jsonBody) {
        try {
            var root = MAPPER.readTree(jsonBody);
            var arr = root.get("results");
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                return String.valueOf(buildEmptyJson(query));
            }

            var resultRoot = MAPPER.createObjectNode();
            resultRoot.put("query", query);
            var resultsArr = resultRoot.putArray("results");
            int count = 0;
            for (var item : arr) {
                if (count >= MAX_RESULTS) {
                    break;
                }
                var obj = resultsArr.addObject();
                obj.put("title", item.has("title") ? item.get("title").asText() : "");
                obj.put("url", item.has("url") ? item.get("url").asText() : "");
                obj.put("content", item.has("content") ? item.get("content").asText() : "");
                count++;
            }
            resultRoot.put("totalResults", resultsArr.size());

            return String.valueOf(resultRoot);
        } catch (Exception e) {
            return "Failed to parse results: " + e.getMessage();
        }
    }

    private ObjectNode buildEmptyJson(String query) {
        return MAPPER.createObjectNode()
                .put("query", query)
                .put("totalResults", 0);
    }

}