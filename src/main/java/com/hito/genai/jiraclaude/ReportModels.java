package com.hito.genai.jiraclaude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Model data report. Semua field berupa String/angka agar mudah dirender Thymeleaf dan di-serialize JSON. */
public final class ReportModels {

    private ReportModels() {
    }

    /** Satu baris sheet "All Ticket". */
    public record Ticket(String key, String issueId, String link, String issueType, String summary, String usingPrdaApi,
                         String parentKey, String parentSummary, String assignee, String assigneeId,
                         String reporter, String reporterId, List<String> sprints, String status,
                         String statusCategory, String priority, String resolution, String created,
                         String updated, Double storyPoints) {
        public String label() {
            return "[" + key + "] " + summary;
        }
    }

    /** Satu baris sheet "DevOps Ticket" (env = SIT / UAT / PROD). */
    public record DevOpsRow(String env, String key, String link, String label, String repository, String epicKey,
                            String epicSummary, String type, String note, String status, String rawStatus) {
    }

    /** branches/prSummary diisi dari panel Development bila tersedia. */
    public record DdlRow(String key, String link, String label, String assignee, String rawStatus,
                         List<String> branches, String prSummary) {
    }

    /** branches/prSummary diisi dari panel Development bila tersedia. */
    public record DatabaseRow(String key, String link, String label, String assignee, String rawStatus,
                         List<String> branches, String prSummary) {
    }

    public record HotfixRow(String key, String link, String label) {
    }

    /** Pull request milik satu tiket pada satu repository. */
    public record RepoPr(String ticketKey, String id, String title, String url, String status, String source,
                         String destination) {
    }

    /**
     * Repository terdampak. prToMain* diturunkan dari PR yang tujuannya branch main/master;
     * fromDevOps = nama juga muncul pada tiket DevOps.
     */
    public record RepoRow(String name, String prToMainStatus, List<RepoPr> prsToMain, List<RepoPr> otherPrs,
                          List<String> tickets, boolean fromDevOps) {
        public String prToMainUrl() {
            // utamakan PR yang masih OPEN agar yang diklik adalah yang perlu perhatian
            return prsToMain.stream().filter(p -> p.status().equals("OPEN")).findFirst()
                    .or(() -> prsToMain.stream().findFirst()).map(RepoPr::url).orElse("");
        }

        /** Mis. "MERGED (2 PR)" atau "OPEN (1 dari 2 PR)"; kosong bila tidak ada PR ke main. */
        public String prToMainLabel() {
            if (prsToMain.isEmpty()) {
                return "";
            }
            long open = prsToMain.stream().filter(p -> p.status().equals("OPEN")).count();
            if (prsToMain.size() == 1) {
                return prToMainStatus;
            }
            return prToMainStatus.equals("OPEN") ? "OPEN (" + open + " dari " + prsToMain.size() + " PR)"
                    : prToMainStatus + " (" + prsToMain.size() + " PR)";
        }
    }

    public record Branch(String name, String url, String repository) {
    }

    public record PullRequest(String id, String title, String url, String status, String source, String destination,
                              String repository, String repositoryUrl) {
    }

    /** Isi panel Development satu tiket. */
    public record DevInfo(String key, List<Branch> branches, List<PullRequest> pullRequests) {
        public int prCount() {
            return pullRequests.size();
        }

        /** Status PR gabungan: OPEN (masih ada yang belum merge) > MERGED > DECLINED; kosong bila tidak ada PR. */
        public String prStatus() {
            for (String s : List.of("OPEN", "MERGED", "DECLINED")) {
                if (pullRequests.stream().anyMatch(p -> p.status().equals(s))) {
                    return s;
                }
            }
            return pullRequests.isEmpty() ? "" : pullRequests.get(0).status();
        }

        public List<String> repos() {
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            pullRequests.forEach(p -> { if (!p.repository().isBlank()) out.add(p.repository()); });
            branches.forEach(b -> { if (!b.repository().isBlank()) out.add(b.repository()); });
            return List.copyOf(out);
        }

        public List<String> branchNames() {
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            branches.forEach(b -> { if (!b.name().isBlank()) out.add(b.name()); });
            pullRequests.forEach(p -> { if (!p.source().isBlank()) out.add(p.source()); });
            return List.copyOf(out);
        }
    }

    /** Ringkasan pengambilan data Development. */
    public record DevSummary(boolean enabled, int checked, int withData, int failed, boolean unavailable,
                             int ticketsWithPr, int ticketsWithoutDev) {
    }

    /** Satu batang pada grafik ringkasan. percent relatif terhadap nilai terbesar (untuk lebar bar). */
    public record Stat(String label, int count, double storyPoints, int percent) {
    }

    public record Summary(int totalTickets, double totalStoryPoints, int withoutStoryPoints, int assignees,
                          int usingPrdaApi, List<Stat> byIssueType, List<Stat> byStatus, List<Stat> byAssignee,
                          List<Stat> byPriority, List<Stat> byEpic) {
    }

    /** Hasil satu JQL: dipakai untuk transparansi di dashboard. */
    public record Section(String name, String jql, int count, String note) {
    }

    public record Report(String fixVersion, Instant generatedAt, List<Section> sections, List<Ticket> tickets,
                         Map<String, List<DevOpsRow>> devops, List<DevOpsRow> devopsUnknownEnv,
                         List<DdlRow> ddl, List<DatabaseRow> db, List<HotfixRow> hotfix, List<RepoRow> repos, Summary summary,
                         List<String> warnings, Map<String, DevInfo> development, DevSummary dev) {
    }

    /** Opsi pada dropdown fixVersion. */
    public record VersionOption(String name, boolean released, boolean archived, String releaseDate) {
    }
}
