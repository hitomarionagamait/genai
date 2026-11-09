package com.hito.genai.jira;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class JiraDeploymentReportService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");
    private static final Pattern DEVOPS_PATTERN = Pattern.compile("(?i)\\b(devops|mount secret|secret|configmap|config map|cron.?job|deployment|ingress)\\b");
    private static final Pattern DATABASE_PATTERN = Pattern.compile("(?i)\\b(database|db|enchancement collection|collection|mongodb|mongo|schema|index|migration|ddl)\\b");
    private static final Pattern DDL_PATTERN = Pattern.compile("(?i)\\b(ddl|database migration|schema|collection|index|rollback)\\b");
    private static final Pattern HOTFIX_SANITY_PATTERN = Pattern.compile("(?i)\\b(hotfix|hot-fix|sanity)\\b");
    private static final Pattern SIT_PATTERN = Pattern.compile("(?i)\\bSIT\\b");
    private static final Pattern UAT_PATTERN = Pattern.compile("(?i)\\bUAT\\b");
    private static final Pattern PROD_PATTERN = Pattern.compile("(?i)\\bPROD(?:UCTION)?\\b");
    private static final Set<String> REQUESTED_FIELD_NAMES = Set.of(
            "Epic Link", "Sprint", "Story Points", "Custom field (Story Points)",
            "Using PRDA API?", "Repository/Services", "Repository", "Collections Related",
            "Collections", "Collections Note", "Collection Note", "Bitbucket Branch", "Need In UAT", "Value", "Note",
            "Status SIT", "Status UAT", "Status Prod", "Status Production",
            "ALREADY CHECK/IMPROVE", "Rollback DDL", "Assets Folder", "Environment",
            "DevOps Type", "Pull Request To Main");

    private final JiraApiClient jiraApiClient;
    private final JiraProperties properties;

    public JiraDeploymentReportService(JiraApiClient jiraApiClient, JiraProperties properties) {
        this.jiraApiClient = jiraApiClient;
        this.properties = properties;
    }

    public List<JiraApiClient.VersionOption> getAvailableFixVersions() {
        return jiraApiClient.getProjectVersions();
    }

    public DeploymentReport createReport(String fixVersion) {
        String selectedVersion = validateFixVersion(fixVersion);
        String jql = buildJql(selectedVersion);
        Map<String, String> fieldIds = jiraApiClient.getFieldIdsByName();
        List<String> requestedFields = buildRequestedFields(fieldIds);
        List<JsonNode> issues = jiraApiClient.searchAllIssues(jql, requestedFields);

        List<DeploymentReport.ChecklistRow> checklist = new ArrayList<>();
        List<DeploymentReport.AllTicketRow> allTickets = new ArrayList<>();
        List<DeploymentReport.HotfixRow> hotfix = new ArrayList<>();
        List<DeploymentReport.DatabaseRow> databases = new ArrayList<>();
        List<DeploymentReport.DdlRow> ddlRows = new ArrayList<>();
        List<DeploymentReport.DevOpsRow> sit = new ArrayList<>();
        List<DeploymentReport.DevOpsRow> uat = new ArrayList<>();
        List<DeploymentReport.DevOpsRow> production = new ArrayList<>();
        List<DeploymentReport.DevOpsRow> unclassified = new ArrayList<>();
        Map<String, DeploymentReport.RepoImpactRow> repositories = new LinkedHashMap<>();

        int rowNumber = 0;
        for (JsonNode issue : issues) {
            rowNumber++;
            JsonNode fields = issue.path("fields");
            String key = text(issue.path("key"));
            String summary = text(fields.path("summary"));
            String ticketUrl = ticketUrl(key);
            String issueType = text(fields.path("issuetype").path("name"));
            String labels = nodeText(fields.path("labels"));
            String components = componentNames(fields.path("components"));
            String customRepository = customText(fields, fieldIds, "Repository/Services", "Repository");
            String repository = firstNonBlank(customRepository, components);
            String collections = customText(fields, fieldIds, "Collections Related", "Collections");
            String epic = firstNonBlank(customText(fields, fieldIds, "Epic Link"), text(fields.path("parent").path("key")));
            String environment = firstNonBlank(
                    customText(fields, fieldIds, "Environment"),
                    environmentFromText(summary + " " + labels));

            checklist.add(new DeploymentReport.ChecklistRow(rowNumber, summary, key, ticketUrl));
            allTickets.add(toAllTicket(rowNumber, issue, fields, fieldIds, key, ticketUrl, issueType, summary));

            if (HOTFIX_SANITY_PATTERN.matcher(summary + " " + labels).find()) {
                hotfix.add(new DeploymentReport.HotfixRow(hotfix.size() + 1, key, ticketUrl));
            }

            if (DATABASE_PATTERN.matcher(summary + " " + labels + " " + issueType).find() || !collections.isBlank()) {
                databases.add(new DeploymentReport.DatabaseRow(databases.size() + 1,
                        firstNonBlank(collections, summary), key, ticketUrl));
            }

            if (DDL_PATTERN.matcher(summary + " " + labels + " " + issueType).find() || !customText(fields, fieldIds, "Bitbucket Branch").isBlank()) {
                ddlRows.add(toDdlRow(ddlRows.size() + 1, key, ticketUrl, fields, fieldIds, collections));
            }

            if (DEVOPS_PATTERN.matcher(summary + " " + labels + " " + issueType).find()) {
                DeploymentReport.DevOpsRow devOpsRow = new DeploymentReport.DevOpsRow(
                        0, key, ticketUrl,
                        firstNonBlank(repository, "Not mapped"), epic,
                        firstNonBlank(customText(fields, fieldIds, "DevOps Type"), issueType),
                        customText(fields, fieldIds, "Note"),
                        customText(fields, fieldIds, "Need In UAT"),
                        deploymentStatus(fields, fieldIds, environment));
                String normalizedEnvironment = environment.toUpperCase(Locale.ROOT);
                if (SIT_PATTERN.matcher(normalizedEnvironment).find()) {
                    sit.add(withNo(devOpsRow, sit.size() + 1));
                } else if (UAT_PATTERN.matcher(normalizedEnvironment).find()) {
                    uat.add(withNo(devOpsRow, uat.size() + 1));
                } else if (PROD_PATTERN.matcher(normalizedEnvironment).find()) {
                    production.add(withNo(devOpsRow, production.size() + 1));
                } else {
                    unclassified.add(withNo(devOpsRow, unclassified.size() + 1));
                }
            }

            if (!repository.isBlank()) {
                for (String repo : repository.split(",\\s*")) {
                    repositories.putIfAbsent(repo.toLowerCase(Locale.ROOT),
                            new DeploymentReport.RepoImpactRow(repositories.size() + 1, repo,
                                    "", "", "", "", "", "", customText(fields, fieldIds, "Pull Request To Main")));
                }
            } else {
                repositories.putIfAbsent("__not_mapped__",
                        new DeploymentReport.RepoImpactRow(repositories.size() + 1, "Not mapped",
                                "", "", "", "", "", "", ""));
            }
        }

        List<DeploymentReport.RepoImpactRow> repoRows = new ArrayList<>(repositories.values());
        return new DeploymentReport(selectedVersion, jql,
                ZonedDateTime.now(ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'WIB'")),
                issues.size(), (int) repoRows.stream().filter(r -> !r.repository().equals("Not mapped")).count(),
                sit.size() + uat.size() + production.size() + unclassified.size(),
                databases.size(), ddlRows.size(), hotfix.size(),
                checklist, repoRows, sit, uat, production, unclassified,
                databases, ddlRows, allTickets, hotfix);
    }

    public String buildJql(String fixVersion) {
        String escaped = fixVersion.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"Platform[Dropdown]\" = Backend AND fixVersion = \"" + escaped
                + "\" AND worktype != Bug AND status = Done ORDER BY created DESC";
    }

    private String validateFixVersion(String fixVersion) {
        if (fixVersion == null || fixVersion.isBlank()) throw new IllegalArgumentException("fixVersion wajib dipilih.");
        String value = fixVersion.trim();
        if (value.length() > 100 || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("fixVersion tidak valid.");
        }
        return value;
    }

    private List<String> buildRequestedFields(Map<String, String> fieldIds) {
        List<String> fields = new ArrayList<>(List.of("summary", "issuetype", "status", "project", "components",
                "assignee", "reporter", "parent", "fixVersions", "created", "updated", "priority",
                "resolution", "labels", "issuelinks"));
        for (String fieldName : REQUESTED_FIELD_NAMES) {
            String id = fieldIds.get(normalize(fieldName));
            if (id != null && !fields.contains(id)) fields.add(id);
        }
        return fields;
    }

    private DeploymentReport.AllTicketRow toAllTicket(int no, JsonNode issue, JsonNode fields,
                                                       Map<String, String> fieldIds, String key,
                                                       String ticketUrl, String issueType, String summary) {
        JsonNode parent = fields.path("parent");
        List<String> sprints = sprintNames(fields, fieldIds);
        return new DeploymentReport.AllTicketRow(no, issueType, key, ticketUrl, summary,
                customText(fields, fieldIds, "Using PRDA API?"),
                firstNonBlank(customText(fields, fieldIds, "Epic Link"), text(parent.path("key"))),
                text(parent.path("fields").path("summary")),
                text(fields.path("assignee").path("displayName")),
                text(fields.path("assignee").path("accountId")),
                text(fields.path("reporter").path("displayName")),
                text(fields.path("reporter").path("accountId")),
                sprintAt(sprints, 0), sprintAt(sprints, 1), sprintAt(sprints, 2),
                sprintAt(sprints, 3), sprintAt(sprints, 4),
                text(fields.path("status").path("name")),
                text(fields.path("priority").path("name")),
                text(fields.path("resolution").path("name")),
                text(fields.path("created")), text(fields.path("updated")),
                customText(fields, fieldIds, "Story Points", "Custom field (Story Points)"));
    }

    private DeploymentReport.DdlRow toDdlRow(int no, String key, String ticketUrl, JsonNode fields,
                                              Map<String, String> fieldIds, String collections) {
        return new DeploymentReport.DdlRow(no, key, ticketUrl,
                customText(fields, fieldIds, "Bitbucket Branch"),
                customText(fields, fieldIds, "Note"), collections,
                customText(fields, fieldIds, "Collections Note", "Collection Note"),
                customText(fields, fieldIds, "Status SIT"),
                customText(fields, fieldIds, "Status UAT"),
                customText(fields, fieldIds, "ALREADY CHECK/IMPROVE"),
                customText(fields, fieldIds, "Status Prod", "Status Production"),
                customText(fields, fieldIds, "Rollback DDL"),
                customText(fields, fieldIds, "Assets Folder"));
    }


    private String deploymentStatus(JsonNode fields, Map<String, String> fieldIds, String environment) {
        String normalized = environment == null ? "" : environment.toUpperCase(Locale.ROOT);
        if (SIT_PATTERN.matcher(normalized).find()) return customText(fields, fieldIds, "Status SIT");
        if (UAT_PATTERN.matcher(normalized).find()) return customText(fields, fieldIds, "Status UAT");
        if (PROD_PATTERN.matcher(normalized).find()) return customText(fields, fieldIds, "Status Prod", "Status Production");
        return "";
    }

    private DeploymentReport.DevOpsRow withNo(DeploymentReport.DevOpsRow row, int no) {
        return new DeploymentReport.DevOpsRow(no, row.issueKey(), row.ticketUrl(), row.repository(),
                row.epic(), row.type(), row.note(), row.needInUat(), row.status());
    }

    private List<String> sprintNames(JsonNode fields, Map<String, String> fieldIds) {
        String id = fieldIds.get(normalize("Sprint"));
        if (id == null) return List.of();
        JsonNode sprintNode = fields.path(id);
        List<String> values = new ArrayList<>();
        if (sprintNode.isArray()) {
            sprintNode.forEach(item -> {
                String name = firstNonBlank(text(item.path("name")), text(item));
                if (!name.isBlank()) values.add(name);
            });
        } else if (sprintNode.isTextual()) {
            values.add(sprintNode.asString());
        }
        return values;
    }

    private String sprintAt(List<String> values, int index) {
        return index < values.size() ? values.get(index) : "";
    }

    private String customText(JsonNode fields, Map<String, String> fieldIds, String... names) {
        for (String name : names) {
            String id = fieldIds.get(normalize(name));
            if (id != null) {
                String value = nodeText(fields.path(id));
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private String nodeText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isValueNode()) return node.asString("");
        if (node.isArray()) {
            return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                    .map(this::nodeText).filter(s -> !s.isBlank()).collect(Collectors.joining(", "));
        }
        for (String property : List.of("displayName", "name", "value", "key", "accountId")) {
            String value = text(node.path(property));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String componentNames(JsonNode components) {
        if (components == null || !components.isArray()) return "";
        List<String> names = new ArrayList<>();
        components.forEach(c -> {
            String name = text(c.path("name"));
            if (!name.isBlank()) names.add(name);
        });
        return String.join(", ", names);
    }

    private String environmentFromText(String value) {
        String upper = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (SIT_PATTERN.matcher(upper).find()) return "SIT";
        if (UAT_PATTERN.matcher(upper).find()) return "UAT";
        if (PROD_PATTERN.matcher(upper).find()) return "PROD";
        return "";
    }

    private String ticketUrl(String issueKey) {
        if (issueKey == null || issueKey.isBlank()) return "";
        return properties.getBaseUrl().replaceAll("/+$", "") + "/browse/" + issueKey;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asString("");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
