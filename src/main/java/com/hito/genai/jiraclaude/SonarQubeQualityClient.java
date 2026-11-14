package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.RepositoryQualityModels.SonarInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Read-only SonarQube Web API client for branch Quality Gate and coverage measures. */
@Component
public class SonarQubeQualityClient {
    private static final Logger log = LoggerFactory.getLogger(SonarQubeQualityClient.class);
    private final RepositoryQualityProperties properties;
    private final RestClient rest;

    public SonarQubeQualityClient(RestClient.Builder builder, RepositoryQualityProperties properties) {
        this.properties = properties;
        String base = properties.getSonar().getBaseUrl();
        while (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base == null || base.isBlank()) base = "http://localhost";
        RestClient.Builder clientBuilder = builder.clone().baseUrl(base);
        String token = properties.getSonar().getToken();
        if (token != null && !token.isBlank()) {
            clientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());
        }
        this.rest = clientBuilder.build();
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getSonar().getBaseUrl() != null && !properties.getSonar().getBaseUrl().isBlank()
                && properties.getSonar().getToken() != null && !properties.getSonar().getToken().isBlank();
    }

    public SonarInfo inspect(String projectKey, String branch, boolean includeCoverage) {
        if (!isConfigured()) {
            return new SonarInfo("NOT_CONFIGURED", "", "", "", "SONAR_BASE_URL atau SONAR_TOKEN belum dikonfigurasi.");
        }

        String gate = "UNAVAILABLE";
        String coverage = "";
        String analysisDate = "";
        String analysisRevision = "";
        StringBuilder note = new StringBuilder();

        try {
            JsonNode status = rest.get().uri(uriBuilder -> {
                        var uri = uriBuilder.path("/api/qualitygates/project_status").queryParam("projectKey", projectKey);
                        if (branch != null && !branch.equalsIgnoreCase("main")) uri.queryParam("branch", branch);
                        return uri.build();
                    }).retrieve().body(JsonNode.class);
            gate = status == null ? "UNKNOWN" : status.path("projectStatus").path("status").asString("UNKNOWN");
            if (status != null && status.path("projectStatus").hasNonNull("status")) {
                gate = status.path("projectStatus").path("status").asString("UNKNOWN").toUpperCase();
            }
        } catch (RestClientException ex) {
            note.append("Quality Gate: ").append(shortMessage(ex));
            log.warn("Sonar Quality Gate tidak tersedia untuk {} branch {}: {}", projectKey, branch, ex.getMessage());
        }

        if (includeCoverage) {
            try {
                JsonNode analyses = rest.get().uri(uriBuilder -> {
                            var uri = uriBuilder.path("/api/project_analyses/search")
                                    .queryParam("project", projectKey).queryParam("ps", 1);
                            if (branch != null && !branch.equalsIgnoreCase("main")) uri.queryParam("branch", branch);
                            return uri.build();
                        }).retrieve().body(JsonNode.class);
                JsonNode latestAnalysis = analyses != null && analyses.path("analyses").isArray()
                        && analyses.path("analyses").size() > 0 ? analyses.path("analyses").get(0) : null;
                if (latestAnalysis != null) {
                    if (analysisDate.isBlank()) analysisDate = latestAnalysis.path("date").asString("");
                    analysisRevision = latestAnalysis.path("revision").asString("");
                }
            } catch (RestClientException ex) {
                append(note, "Revision analisis Sonar: " + shortMessage(ex));
                log.warn("Sonar analysis metadata tidak tersedia untuk {} branch {}: {}", projectKey, branch, ex.getMessage());
            }
            try {
                JsonNode measures = rest.get().uri(uriBuilder -> {
                            var uri = uriBuilder.path("/api/measures/component")
                                    .queryParam("component", projectKey).queryParam("metricKeys", "coverage");
                            if (branch != null && !branch.equalsIgnoreCase("main")) uri.queryParam("branch", branch);
                            return uri.build();
                        }).retrieve().body(JsonNode.class);
                JsonNode component = measures == null ? null : measures.path("component");
                String measuredAnalysisDate = component == null ? "" : component.path("analysisDate").asString("");
                if (!measuredAnalysisDate.isBlank()) analysisDate = measuredAnalysisDate;
                JsonNode measureList = component == null ? null : component.path("measures");
                if (measureList != null && measureList.isArray()) {
                    for (JsonNode measure : measureList) {
                        if ("coverage".equals(measure.path("metric").asString(""))) {
                            String value = measure.path("value").asString("");
                            if (!value.isBlank()) coverage = value + "%";
                        }
                    }
                }
                if (coverage.isBlank()) append(note, "Coverage metric tidak tersedia pada branch ini.");
            } catch (RestClientException ex) {
                append(note, "Coverage: " + shortMessage(ex));
                log.warn("Sonar coverage tidak tersedia untuk {} branch {}: {}", projectKey, branch, ex.getMessage());
            }
        }

        return new SonarInfo(gate, coverage, analysisDate, analysisRevision, note.toString());
    }

    private static void append(StringBuilder target, String value) {
        if (target.length() > 0) target.append(" | ");
        target.append(value);
    }

    private static String shortMessage(RestClientException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
