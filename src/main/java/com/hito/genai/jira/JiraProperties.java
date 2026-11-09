package com.hito.genai.jira;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jira")
public class JiraProperties {
    private String baseUrl = "";
    private String email = "";
    private String apiToken = "";
    private String projectKey = "";
    private String defaultFixVersion = "v2.15.0";
    private int maxResults = 100;
    private int maxPages = 50;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    public String getDefaultFixVersion() { return defaultFixVersion; }
    public void setDefaultFixVersion(String defaultFixVersion) { this.defaultFixVersion = defaultFixVersion; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }

    public boolean isConfigured() {
        return hasText(baseUrl) && hasText(email) && hasText(apiToken) && hasText(projectKey);
    }

    public void validate() {
        if (!hasText(baseUrl) || !hasText(email) || !hasText(apiToken) || !hasText(projectKey)) {
            throw new IllegalStateException("Konfigurasi Jira belum lengkap. Set JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN, dan JIRA_PROJECT_KEY.");
        }
        if (!baseUrl.startsWith("https://")) {
            throw new IllegalStateException("JIRA_BASE_URL harus menggunakan HTTPS.");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
