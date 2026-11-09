package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.DevOpsRow;
import com.hito.genai.jiraclaude.ReportModels.Ticket;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Membaca tiket DevOps (mis. "[PROD - UBP] Request Configmaps - prdi-order-service") menjadi baris
 * sheet "DevOps Ticket": environment, tipe request, repository/service, dan status.
 */
public final class DevOpsClassifier {

    /**
     * Label environment di awal summary atau di dalam kurung siku. Contoh yang dikenali:
     * [SIT - UBP], [SIT-V2], [SIT-V3 - UBP], [ALL SIT Environment], [UAT], [ALL UAT], [PROD - UBP], [PRODUCTION].
     * group(1) = label lengkap ("ALL SIT", "SIT-V3"), group(2) = environment dasar (SIT/UAT/PROD).
     * Varian SIT-V2, SIT-V3, dan ALL SIT semuanya dikelompokkan ke SIT.
     */
    private static final Pattern ENV = Pattern.compile(
            "(?:^|\\[)\\s*((?:ALL\\s+)?(SIT|UAT|PROD(?:UCTION)?)(?:[\\s_-]+V\\d+)?)\\b",
            Pattern.CASE_INSENSITIVE);

    private final Pattern repoPattern;

    public DevOpsClassifier(String repoRegex) {
        this.repoPattern = Pattern.compile(repoRegex, Pattern.CASE_INSENSITIVE);
    }

    public DevOpsRow classify(Ticket t) {
        String summary = t.summary();
        String env = env(summary);
        String label = envLabel(summary);
        String note = label.equals(env) ? "" : label;
        return new DevOpsRow(env, t.key(), t.link(), t.label(), repo(summary), t.parentKey(),
                t.parentSummary(), type(summary), note, status(t), t.status());
    }

    /** SIT / UAT / PROD, atau "" bila tidak terdeteksi. */
    static String env(String summary) {
        Matcher m = ENV.matcher(summary == null ? "" : summary);
        if (!m.find()) {
            return "";
        }
        String e = m.group(2).toUpperCase(Locale.ROOT);
        return e.startsWith("PROD") ? "PROD" : e;
    }

    /** Label asli yang dinormalisasi, mis. "SIT-V3" atau "ALL SIT"; "" bila tidak terdeteksi. */
    static String envLabel(String summary) {
        Matcher m = ENV.matcher(summary == null ? "" : summary);
        if (!m.find()) {
            return "";
        }
        return m.group(1).toUpperCase(Locale.ROOT).replaceAll("[\\s_-]+V(\\d+)", "-V$1").replaceAll("\\s+", " ").replace("PRODUCTION", "PROD").trim();
    }

    static String type(String summary) {
        String s = summary == null ? "" : summary.toLowerCase(Locale.ROOT);
        if (s.contains("mount") || s.contains("mount secret")) {
            return "Mount Secret";
        }
        if (s.contains("configmap")) {
            return "Configmap";
        }
        if (s.contains("cronjob") || s.contains("cron job") || s.contains("scheduler") || s.contains("new service")) {
            return "New Service/Cron Job";
        }
        if (s.contains("secret")) {
            return "Secret";
        }
        return "";
    }

    String repo(String summary) {
        Matcher m = repoPattern.matcher(summary == null ? "" : summary);
        return m.find() ? m.group(m.groupCount() >= 1 ? 1 : 0) : "";
    }

    /** Status Jira -> SUCCESS / FAILED / ON PROGRESS (nilai dropdown pada template). */
    static String status(Ticket t) {
        String name = t.status() == null ? "" : t.status().toLowerCase(Locale.ROOT);
        if (name.contains("fail") || name.contains("reject") || name.contains("cancel")) {
            return "FAILED";
        }
        if ("done".equalsIgnoreCase(t.status()) || "done nonprod".equalsIgnoreCase(t.status())) {
            return "SUCCESS";
        }
        return "ON PROGRESS";
    }
}
