package com.hito.genai.jira;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JiraApiClient {
    private final JiraProperties properties;

    public JiraApiClient(JiraProperties properties) {
        this.properties = properties;
    }

    private RestClient client() {
        properties.validate();
        return RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .defaultHeaders(headers -> {
                    headers.setBasicAuth(properties.getEmail(), properties.getApiToken());
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .build();
    }

    public List<VersionOption> getProjectVersions() {
        JsonNode response = client().get()
                .uri("/rest/api/3/project/{projectKey}/versions", properties.getProjectKey())
                .retrieve()
                .body(JsonNode.class);

        List<VersionOption> versions = new ArrayList<>();
        if (response == null || !response.isArray()) return versions;
        response.forEach(item -> {
            String name = text(item.path("name"));
            if (!name.isBlank()) {
                versions.add(new VersionOption(text(item.path("id")), name,
                        item.path("released").asBoolean(false), item.path("archived").asBoolean(false),
                        text(item.path("releaseDate"))));
            }
        });
        // Preserve Jira's project version ordering instead of lexicographically sorting names.
        return versions;
    }

    public Map<String, String> getFieldIdsByName() {
        JsonNode response = client().get()
                .uri("/rest/api/3/field")
                .retrieve()
                .body(JsonNode.class);

        Map<String, String> result = new LinkedHashMap<>();
        if (response != null && response.isArray()) {
            response.forEach(field -> {
                String name = text(field.path("name"));
                String id = text(field.path("id"));
                if (!name.isBlank() && !id.isBlank()) result.put(normalize(name), id);
            });
        }
        return result;
    }

    public List<JsonNode> searchAllIssues(String jql, List<String> fields) {
        ArrayNode combined = JsonNodeFactory.instance.arrayNode();
        String nextPageToken = null;
        int maxPages = Math.max(1, properties.getMaxPages());
        boolean finished = false;

        for (int page = 0; page < maxPages; page++) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jql", jql);
            request.put("maxResults", Math.max(1, Math.min(properties.getMaxResults(), 100)));
            request.put("fields", fields);
            if (nextPageToken != null && !nextPageToken.isBlank()) {
                request.put("nextPageToken", nextPageToken);
            }

            JsonNode response = client().post()
                    .uri("/rest/api/3/search/jql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) break;
            JsonNode issues = response.path("issues");
            if (issues.isArray()) issues.forEach(issue -> combined.add(issue.deepCopy()));

            boolean isLast = response.path("isLast").asBoolean(false);
            nextPageToken = text(response.path("nextPageToken"));
            if (isLast || nextPageToken.isBlank()) {
                finished = true;
                break;
            }
        }
        if (!finished && nextPageToken != null && !nextPageToken.isBlank()) {
            throw new IllegalStateException("Hasil Jira melebihi batas " + (maxPages * Math.max(1, Math.min(properties.getMaxResults(), 100)))
                    + " issue. Naikkan jira.max-pages untuk mengambil seluruh data.");
        }

        List<JsonNode> result = new ArrayList<>();
        combined.forEach(result::add);
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asString("");
    }

    private static String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    public record VersionOption(String id, String name, boolean released, boolean archived, String releaseDate) { }
}
