package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.Branch;
import com.hito.genai.jiraclaude.ReportModels.DevInfo;
import com.hito.genai.jiraclaude.ReportModels.PullRequest;
import com.hito.genai.jiraclaude.ReportModels.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Membaca panel "Development" Jira (branch & pull request Bitbucket) untuk tiket pada report.
 *
 * Memakai endpoint INTERNAL Jira (/rest/dev-status/latest/...), bukan API resmi: bentuk responsnya bisa berubah.
 * Karena itu semua kegagalan bersifat lunak: report tetap jadi, hanya tanpa data Development.
 */
@Service
public class JiraDevelopmentService {

    private static final Logger log = LoggerFactory.getLogger(JiraDevelopmentService.class);

    private final JiraClient client;
    private final JiraClaudeProperties props;

    public JiraDevelopmentService(JiraClient client, JiraClaudeProperties props) {
        this.client = client;
        this.props = props;
    }

    /** Hasil pengambilan: data per tiket (hanya tiket yang punya data) + statistik. */
    public record Result(Map<String, DevInfo> byKey, int checked, int failed, boolean unavailable, String note) {
        static Result disabled() {
            return new Result(Map.of(), 0, 0, false, "");
        }
    }

    public Result collect(List<Ticket> tickets) {
        JiraClaudeProperties.Development cfg = props.getDevelopment();
        if (!cfg.isEnabled() || tickets.isEmpty()) {
            return Result.disabled();
        }
        List<Ticket> targets = tickets.stream().filter(t -> !t.issueId().isBlank())
                .limit(Math.max(1, cfg.getMaxTickets())).toList();

        AtomicBoolean unavailable = new AtomicBoolean(false);
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger checked = new AtomicInteger();
        StringBuilder note = new StringBuilder();

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(16, cfg.getParallelism())));
        try {
            List<Callable<DevInfo>> jobs = new ArrayList<>();
            for (Ticket t : targets) {
                jobs.add(() -> {
                    if (unavailable.get()) {
                        return null;
                    }
                    try {
                        DevInfo info = fetch(t);
                        checked.incrementAndGet();
                        return info;
                    } catch (JiraException e) {
                        // 401/403/404 pada dev-status: fitur tidak tersedia untuk akun/instance ini -> berhenti.
                        if (e.status() == 401 || e.status() == 403 || e.status() == 404) {
                            if (unavailable.compareAndSet(false, true)) {
                                synchronized (note) {
                                    note.append(e.getMessage());
                                }
                            }
                        } else {
                            failed.incrementAndGet();
                            log.warn("Data Development {} gagal: {}", t.key(), e.getMessage());
                        }
                        return null;
                    }
                });
            }
            Map<String, DevInfo> out = new TreeMap<>(JiraReportService::compareIssueKeys);
            for (Future<DevInfo> f : pool.invokeAll(jobs)) {
                try {
                    DevInfo info = f.get();
                    if (info != null) {
                        out.put(info.key(), info);
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    failed.incrementAndGet();
                }
            }
            return new Result(new LinkedHashMap<>(out), checked.get(), failed.get(), unavailable.get(), note.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Map.of(), checked.get(), failed.get(), false, "Pengambilan data Development dibatalkan.");
        } finally {
            pool.shutdownNow();
        }
    }

    /** Ringkasan dulu (murah): hanya tiket yang punya PR/branch yang diambil detailnya. */
    private DevInfo fetch(Ticket t) {
        JsonNode summary = client.devSummary(t.issueId()).path("summary");
        Map<String, Boolean> calls = new LinkedHashMap<>(); // "app|dataType"
        for (String dataType : List.of("pullrequest", "branch")) {
            JsonNode byInstance = summary.path(dataType).path("byInstanceType");
            if (byInstance.isObject()) {
                for (Map.Entry<String, JsonNode> e : byInstance.properties()) {
                    if (e.getValue().path("count").asInt(0) > 0) {
                        calls.put(e.getKey() + "|" + dataType, true);
                    }
                }
            }
        }
        if (calls.isEmpty()) {
            return null;
        }
        List<Branch> branches = new ArrayList<>();
        Map<String, PullRequest> prs = new LinkedHashMap<>();
        for (String call : calls.keySet()) {
            String[] parts = call.split("\\|");
            parseDetail(client.devDetail(t.issueId(), parts[0], parts[1]), branches, prs);
        }
        List<Branch> distinctBranches = new ArrayList<>(new LinkedHashMap<String, Branch>() {{
            branches.forEach(b -> putIfAbsent(b.repository() + "#" + b.name(), b));
        }}.values());
        if (distinctBranches.isEmpty() && prs.isEmpty()) {
            return null;
        }
        return new DevInfo(t.key(), List.copyOf(distinctBranches), List.copyOf(prs.values()));
    }

    /** Membaca branches[] dan pullRequests[] dari respons detail (toleran terhadap variasi bentuk). */
    static void parseDetail(JsonNode res, List<Branch> branches, Map<String, PullRequest> prs) {
        for (JsonNode d : res.path("detail")) {
            for (JsonNode b : d.path("branches")) {
                String name = b.path("name").asString("");
                if (!name.isBlank()) {
                    String repository = repositoryName(b);
                    branches.add(new Branch(name, b.path("url").asString(""), shortRepo(repository)));
                }
            }
            for (JsonNode p : d.path("pullRequests")) {
                String url = p.path("url").asString("");
                String repo = repositoryName(p);
                PullRequest pr = new PullRequest(
                        p.path("id").asString(""),
                        p.path("name").asString(""),
                        url,
                        p.path("status").asString("").toUpperCase(Locale.ROOT),
                        branchName(p.path("source")),
                        branchName(p.path("destination")),
                        shortRepo(repo),
                        p.path("repositoryUrl").asString(""));
                prs.putIfAbsent(url.isBlank() ? pr.repository() + pr.id() : url, pr);
            }
        }
    }

    private static String branchName(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        if (n.isTextual()) {
            return n.asString("");
        }
        JsonNode b = n.path("branch");
        if (b.isTextual()) {
            return b.asString("");
        }
        if (b.isObject()) {
            return b.path("name").asString("");
        }
        return n.path("name").asString("");
    }

    /** Extract repository slug/name from the variants returned by Jira dev-status. */
    private static String repositoryName(JsonNode item) {
        JsonNode repository = item.path("repository");
        return firstNonBlank(
                repository.path("slug").asString(""),
                repository.path("full_name").asString(""),
                item.path("repositorySlug").asString(""),
                item.path("repositoryName").asString(""),
                item.path("repository_name").asString(""),
                repository.path("links").path("html").path("href").asString(""),
                item.path("repositoryUrl").asString(""),
                item.path("repository_url").asString(""),
                repository.path("name").asString(""),
                repository.asString(""));
    }

    /**
     * Normalise repository identifiers returned by Jira dev-status.
     * Jira may return a slug/full-name or an HTML URL such as
     * https://bitbucket.org/prodiadigitalindonesia/backendapi-ddl/src/main/.
     * In the latter case, the repository is the path segment before "src";
     * taking the last segment would incorrectly return "main".
     */
    static String shortRepo(String name) {
        if (name == null || name.isBlank()) return "";
        String value = name.trim();
        String path = value;
        try {
            if (value.contains("://")) {
                path = java.net.URI.create(value).getPath();
                if (path == null) path = value;
            }
        } catch (IllegalArgumentException ignored) {
            // Continue with best-effort string normalisation.
        }
        path = path.split("[?#]", 2)[0];
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        List<String> segments = Arrays.stream(path.split("/"))
                .map(String::trim).filter(segment -> !segment.isBlank()).toList();
        String selected = "";
        for (int i = 0; i < segments.size(); i++) {
            String marker = segments.get(i).toLowerCase(Locale.ROOT);
            if (Set.of("src", "branches", "branch", "pull-requests", "commits", "downloads", "admin")
                    .contains(marker) && i > 0) {
                selected = segments.get(i - 1);
                break;
            }
        }
        if (selected.isBlank()) {
            // URLs include the workspace as the first path segment and repository as the second.
            // Plain workspace/repository values use the final segment as well.
            if (value.contains("://") && segments.size() >= 2) selected = segments.get(1);
            else selected = segments.isEmpty() ? value : segments.get(segments.size() - 1);
        }
        selected = selected.trim();
        if (selected.toLowerCase(Locale.ROOT).endsWith(".git")) {
            selected = selected.substring(0, selected.length() - 4);
        }
        return selected;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
}
