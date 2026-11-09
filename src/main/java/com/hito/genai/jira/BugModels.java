package com.hito.genai.jira;

import java.time.Instant;
import java.util.List;

/** Model data dashboard monitoring bug. */
public final class BugModels {

    private BugModels() {
    }

    /** Satu tiket. fixVersions selalu nama LENGKAP (v2.15.0); pengelompokan memakai versi pendek. */
    public record Bug(String key, String link, String summary, String issueType, String status, String statusCategory,
                      String priority, String assignee, String reporter, String created, String updated,
                      String resolved, List<String> labels, List<String> fixVersions, boolean open, long ageDays,
                      boolean aging, boolean critical) {
    }

    public record BarStat(String label, int count, int percent) {
    }

    /** Rincian per fixVersion lengkap di dalam satu grup versi pendek. */
    public record VersionDetail(String fullName, int total, int open) {
    }

    /**
     * Satu grup versi pendek (mis. v2.15). Tiket dengan lebih dari satu fixVersion muncul di setiap grup
     * yang sesuai, jadi jumlah antar grup bisa lebih besar dari total unik.
     */
    public record Group(String shortVersion, boolean noVersion, int total, int open, int closed, int criticalOpen,
                        int unassignedOpen, int openWidth, int closedWidth, List<VersionDetail> versions,
                        List<BarStat> openByPriority, List<Bug> bugs) {
    }

    public record WeekPoint(String label, int created, int resolved, int createdPct, int resolvedPct) {
    }

    public record BugReport(Instant generatedAt, String jql, int total, int open, int closed, int criticalOpen,
                            int unassignedOpen, int agingOpen, int noFixVersion, int created7d, int resolved7d,
                            double avgOpenAgeDays, int agingDays, List<Group> groups, List<BarStat> byStatus,
                            List<BarStat> byPriority, List<BarStat> openByAssignee, List<BarStat> byIssueType,
                            List<WeekPoint> weekly, List<String> warnings) {
    }
}
