package com.hito.genai.jira;

import com.hito.genai.jira.BugIssueMapper.Parsed;
import com.hito.genai.jira.BugModels.*;
import com.hito.genai.jiraclaude.JiraClaudeProperties;
import com.hito.genai.jiraclaude.JiraClient;
import com.hito.genai.jiraclaude.JiraException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Menyusun dashboard monitoring bug dari satu JQL. Pengelompokan memakai versi pendek (v2.15) dari fixVersion,
 * sementara setiap tiket tetap membawa nama fixVersion lengkap (v2.15.0).
 */
@Service
public class BugMonitorService {

    static final String NO_VERSION = "(Tanpa fixVersion)";
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final int WEEKS = 8;

    private record Cached(BugReport report, Instant at) {
    }

    private final JiraClient client;
    private final JiraClaudeProperties props;
    private volatile Cached cache;

    public BugMonitorService(JiraClient client, JiraClaudeProperties props) {
        this.client = client;
        this.props = props;
    }

    public boolean isConfigured() {
        return client.isConfigured();
    }

    public BugReport report(boolean refresh) {
        if (!client.isConfigured()) {
            throw new JiraException("Jira belum dikonfigurasi (JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN).");
        }
        Cached c = cache;
        if (!refresh && c != null && Duration.between(c.at(), Instant.now()).toMinutes() < Math.max(0, props.getCacheTtlMinutes())) {
            return c.report();
        }
        BugReport r = build();
        cache = new Cached(r, Instant.now());
        return r;
    }

    private BugReport build() {
        JiraClaudeProperties.Bugs cfg = props.getBugs();
        int max = Math.max(1, cfg.getMaxIssues());
        Instant now = Instant.now();
        Set<String> critical = cfg.getCriticalPriorities().stream().map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<Parsed> parsed = new ArrayList<>();
        for (JsonNode issue : client.search(cfg.getJql(), BugIssueMapper.FIELDS, max)) {
            parsed.add(BugIssueMapper.map(issue, client.baseUrl(), critical, cfg.getAgingDays(), now));
        }
        List<Bug> bugs = parsed.stream().map(Parsed::bug).toList();
        List<String> warnings = new ArrayList<>();
        if (bugs.size() >= max) {
            warnings.add("Jumlah tiket mencapai batas " + max + " (app.jira.bugs.max-issues); tiket yang lebih lama tidak ikut.");
        }
        long notBug = bugs.stream().filter(b -> !b.issueType().equalsIgnoreCase("Bug")).count();
        if (notBug > 0) {
            warnings.add(notBug + " dari " + bugs.size() + " tiket bukan bertipe Bug karena JQL tidak memfilter work type. "
                    + "Tambahkan AND worktype = Bug pada app.jira.bugs.jql bila hanya ingin bug.");
        }

        // --- grup berdasarkan versi pendek
        Map<String, List<Bug>> byShort = new LinkedHashMap<>();
        for (Bug b : bugs) {
            Set<String> shorts = new LinkedHashSet<>();
            b.fixVersions().forEach(v -> shorts.add(VersionNames.shortOf(v)));
            if (shorts.isEmpty()) {
                shorts.add(NO_VERSION);
            }
            for (String s : shorts) {
                byShort.computeIfAbsent(s, k -> new ArrayList<>()).add(b);
            }
        }
        List<String> order = new ArrayList<>(byShort.keySet());
        order.sort((a, b) -> {
            if (a.equals(NO_VERSION)) return b.equals(NO_VERSION) ? 0 : 1;
            if (b.equals(NO_VERSION)) return -1;
            return VersionNames.compare(b, a); // terbaru dulu
        });
        int maxGroup = byShort.values().stream().mapToInt(List::size).max().orElse(1);
        List<Group> groups = new ArrayList<>();
        for (String g : order) {
            groups.add(group(g, byShort.get(g), maxGroup));
        }

        // --- KPI
        int open = (int) bugs.stream().filter(Bug::open).count();
        double avgAge = bugs.stream().filter(Bug::open).mapToLong(Bug::ageDays).average().orElse(0);
        Instant weekAgo = now.minus(Duration.ofDays(7));
        int created7 = (int) parsed.stream().filter(p -> p.created() != null && p.created().isAfter(weekAgo)).count();
        int resolved7 = (int) parsed.stream().filter(p -> p.resolved() != null && p.resolved().isAfter(weekAgo)).count();

        return new BugReport(now, cfg.getJql(), bugs.size(), open, bugs.size() - open,
                (int) bugs.stream().filter(b -> b.open() && b.critical()).count(),
                (int) bugs.stream().filter(b -> b.open() && b.assignee().isBlank()).count(),
                (int) bugs.stream().filter(Bug::aging).count(),
                (int) bugs.stream().filter(b -> b.fixVersions().isEmpty()).count(),
                created7, resolved7, Math.round(avgAge * 10.0) / 10.0, cfg.getAgingDays(), List.copyOf(groups),
                stats(bugs, Bug::status, "(tanpa status)", 20),
                stats(bugs, Bug::priority, "(tanpa prioritas)", 20),
                stats(bugs.stream().filter(Bug::open).toList(), Bug::assignee, "(Unassigned)", 15),
                stats(bugs, Bug::issueType, "(tanpa tipe)", 20),
                weekly(parsed, now), List.copyOf(warnings));
    }

    private Group group(String shortVersion, List<Bug> bugs, int maxGroup) {
        boolean noVersion = shortVersion.equals(NO_VERSION);
        int open = (int) bugs.stream().filter(Bug::open).count();
        int total = bugs.size();

        // rincian per fixVersion lengkap yang termasuk grup ini
        Map<String, int[]> byFull = new LinkedHashMap<>();
        if (!noVersion) {
            for (Bug b : bugs) {
                for (String v : b.fixVersions()) {
                    if (VersionNames.shortOf(v).equals(shortVersion)) {
                        int[] c = byFull.computeIfAbsent(v, k -> new int[2]);
                        c[0]++;
                        if (b.open()) {
                            c[1]++;
                        }
                    }
                }
            }
        }
        List<VersionDetail> versions = byFull.entrySet().stream()
                .sorted((a, b) -> VersionNames.compare(b.getKey(), a.getKey()))
                .map(e -> new VersionDetail(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();

        return new Group(shortVersion, noVersion, total, open, total - open,
                (int) bugs.stream().filter(b -> b.open() && b.critical()).count(),
                (int) bugs.stream().filter(b -> b.open() && b.assignee().isBlank()).count(),
                pct(open, maxGroup), pct(total - open, maxGroup), versions,
                stats(bugs.stream().filter(Bug::open).toList(), Bug::priority, "(tanpa prioritas)", 10),
                List.copyOf(bugs));
    }

    /** Tren dibuat vs selesai untuk 8 minggu terakhir (minggu mulai Senin, zona WIB). */
    private List<WeekPoint> weekly(List<Parsed> parsed, Instant now) {
        LocalDate thisMonday = now.atZone(WIB).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM", new Locale("id", "ID"));
        List<int[]> counts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = WEEKS - 1; i >= 0; i--) {
            LocalDate start = thisMonday.minusWeeks(i), end = start.plusDays(7);
            int created = 0, resolved = 0;
            for (Parsed p : parsed) {
                if (p.created() != null && inRange(p.created(), start, end)) created++;
                if (p.resolved() != null && inRange(p.resolved(), start, end)) resolved++;
            }
            counts.add(new int[]{created, resolved});
            labels.add("Minggu " + fmt.format(start));
        }
        int max = counts.stream().mapToInt(c -> Math.max(c[0], c[1])).max().orElse(1);
        List<WeekPoint> out = new ArrayList<>();
        for (int i = 0; i < counts.size(); i++) {
            out.add(new WeekPoint(labels.get(i), counts.get(i)[0], counts.get(i)[1], pct(counts.get(i)[0], max), pct(counts.get(i)[1], max)));
        }
        return List.copyOf(out);
    }

    private static boolean inRange(Instant t, LocalDate startInclusive, LocalDate endExclusive) {
        LocalDate d = t.atZone(WIB).toLocalDate();
        return !d.isBefore(startInclusive) && d.isBefore(endExclusive);
    }

    private static List<BarStat> stats(List<Bug> bugs, Function<Bug, String> by, String emptyLabel, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Bug b : bugs) {
            String k = by.apply(b);
            counts.merge(k == null || k.isBlank() ? emptyLabel : k, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        int max = sorted.isEmpty() ? 0 : sorted.get(0).getValue();
        return sorted.stream().limit(limit).map(e -> new BarStat(e.getKey(), e.getValue(), pct(e.getValue(), max))).toList();
    }

    private static int pct(int v, int max) {
        return max <= 0 ? 0 : (int) Math.round(v * 100.0 / max);
    }
}
