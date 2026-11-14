package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Menyusun report release dari Jira untuk satu fixVersion: menjalankan JQL, mengklasifikasi,
 * menghitung ringkasan, dan menyimpan hasilnya di cache singkat.
 */
@Service
public class JiraReportService {

    private static final Logger log = LoggerFactory.getLogger(JiraReportService.class);
    /** fixVersion hanya boleh huruf/angka/titik/minus/underscore/spasi: mencegah injeksi JQL. */
    private static final Pattern SAFE_VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ -]{0,49}$");
    private static final Pattern DDL = Pattern.compile("(?i)\\bDDL\\b");
    private static final Pattern DATABASE = Pattern.compile("(?i)\\bCollection\\b");
    private static final Pattern HOTFIX = Pattern.compile("(?i)\\b(hotfix|sanity)\\b");
    private static final List<String> ENVS = List.of("SIT", "UAT", "PROD");
    private static final Pattern MAJOR_MINOR = Pattern.compile("^([vV]?\\d+\\.\\d+)(?:\\.\\d+.*)?$");

    private final JiraClient client;
    private final JiraClaudeProperties props;
    private final DevOpsClassifier classifier;
    private final JiraDevelopmentService development;
    private final RepositoryQualityProperties repositoryQualityProperties;

    private record Cached(Report report, Instant at) {
    }

    private record CachedVersions(List<VersionOption> versions, Instant at) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile CachedVersions versionsCache;

    public JiraReportService(JiraClient client, JiraClaudeProperties props, JiraDevelopmentService development,
                             RepositoryQualityProperties repositoryQualityProperties) {
        this.client = client;
        this.props = props;
        this.development = development;
        this.repositoryQualityProperties = repositoryQualityProperties;
        this.classifier = new DevOpsClassifier(props.getDevops().getRepoPattern());
    }

    public boolean isConfigured() {
        return client.isConfigured();
    }

    public boolean hasProjectKeys() {
        return props.getProjectKeys().stream().anyMatch(k -> !k.isBlank());
    }

    // ------------------------------------------------------------------ versi

    /** Versi (tidak termasuk yang di-archive) dari semua project, terbaru lebih dulu. */
    public List<VersionOption> fixVersions(boolean refresh) {
        if (refresh) {
            // Invalidate before fetching: a failed refresh must not silently leave an old list reusable.
            versionsCache = null;
        }
        CachedVersions c = versionsCache;
        if (!refresh && c != null && fresh(c.at())) {
            return c.versions();
        }
        Map<String, VersionOption> byName = new LinkedHashMap<>();
        for (String key : props.getProjectKeys()) {
            if (key.isBlank()) {
                continue;
            }
            for (VersionOption v : client.versions(key.trim())) {
                if (!v.archived()) {
                    byName.merge(v.name(), v, (a, b) -> a.released() ? b : a);
                }
            }
        }
        List<VersionOption> list = new ArrayList<>(byName.values());
        list.sort((a, b) -> compareVersionNames(b.name(), a.name()));
        versionsCache = new CachedVersions(List.copyOf(list), Instant.now());
        return versionsCache.versions();
    }

    /** Pastikan input aman dan (bila daftar versi bisa dibaca) memang ada di Jira. */
    public String validateVersion(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (!SAFE_VERSION.matcher(v).matches()) {
            throw new IllegalArgumentException("fixVersion tidak valid. Gunakan huruf, angka, titik, minus, underscore.");
        }
        if (hasProjectKeys()) {
            try {
                List<VersionOption> known = fixVersions(false);
                if (!known.isEmpty() && known.stream().noneMatch(o -> o.name().equals(v))) {
                    throw new IllegalArgumentException("fixVersion '" + v + "' tidak ditemukan di Jira.");
                }
            } catch (JiraException e) {
                log.warn("Daftar versi tidak bisa dibaca, validasi hanya memakai format: {}", e.getMessage());
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ report

    public Report report(String fixVersionRaw, boolean refresh) {
        String fixVersion = validateVersion(fixVersionRaw);
        if (refresh) {
            // Remove the previous snapshot before contacting Jira. If the fresh query fails,
            // a later non-refresh caller must not mistake the old snapshot for current data.
            cache.remove(fixVersion);
            log.info("Memuat ulang Jira report untuk fixVersion={} secara langsung", fixVersion);
        }
        Cached c = cache.get(fixVersion);
        if (!refresh && c != null && fresh(c.at())) {
            return c.report();
        }
        Report r = build(fixVersion);
        cache.put(fixVersion, new Cached(r, Instant.now()));
        return r;
    }

    private Report build(String fixVersion) {
        JiraIssueMapper.FieldIds ids = client.resolveFieldIds();
        List<String> fields = JiraIssueMapper.requestedFields(ids);
        String base = client.baseUrl();
        List<Section> sections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int max = Math.max(1, props.getMaxIssues());

        if (ids.storyPoints() == null) {
            warnings.add("Field Story Points tidak ditemukan di Jira; kolom Story Points akan kosong. "
                    + "Atur jira.field-names.story-points(-id) bila namanya berbeda.");
        }

        // --- All Ticket (wajib; error langsung dilempar)
        String allJql = buildJql(props.getJql().getAllTickets(), fixVersion);
        List<Ticket> tickets = new ArrayList<>();
        for (JsonNode issue : client.search(allJql, fields, max)) {
            tickets.add(JiraIssueMapper.toTicket(issue, ids, base));
        }
        sections.add(new Section("All Ticket", allJql, tickets.size(),
                tickets.size() >= max ? "Dibatasi " + max + " tiket (jira.max-issues)." : ""));
        if (tickets.size() >= max) {
            warnings.add("Jumlah tiket mencapai batas " + max + "; sebagian tiket mungkin tidak ikut.");
        }

        // --- Development (branch & PR dari panel Development Jira)
        JiraDevelopmentService.Result dev = development.collect(tickets);
        Map<String, DevInfo> devInfo = new LinkedHashMap<>(dev.byKey());
        if (props.getDevelopment().isEnabled()) {
            sections.add(new Section("Development (Jira dev-status)",
                    "GET /rest/dev-status/latest/issue/summary + detail per tiket", dev.byKey().size(),
                    dev.checked() + " tiket diperiksa, " + dev.byKey().size() + " punya branch/PR"));
            if (dev.unavailable()) {
                warnings.add("Data Development tidak tersedia: " + dev.note()
                        + " (endpoint internal dev-status; kolom branch/PR dibiarkan kosong).");
            }
            if (dev.failed() > 0) {
                warnings.add("Data Development gagal diambil untuk " + dev.failed()
                        + " tiket; branch/PR tiket tersebut tidak ikut.");
            }
        }

        // --- DevOps (project lain; hanya bila JQL diisi)
        List<DevOpsRow> devopsAll = new ArrayList<>();
        String devJql = props.getJql().getDevops();
        if (devJql != null && !devJql.isBlank()) {
            String jql = buildJql(devJql, fixVersion);
            try {
                for (JsonNode issue : client.search(jql, fields, max)) {
                    devopsAll.add(classifier.classify(JiraIssueMapper.toTicket(issue, ids, base)));
                }
                sections.add(new Section("DevOps Ticket", jql, devopsAll.size(), ""));
            } catch (JiraException e) {
                sections.add(new Section("DevOps Ticket", jql, 0, "Gagal: " + e.getMessage()));
                warnings.add("Tiket DevOps gagal ditarik: " + e.getMessage());
            }
        } else {
            warnings.add("Tiket DevOps belum ditarik: isi jira.jql.devops (mis. project = MD AND ...).");
        }
        devopsAll.sort(Comparator.comparing(DevOpsRow::key, JiraReportService::compareIssueKeys));
        Map<String, List<DevOpsRow>> devops = new LinkedHashMap<>();
        for (String env : ENVS) {
            devops.put(env, devopsAll.stream().filter(d -> env.equals(d.env())).toList());
        }
        List<DevOpsRow> unknownEnv = devopsAll.stream().filter(d -> !ENVS.contains(d.env())).toList();
        if (!unknownEnv.isEmpty()) {
            warnings.add(unknownEnv.size() + " tiket DevOps tidak punya label environment ([SIT]/[UAT]/[PROD]) "
                    + "di summary, tidak masuk ke blok SIT/UAT/PROD maupun Excel.");
        }

        // --- DDL
        List<DdlRow> ddl = new ArrayList<>();
        String ddlJql = props.getJql().getDdl();
        if (ddlJql != null && !ddlJql.isBlank()) {
            String jql = buildJql(ddlJql, fixVersion);
            try {
                List<Ticket> ddlTickets = new ArrayList<>();
                for (JsonNode issue : client.search(jql, fields, max)) {
                    ddlTickets.add(JiraIssueMapper.toTicket(issue, ids, base));
                }
                // tiket DDL dari JQL lain mungkin belum punya data Development
                List<Ticket> missing = ddlTickets.stream().filter(t -> !devInfo.containsKey(t.key())).toList();
                if (!missing.isEmpty() && !dev.unavailable()) {
                    devInfo.putAll(development.collect(missing).byKey());
                }
                ddlTickets.forEach(t -> ddl.add(ddlRow(t, devInfo.get(t.key()), fixVersion)));
                sections.add(new Section("DDL Ticket", jql, ddl.size(), ""));
            } catch (JiraException e) {
                sections.add(new Section("DDL Ticket", jql, 0, "Gagal: " + e.getMessage()));
                warnings.add("Tiket DDL gagal ditarik: " + e.getMessage());
            }
        } else {
            for (Ticket t : tickets) {
                if (DDL.matcher(t.summary()).find()) {
                    ddl.add(ddlRow(t, devInfo.get(t.key()), fixVersion));
                }
            }
            sections.add(new Section("DDL Ticket", "(diturunkan dari All Ticket: summary memuat \"DDL\")", ddl.size(), ""));
        }

        // --- Database
        List<DatabaseRow> db = new ArrayList<>();
        String dbJql = props.getJql().getDb();
        if (dbJql != null && !dbJql.isBlank()) {
            String jql = buildJql(dbJql, fixVersion);
            try {
                List<Ticket> dbTickets = new ArrayList<>();
                for (JsonNode issue : client.search(jql, fields, max)) {
                    dbTickets.add(JiraIssueMapper.toTicket(issue, ids, base));
                }
                // tiket DB dari JQL lain mungkin belum punya data Development
                List<Ticket> missing = dbTickets.stream().filter(t -> !devInfo.containsKey(t.key())).toList();
                if (!missing.isEmpty() && !dev.unavailable()) {
                    devInfo.putAll(development.collect(missing).byKey());
                }
                dbTickets.forEach(t -> db.add(dbRow(t, devInfo.get(t.key()), fixVersion)));
                sections.add(new Section("DB Ticket", jql, db.size(), ""));
            } catch (JiraException e) {
                sections.add(new Section("DB Ticket", jql, 0, "Gagal: " + e.getMessage()));
                warnings.add("Tiket DB gagal ditarik: " + e.getMessage());
            }
        } else {
            for (Ticket t : tickets) {
                if (DATABASE.matcher(t.summary()).find()) {
                    db.add(dbRow(t, devInfo.get(t.key()), fixVersion));
                }
            }
            sections.add(new Section("Database Ticket", "(diturunkan dari All Ticket: summary memuat \"Collection\")", db.size(), ""));
        }

        // --- Bugs Development / UAT (query is intentionally kept separate from All Ticket)
        List<Ticket> bugsDevelopmentUat = new ArrayList<>();
        String bugsDevelopmentUatTemplate = props.getJql().getBugsDevelopmentUat();
        if (bugsDevelopmentUatTemplate != null && !bugsDevelopmentUatTemplate.isBlank()) {
            String jql = buildJql(bugsDevelopmentUatTemplate, fixVersion);
            try {
                for (JsonNode issue : client.search(jql, fields, max)) {
                    bugsDevelopmentUat.add(JiraIssueMapper.toTicket(issue, ids, base));
                }
                sections.add(new Section("Bugs Development / UAT", jql, bugsDevelopmentUat.size(),
                        bugsDevelopmentUat.size() >= max ? "Dibatasi " + max + " tiket (jira.max-issues)." : ""));
            } catch (JiraException e) {
                sections.add(new Section("Bugs Development / UAT", jql, 0, "Gagal: " + e.getMessage()));
                warnings.add("Bugs Development / UAT gagal ditarik: " + e.getMessage());
            }
        }

        // --- Hotfix / Sanity
        List<HotfixRow> hotfix = new ArrayList<>();
        String hfJql = props.getJql().getHotfix();
        if (hfJql != null && !hfJql.isBlank()) {
            String jql = buildJql(hfJql, fixVersion);
            try {
                for (JsonNode issue : client.search(jql, fields, max)) {
                    Ticket t = JiraIssueMapper.toTicket(issue, ids, base);
                    hotfix.add(new HotfixRow(t.key(), t.link(), t.label()));
                }
                sections.add(new Section("Hotfix OR Sanity Tickets", jql, hotfix.size(), ""));
            } catch (JiraException e) {
                sections.add(new Section("Hotfix OR Sanity Tickets", jql, 0, "Gagal: " + e.getMessage()));
                warnings.add("Tiket Hotfix/Sanity gagal ditarik: " + e.getMessage());
            }
        } else {
            for (Ticket t : tickets) {
                if (HOTFIX.matcher(t.summary()).find()) {
                    hotfix.add(new HotfixRow(t.key(), t.link(), t.label()));
                }
            }
            sections.add(new Section("Hotfix OR Sanity Tickets",
                    "(diturunkan dari All Ticket: summary memuat hotfix/sanity)", hotfix.size(), ""));
        }

        // --- Repo Impacted: dari PR/branch (Development) + repository pada tiket DevOps
        List<RepoRow> repos = buildRepos(devInfo, devopsAll);

        int withPr = (int) tickets.stream().filter(t -> devInfo.containsKey(t.key()) && devInfo.get(t.key()).prCount() > 0).count();
        int withoutDev = (int) tickets.stream().filter(t -> !devInfo.containsKey(t.key())).count();
        DevSummary devSummary = new DevSummary(props.getDevelopment().isEnabled(), dev.checked(), dev.byKey().size(),
                dev.failed(), dev.unavailable(), withPr, withoutDev);

        return new Report(fixVersion, Instant.now(), List.copyOf(sections), List.copyOf(tickets),
                Collections.unmodifiableMap(devops), unknownEnv, List.copyOf(ddl), List.copyOf(db), List.copyOf(hotfix),
                List.copyOf(bugsDevelopmentUat), repos,
                summarize(tickets), List.copyOf(warnings), Collections.unmodifiableMap(devInfo), devSummary);
    }

    private DdlRow ddlRow(Ticket t, DevInfo info, String fixVersion) {
        List<String> branches = backendApiDdlBranches(info);
        String prSummary = backendApiDdlPrSummary(info);
        BranchTargets targets = branchTargets(branches, fixVersion);
        return new DdlRow(t.key(), t.link(), t.label(), t.assignee(), t.status(), branches, prSummary,
                targets.sit(), targets.uat(), targets.prod());
    }

    private DatabaseRow dbRow(Ticket t, DevInfo info, String fixVersion) {
        List<String> branches = backendApiDdlBranches(info);
        String prSummary = backendApiDdlPrSummary(info);
        BranchTargets targets = branchTargets(branches, fixVersion);
        return new DatabaseRow(t.key(), t.link(), t.label(), t.assignee(), t.status(), branches, prSummary,
                targets.sit(), targets.uat(), targets.prod());
    }

    private record BranchTargets(String sit, String uat, String prod) { }

    /** Resolve repository links from Jira dev-status to the dedicated backendapi-ddl repository only. */
    private static List<String> backendApiDdlBranches(DevInfo info) {
        if (info == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        info.branches().stream().filter(b -> isBackendApiDdl(b.repository()))
                .map(Branch::name).filter(v -> v != null && !v.isBlank()).forEach(out::add);
        info.pullRequests().stream().filter(p -> isBackendApiDdl(p.repository())).forEach(p -> {
            if (p.source() != null && !p.source().isBlank()) out.add(p.source());
            if (p.destination() != null && !p.destination().isBlank()) out.add(p.destination());
        });
        return List.copyOf(out);
    }

    private static String backendApiDdlPrSummary(DevInfo info) {
        if (info == null) return "";
        return info.pullRequests().stream().filter(p -> isBackendApiDdl(p.repository()))
                .map(p -> (p.id().isBlank() ? "PR" : "PR " + p.id()) + " " + p.status()
                        + (p.source().isBlank() ? "" : " " + p.source())
                        + (p.destination().isBlank() ? "" : " → " + p.destination()))
                .collect(Collectors.joining("; "));
    }

    private static boolean isBackendApiDdl(String repository) {
        if (repository == null || repository.isBlank()) return false;
        String normalized = RepoCoverageWhitelistService.normalize(repository)
                .replaceAll("[^a-z0-9]", "");
        return normalized.equals("backendapiddl");
    }

    private BranchTargets branchTargets(List<String> branches, String fixVersion) {
        String defaultSit = repositoryQualityProperties.getEnvironments().getSit().getBranch();
        if (defaultSit == null || defaultSit.isBlank()) defaultSit = "develop-support";
        List<String> values = branches == null ? List.of() : branches;
        String sit = preferredMatching(values, defaultSit, value -> {
            String v = normaliseBranch(value);
            return v.equals("develop-support") || v.equals("develop") || v.startsWith("sit");
        }, defaultSit);
        String uatExpected = "release/" + (fixVersion == null ? "" : fixVersion.trim());
        String uat = preferredMatching(values, uatExpected, value -> {
            String v = normaliseBranch(value);
            return v.equals(normaliseBranch(uatExpected)) || v.startsWith("uat");
        }, uatExpected);
        String prod = preferredMatching(values, "main", value -> {
            String v = normaliseBranch(value);
            return v.equals("main") || v.startsWith("prod");
        }, "main");
        return new BranchTargets(sit, uat, prod);
    }

    private static String preferredMatching(List<String> values, String preferred,
                                             java.util.function.Predicate<String> match, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            String exact = values.stream().filter(v -> v != null && v.equalsIgnoreCase(preferred)).findFirst().orElse("");
            if (!exact.isBlank()) return exact;
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).filter(match).findFirst().orElse(fallback);
    }

    private static String normaliseBranch(String value) {
        if (value == null) return "";
        String branch = value.trim().toLowerCase(Locale.ROOT);
        if (branch.startsWith("refs/heads/")) branch = branch.substring("refs/heads/".length());
        if (branch.startsWith("refs/tags/")) branch = branch.substring("refs/tags/".length());
        return branch;
    }

    /** Gabungkan repository dari PR/branch dan dari tiket DevOps; tentukan status PR ke branch main. */
    private List<RepoRow> buildRepos(Map<String, DevInfo> devInfo, List<DevOpsRow> devopsAll) {
        Set<String> mains = props.getDevelopment().getMainBranches().stream()
                .map(b -> b.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Map<String, String> display = new TreeMap<>();
        Map<String, Map<String, RepoPr>> toMain = new HashMap<>(), other = new HashMap<>();
        Map<String, Set<String>> tickets = new HashMap<>();
        Set<String> fromDevOps = new HashSet<>();

        for (DevInfo info : devInfo.values()) {
            for (PullRequest p : info.pullRequests()) {
                if (p.repository().isBlank()) {
                    continue;
                }
                String k = p.repository().toLowerCase(Locale.ROOT);
                display.putIfAbsent(k, p.repository());
                RepoPr rp = new RepoPr(info.key(), p.id(), p.title(), p.url(), p.status(), p.source(), p.destination());
                Map<String, Map<String, RepoPr>> target = mains.contains(p.destination().toLowerCase(Locale.ROOT)) ? toMain : other;
                target.computeIfAbsent(k, x -> new LinkedHashMap<>()).putIfAbsent(p.url().isBlank() ? info.key() + p.id() : p.url(), rp);
                tickets.computeIfAbsent(k, x -> new TreeSet<>(JiraReportService::compareIssueKeys)).add(info.key());
            }
            for (Branch b : info.branches()) {
                if (!b.repository().isBlank()) {
                    String k = b.repository().toLowerCase(Locale.ROOT);
                    display.putIfAbsent(k, b.repository());
                    tickets.computeIfAbsent(k, x -> new TreeSet<>(JiraReportService::compareIssueKeys)).add(info.key());
                }
            }
        }
        for (DevOpsRow d : devopsAll) {
            if (!d.repository().isBlank()) {
                String k = d.repository().toLowerCase(Locale.ROOT);
                display.putIfAbsent(k, d.repository());
                fromDevOps.add(k);
            }
        }

        List<RepoRow> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : display.entrySet()) {
            String k = e.getKey();
            List<RepoPr> main = List.copyOf(toMain.getOrDefault(k, Map.of()).values());
            List<RepoPr> oth = List.copyOf(other.getOrDefault(k, Map.of()).values());
            rows.add(new RepoRow(e.getValue(), strongestStatus(main), main, oth,
                    List.copyOf(tickets.getOrDefault(k, Set.of())), fromDevOps.contains(k)));
        }
        return List.copyOf(rows);
    }

    private static String strongestStatus(List<RepoPr> prs) {
        for (String s : List.of("OPEN", "MERGED", "DECLINED")) {
            if (prs.stream().anyMatch(p -> p.status().equals(s))) {
                return s;
            }
        }
        return prs.isEmpty() ? "" : prs.get(0).status();
    }

    // ------------------------------------------------------------------ util

    /** Ganti {fixVersion} (penuh) dan {fixVersionShort} (major.minor). Nilai sudah divalidasi sebelumnya. */
    static String buildJql(String template, String fixVersion) {
        return template
                .replace("{fixVersionShort}", shortVersion(fixVersion))
                .replace("{fixVersion}", fixVersion)
                .trim();
    }

    private boolean fresh(Instant at) {
        return Duration.between(at, Instant.now()).toMinutes() < Math.max(0, props.getCacheTtlMinutes());
    }

    static Summary summarize(List<Ticket> tickets) {
        double totalSp = tickets.stream().mapToDouble(t -> t.storyPoints() == null ? 0 : t.storyPoints()).sum();
        int noSp = (int) tickets.stream().filter(t -> t.storyPoints() == null).count();
        int assignees = (int) tickets.stream().map(Ticket::assignee).filter(s -> !s.isBlank()).distinct().count();
        int prda = (int) tickets.stream().filter(t -> {
            String v = t.usingPrdaApi().trim().toLowerCase(Locale.ROOT);
            return v.equals("yes") || v.equals("ya") || v.equals("true");
        }).count();
        return new Summary(tickets.size(), round1(totalSp), noSp, assignees, prda,
                stats(tickets, Ticket::issueType, "(tanpa tipe)", 20),
                stats(tickets, Ticket::status, "(tanpa status)", 20),
                stats(tickets, Ticket::assignee, "(Unassigned)", 15),
                stats(tickets, Ticket::priority, "(tanpa prioritas)", 20),
                stats(tickets, t -> t.parentKey().isBlank() ? "" : t.parentKey() + " - " + t.parentSummary(),
                        "(tanpa parent/epic)", 15));
    }

    private static List<Stat> stats(List<Ticket> tickets, java.util.function.Function<Ticket, String> by,
                                    String emptyLabel, int limit) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Double> points = new LinkedHashMap<>();
        for (Ticket t : tickets) {
            String k = by.apply(t);
            if (k == null || k.isBlank()) {
                k = emptyLabel;
            }
            counts.computeIfAbsent(k, x -> new int[1])[0]++;
            points.merge(k, t.storyPoints() == null ? 0 : t.storyPoints(), Double::sum);
        }
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        int max = sorted.isEmpty() ? 0 : sorted.get(0).getValue()[0];
        List<Stat> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : sorted) {
            if (out.size() >= limit) {
                break;
            }
            int pct = max == 0 ? 0 : (int) Math.round(e.getValue()[0] * 100.0 / max);
            out.add(new Stat(e.getKey(), e.getValue()[0], round1(points.get(e.getKey())), pct));
        }
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** MD-1024 < MD-1100 (urut numerik per project). */
    static int compareIssueKeys(String a, String b) {
        int ia = a.lastIndexOf('-'), ib = b.lastIndexOf('-');
        String pa = ia < 0 ? a : a.substring(0, ia), pb = ib < 0 ? b : b.substring(0, ib);
        int c = pa.compareTo(pb);
        if (c != 0) {
            return c;
        }
        try {
            return Long.compare(Long.parseLong(a.substring(ia + 1)), Long.parseLong(b.substring(ib + 1)));
        } catch (RuntimeException e) {
            return a.compareTo(b);
        }
    }

    /** Urutan versi alami: v2.9.0 < v2.15.0. */
    static int compareVersionNames(String a, String b) {
        String[] pa = a.replaceFirst("^[vV]", "").split("[._ -]");
        String[] pb = b.replaceFirst("^[vV]", "").split("[._ -]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String x = i < pa.length ? pa[i] : "0", y = i < pb.length ? pb[i] : "0";
            boolean nx = x.matches("\\d+"), ny = y.matches("\\d+");
            int c = (nx && ny) ? Long.compare(Long.parseLong(x), Long.parseLong(y)) : x.compareToIgnoreCase(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** v2.15.0 -> v2.15 ; v2.15.1 -> v2.15 ; v2.15 tetap ; bentuk lain dikembalikan apa adanya. */
    static String shortVersion(String v) {
        Matcher m = MAJOR_MINOR.matcher(v);
        return m.matches() ? m.group(1) : v;
    }
}
