package com.hito.genai.jira;

import com.hito.genai.jira.BugModels.Bug;
import com.hito.genai.jiraclaude.JiraIssueMapper;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Mengubah JSON issue Jira menjadi {@link Bug} (plus waktu mentah untuk statistik). */
final class BugIssueMapper {

    /** Field yang diminta ke Jira. */
    static final List<String> FIELDS = List.of("summary", "issuetype", "status", "priority", "assignee", "reporter",
            "created", "updated", "resolutiondate", "labels", "fixVersions");

    private static final DateTimeFormatter JIRA_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    record Parsed(Bug bug, Instant created, Instant resolved) {
    }

    private BugIssueMapper() {
    }

    static Parsed map(JsonNode issue, String browseBase, Set<String> criticalLower, int agingDays, Instant now) {
        JsonNode f = issue.path("fields");
        String key = issue.path("key").asString("");
        String category = f.path("status").path("statusCategory").path("key").asString("");
        boolean open = !"done".equalsIgnoreCase(category);
        String priority = f.path("priority").path("name").asString("");

        Instant created = instant(f.path("created").asString(""));
        Instant resolved = instant(f.path("resolutiondate").asString(""));
        long age = 0;
        if (created != null) {
            Instant end = open || resolved == null ? now : resolved;
            age = Math.max(0, Duration.between(created, end).toDays());
        }

        List<String> versions = new ArrayList<>();
        for (JsonNode v : f.path("fixVersions")) {
            String n = v.path("name").asString("");
            if (!n.isBlank() && !versions.contains(n)) {
                versions.add(n);
            }
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode l : f.path("labels")) {
            labels.add(l.asString(""));
        }

        Bug bug = new Bug(key, browseBase + "/browse/" + key, f.path("summary").asString(""),
                f.path("issuetype").path("name").asString(""), f.path("status").path("name").asString(""), category,
                priority, f.path("assignee").path("displayName").asString(""),
                f.path("reporter").path("displayName").asString(""),
                JiraIssueMapper.date(f.path("created").asString("")), JiraIssueMapper.date(f.path("updated").asString("")),
                JiraIssueMapper.date(f.path("resolutiondate").asString("")), List.copyOf(labels), List.copyOf(versions),
                open, age, open && age > agingDays, criticalLower.contains(priority.toLowerCase(Locale.ROOT)));
        return new Parsed(bug, created, resolved);
    }

    static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw, JIRA_TS).toInstant();
        } catch (RuntimeException e) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }
}
