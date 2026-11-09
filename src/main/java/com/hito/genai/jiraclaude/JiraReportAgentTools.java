package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Tool agent: hanya MEMBACA data report yang sama dengan dashboard (tidak ada aksi tulis ke Jira).
 * ID akun (accountId) sengaja tidak pernah dikirim ke model.
 */
@Component
public class JiraReportAgentTools {

    private final JiraReportService service;

    public JiraReportAgentTools(JiraReportService service) {
        this.service = service;
    }

    @Tool(description = "Daftar fixVersion yang tersedia di Jira (terbaru lebih dulu) beserta status rilisnya.")
    public String listFixVersions() {
        try {
            List<VersionOption> v = service.fixVersions(false);
            if (v.isEmpty()) {
                return "(tidak ada versi; jira.project-keys belum diisi atau project belum punya versi)";
            }
            return v.stream().limit(30)
                    .map(o -> o.name() + (o.released() ? " (sudah rilis" + (o.releaseDate().isBlank() ? "" : " " + o.releaseDate()) + ")" : " (belum rilis)"))
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Ringkasan release untuk satu fixVersion: jumlah tiket per tipe/status/assignee/prioritas/epic, "
            + "total story points, tiket tanpa story points, ringkasan tiket DevOps per environment, DDL, hotfix/sanity, "
            + "JQL yang dipakai, dan peringatan data.")
    public String getReleaseSummary(@ToolParam(description = "Contoh: v2.15.0") String fixVersion) {
        try {
            Report r = service.report(fixVersion, false);
            Summary s = r.summary();
            StringBuilder sb = new StringBuilder();
            sb.append("fixVersion: ").append(r.fixVersion()).append('\n');
            sb.append("Total tiket: ").append(s.totalTickets()).append(", story points: ").append(s.totalStoryPoints())
                    .append(", tiket tanpa story points: ").append(s.withoutStoryPoints())
                    .append(", jumlah assignee: ").append(s.assignees())
                    .append(", pakai PRDA API: ").append(s.usingPrdaApi()).append('\n');
            appendStats(sb, "Per tipe", s.byIssueType());
            appendStats(sb, "Per status", s.byStatus());
            appendStats(sb, "Per assignee", s.byAssignee());
            appendStats(sb, "Per prioritas", s.byPriority());
            appendStats(sb, "Per epic/parent", s.byEpic());
            sb.append("DevOps: ");
            for (String env : List.of("SIT", "UAT", "PROD")) {
                List<DevOpsRow> rows = r.devops().getOrDefault(env, List.of());
                sb.append(env).append("=").append(rows.size()).append(" (")
                        .append(rows.stream().filter(d -> d.status().equals("SUCCESS")).count()).append(" success, ")
                        .append(rows.stream().filter(d -> d.status().equals("ON PROGRESS")).count()).append(" on progress, ")
                        .append(rows.stream().filter(d -> d.status().equals("FAILED")).count()).append(" failed)  ");
            }
            sb.append("\nDevOps tanpa label environment: ").append(r.devopsUnknownEnv().size()).append('\n');
            DevSummary dv = r.dev();
            if (dv.enabled()) {
                sb.append("Development (branch/PR Bitbucket): tiket dengan PR=").append(dv.ticketsWithPr())
                        .append(", tiket tanpa data development=").append(dv.ticketsWithoutDev())
                        .append(dv.unavailable() ? " (data Development TIDAK tersedia)" : "").append('\n');
            }
            sb.append("DDL: ").append(r.ddl().size()).append(", Hotfix/Sanity: ").append(r.hotfix().size())
                    .append(", repository terdampak: ").append(r.repos().size()).append('\n');
            r.sections().forEach(x -> sb.append("JQL ").append(x.name()).append(" (").append(x.count()).append("): ").append(x.jql()).append('\n'));
            r.warnings().forEach(w -> sb.append("PERINGATAN: ").append(w).append('\n'));
            return sb.toString();
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Cari/filter tiket (sheet All Ticket) pada satu fixVersion. Semua filter opsional dan digabung dengan AND. "
            + "Mengembalikan: key | tipe | status | assignee | story points | summary.")
    public String queryTickets(
            @ToolParam(description = "Contoh: v2.15.0") String fixVersion,
            @ToolParam(description = "Kata kunci pada summary/key/epic", required = false) String keyword,
            @ToolParam(description = "Nama assignee (sebagian nama cukup)", required = false) String assignee,
            @ToolParam(description = "Tipe issue, mis. Story/Task/Sub-task", required = false) String issueType,
            @ToolParam(description = "Nama status, mis. Done", required = false) String status,
            @ToolParam(description = "true = hanya tiket yang belum punya story points", required = false) Boolean onlyWithoutStoryPoints,
            @ToolParam(description = "Maksimum baris (default 30, maks 100)", required = false) Integer limit) {
        try {
            Report r = service.report(fixVersion, false);
            int max = limit == null ? 30 : Math.max(1, Math.min(100, limit));
            List<Ticket> hits = r.tickets().stream()
                    .filter(t -> blank(keyword) || contains(t.summary(), keyword) || contains(t.key(), keyword)
                            || contains(t.parentSummary(), keyword) || contains(t.parentKey(), keyword))
                    .filter(t -> blank(assignee) || contains(t.assignee(), assignee))
                    .filter(t -> blank(issueType) || t.issueType().equalsIgnoreCase(issueType.trim()))
                    .filter(t -> blank(status) || t.status().equalsIgnoreCase(status.trim()))
                    .filter(t -> !Boolean.TRUE.equals(onlyWithoutStoryPoints) || t.storyPoints() == null)
                    .toList();
            if (hits.isEmpty()) {
                return "(tidak ada tiket yang cocok)";
            }
            StringBuilder sb = new StringBuilder("Cocok: " + hits.size() + " tiket (ditampilkan maks " + max + ")\n");
            hits.stream().limit(max).forEach(t -> sb.append(t.key()).append(" | ").append(t.issueType()).append(" | ")
                    .append(t.status()).append(" | ").append(t.assignee().isBlank() ? "-" : t.assignee()).append(" | SP ")
                    .append(t.storyPoints() == null ? "-" : t.storyPoints()).append(" | ").append(t.summary()).append('\n'));
            return sb.toString();
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Daftar tiket DevOps (configmap, secret, cronjob, dst.) untuk satu fixVersion, per environment. "
            + "Mengembalikan: key | env | tipe | repository | status | summary.")
    public String getDevOpsTickets(
            @ToolParam(description = "Contoh: v2.15.0") String fixVersion,
            @ToolParam(description = "SIT, UAT, atau PROD. Kosongkan untuk semua.", required = false) String environment) {
        try {
            Report r = service.report(fixVersion, false);
            String env = blank(environment) ? "" : environment.trim().toUpperCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            r.devops().forEach((e, rows) -> {
                if (env.isEmpty() || env.equals(e)) {
                    rows.forEach(d -> row(sb, d));
                }
            });
            if (env.isEmpty()) {
                r.devopsUnknownEnv().forEach(d -> row(sb, d));
            }
            return sb.isEmpty() ? "(tidak ada tiket DevOps; mungkin jira.jql.devops belum diisi)" : sb.toString();
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Info Development (branch dan pull request Bitbucket) dari panel Development Jira untuk satu fixVersion. "
            + "Tanpa filter: daftar repository terdampak beserta status PR ke main. Dengan ticketKey: detail branch/PR tiket itu. "
            + "Dengan repository: PR dan tiket pada repository itu.")
    public String getDevelopmentInfo(
            @ToolParam(description = "Contoh: v2.15.0") String fixVersion,
            @ToolParam(description = "Key tiket, mis. BE-501", required = false) String ticketKey,
            @ToolParam(description = "Nama repository (sebagian nama cukup)", required = false) String repository) {
        try {
            Report r = service.report(fixVersion, false);
            if (!r.dev().enabled()) {
                return "(pengambilan data Development dimatikan: jira.development.enabled=false)";
            }
            StringBuilder sb = new StringBuilder();
            if (r.dev().unavailable()) {
                sb.append("PERHATIAN: data Development tidak tersedia dari Jira (endpoint internal dev-status ditolak).\n");
            }
            if (!blank(ticketKey)) {
                DevInfo info = r.development().get(ticketKey.trim().toUpperCase(Locale.ROOT));
                if (info == null) {
                    return sb + "(tidak ada branch/PR untuk " + ticketKey.trim() + ")";
                }
                sb.append("Branch: ").append(info.branchNames()).append('\n');
                info.pullRequests().forEach(p -> sb.append("PR ").append(p.id()).append(" | ").append(p.status()).append(" | ")
                        .append(p.repository()).append(" | ").append(p.source()).append(" -> ").append(p.destination())
                        .append(" | ").append(p.title()).append(" | ").append(p.url()).append('\n'));
                return sb.toString();
            }
            List<RepoRow> repos = r.repos().stream()
                    .filter(x -> blank(repository) || contains(x.name(), repository)).toList();
            if (repos.isEmpty()) {
                return sb + "(tidak ada repository yang cocok)";
            }
            for (RepoRow x : repos) {
                sb.append(x.name()).append(" | PR ke main: ").append(x.prsToMain().isEmpty() ? "tidak ada" : x.prToMainLabel())
                        .append(" | PR lain: ").append(x.otherPrs().size())
                        .append(" | tiket: ").append(String.join(",", x.tickets()))
                        .append(x.fromDevOps() ? " | ada tiket DevOps" : "").append('\n');
                if (!blank(repository)) {
                    x.prsToMain().forEach(p -> sb.append("   -> main: ").append(p.ticketKey()).append(' ').append(p.status()).append(' ').append(p.url()).append('\n'));
                    x.otherPrs().forEach(p -> sb.append("   -> ").append(p.destination()).append(": ").append(p.ticketKey()).append(' ').append(p.status()).append(' ').append(p.url()).append('\n'));
                }
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private static void row(StringBuilder sb, DevOpsRow d) {
        sb.append(d.key()).append(" | ").append(d.env().isBlank() ? "(env?)" : d.env()).append(" | ")
                .append(d.type().isBlank() ? "(tipe?)" : d.type()).append(" | ")
                .append(d.repository().isBlank() ? "(repo?)" : d.repository()).append(" | ")
                .append(d.status()).append(" | ").append(d.label()).append('\n');
    }

    private static void appendStats(StringBuilder sb, String title, List<Stat> stats) {
        sb.append(title).append(": ")
                .append(stats.stream().map(s -> s.label() + "=" + s.count() + " (SP " + s.storyPoints() + ")")
                        .collect(Collectors.joining("; "))).append('\n');
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean contains(String hay, String needle) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT));
    }
}
