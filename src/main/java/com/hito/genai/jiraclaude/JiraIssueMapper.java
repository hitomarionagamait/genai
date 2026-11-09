package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.Ticket;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Mengubah JSON issue Jira menjadi {@link Ticket}. Toleran terhadap field yang kosong / bentuk berbeda. */
public final class JiraIssueMapper {

    /** ID customfield hasil pencarian berdasarkan nama (boleh null). */
    public record FieldIds(String storyPoints, String sprint, String usingPrdaApi) {
    }

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter JIRA_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private JiraIssueMapper() {
    }

    /** Daftar field yang diminta ke Jira (default search hanya mengembalikan id). */
    public static List<String> requestedFields(FieldIds ids) {
        List<String> f = new ArrayList<>(List.of("summary", "issuetype", "parent", "assignee", "reporter", "status",
                "priority", "resolution", "created", "updated"));
        for (String id : new String[]{ids.storyPoints(), ids.sprint(), ids.usingPrdaApi()}) {
            if (id != null && !f.contains(id)) {
                f.add(id);
            }
        }
        return f;
    }

    public static Ticket toTicket(JsonNode issue, FieldIds ids, String browseBase) {
        JsonNode f = issue.path("fields");
        String key = issue.path("key").asString("");

        List<String> sprints = new ArrayList<>();
        if (ids.sprint() != null) {
            JsonNode sp = f.path(ids.sprint());
            if (sp.isArray()) {
                for (JsonNode s : sp) {
                    String n = s.isObject() ? s.path("name").asString("") : s.asString("");
                    if (!n.isBlank()) {
                        sprints.add(n);
                    }
                }
            }
        }

        Double points = null;
        if (ids.storyPoints() != null) {
            JsonNode sp = f.path(ids.storyPoints());
            if (sp.isNumber()) {
                points = sp.asDouble();
            }
        }

        JsonNode parent = f.path("parent");
        return new Ticket(
                key,
                issue.path("id").asString(""),
                browseBase + "/browse/" + key,
                f.path("issuetype").path("name").asString(""),
                f.path("summary").asString(""),
                ids.usingPrdaApi() == null ? "" : text(f.path(ids.usingPrdaApi())),
                parent.path("key").asString(""),
                parent.path("fields").path("summary").asString(""),
                f.path("assignee").path("displayName").asString(""),
                f.path("assignee").path("accountId").asString(""),
                f.path("reporter").path("displayName").asString(""),
                f.path("reporter").path("accountId").asString(""),
                sprints,
                f.path("status").path("name").asString(""),
                f.path("status").path("statusCategory").path("key").asString(""),
                f.path("priority").path("name").asString(""),
                f.path("resolution").path("name").asString(""),
                date(f.path("created").asString("")),
                date(f.path("updated").asString("")),
                points);
    }

    /** Nilai field generik (string, angka, objek opsi, atau array) menjadi teks. */
    static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        if (n.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode e : n) {
                String t = text(e);
                if (!t.isBlank()) {
                    parts.add(t);
                }
            }
            return String.join(", ", parts);
        }
        if (n.isObject()) {
            for (String k : new String[]{"value", "name", "displayName", "summary", "key"}) {
                if (n.hasNonNull(k)) {
                    return n.get(k).asString("");
                }
            }
            return "";
        }
        return n.asString("");
    }

    public static String date(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return OffsetDateTime.parse(raw, JIRA_TS).atZoneSameInstant(WIB).format(OUT);
        } catch (RuntimeException e) {
            try {
                return OffsetDateTime.parse(raw).atZoneSameInstant(WIB).format(OUT);
            } catch (RuntimeException e2) {
                return raw;
            }
        }
    }
}
