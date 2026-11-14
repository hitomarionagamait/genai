package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.RepoRow;
import com.hito.genai.jiraclaude.RepositoryQualityModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Joins Jira impacted repositories with Bitbucket branch/pipeline data and SonarQube branch metrics.
 * Coverage is read from SonarQube's branch analysis. Bitbucket pipeline data is shown alongside it
 * so engineers can verify the latest unit-test run; the pipeline test-report API itself does not
 * provide a percentage coverage metric.
 */
@Service
public class RepositoryQualityService {
    private static final Logger log = LoggerFactory.getLogger(RepositoryQualityService.class);

    private final BitbucketRepositoryQualityClient bitbucket;
    private final SonarQubeQualityClient sonar;
    private final RepositoryQualityProperties properties;

    public RepositoryQualityService(BitbucketRepositoryQualityClient bitbucket,
                                   SonarQubeQualityClient sonar,
                                   RepositoryQualityProperties properties) {
        this.bitbucket = bitbucket;
        this.sonar = sonar;
        this.properties = properties;
    }

    public record ScanResult(List<String> branchOptions, List<RepoQualityRow> rows, List<String> warnings) {}

    public record EnvironmentScanResult(List<String> sitBranchOptions, String sitBranch, String uatBranch, String prodBranch,
                                        Map<String, RepoQualityRow> sit, Map<String, RepoQualityRow> uat,
                                        Map<String, RepoQualityRow> prod, List<String> warnings) {}

    private static final String DATABASE_BITBUCKET_WORKSPACE = "prodiadigitalindonesia";
    private static final String DATABASE_BITBUCKET_REPOSITORY = "backendapi-ddl";

    /**
     * Read the latest repository pipelines and match SIT/UAT/PROD by target branch or
     * deployment environment label. The repository and workspace are fixed deliberately.
     */
    public DatabasePipelineResult scanDatabasePipelines(String fixVersion, String requestedSitBranch) {
        String sitBranch = resolveSitBranch(requestedSitBranch);
        String version = fixVersion == null ? "" : fixVersion.trim();
        String uatPattern = properties.getEnvironments().getUat().getBranchPattern();
        String uatBranch = version.isBlank() ? ""
                : (uatPattern == null || uatPattern.isBlank() ? "release/{fixVersion}" : uatPattern).replace("{fixVersion}", version);
        String prodBranch = properties.getEnvironments().getProd().getBranch();
        if (!bitbucket.isConfiguredForWorkspace(DATABASE_BITBUCKET_WORKSPACE)) {
            return new DatabasePipelineResult(
                    EnvironmentPipelineResult.empty(sitBranch, "Bitbucket token belum dikonfigurasi; status belum dapat dibaca."),
                    EnvironmentPipelineResult.empty(uatBranch, "Bitbucket token belum dikonfigurasi; status belum dapat dibaca."),
                    EnvironmentPipelineResult.empty(prodBranch, "Bitbucket token belum dikonfigurasi; status belum dapat dibaca."));
        }
        try {
            Map<String, PipelineInfo> pipelines = bitbucket.latestDeploymentEnvironmentPipelines(
                    DATABASE_BITBUCKET_WORKSPACE, DATABASE_BITBUCKET_REPOSITORY,
                    sitBranch, uatBranch, prodBranch);
            return new DatabasePipelineResult(
                    toEnvironmentResult(pipelines.get("SIT"), sitBranch, "SIT"),
                    toEnvironmentResult(pipelines.get("UAT"), uatBranch, "UAT"),
                    toEnvironmentResult(pipelines.get("PROD"), prodBranch, "PROD"));
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.warn("Gagal membaca pipeline Database {}/{}: {}",
                    DATABASE_BITBUCKET_WORKSPACE, DATABASE_BITBUCKET_REPOSITORY, message);
            return new DatabasePipelineResult(
                    EnvironmentPipelineResult.empty(sitBranch, "Gagal membaca pipeline: " + message),
                    EnvironmentPipelineResult.empty(uatBranch, "Gagal membaca pipeline: " + message),
                    EnvironmentPipelineResult.empty(prodBranch, "Gagal membaca pipeline: " + message));
        }
    }

    private static EnvironmentPipelineResult toEnvironmentResult(PipelineInfo pipeline, String fallbackBranch,
                                                                   String environment) {
        if (pipeline == null) {
            return EnvironmentPipelineResult.empty(fallbackBranch,
                    "Pipeline untuk environment " + environment + " belum ditemukan pada rentang pencarian.");
        }
        String raw = pipeline.status() == null ? "" : pipeline.status().trim().toUpperCase(Locale.ROOT);
        String status;
        if (raw.equals("SUCCESSFUL") || raw.equals("SUCCESS") || raw.equals("PASSED")) {
            status = "SUCCESS";
        } else if (raw.equals("FAILED") || raw.equals("ERROR") || raw.equals("STOPPED")
                || raw.equals("CANCELLED") || raw.equals("CANCELED") || raw.equals("EXPIRED")) {
            status = "FAILED";
        } else if (raw.equals("IN_PROGRESS") || raw.equals("PENDING") || raw.equals("RUNNING")
                || raw.equals("BUILDING") || raw.equals("CREATED") || raw.equals("QUEUED")) {
            status = "IN PROGRESS";
        } else {
            status = "";
        }
        String note = pipeline.note();
        if (note == null || note.isBlank()) {
            note = raw.isBlank() ? "Status pipeline belum tersedia." : "Status pipeline Bitbucket: " + raw;
        }
        String branch = pipeline.branch() == null || pipeline.branch().isBlank() ? fallbackBranch : pipeline.branch();
        return new EnvironmentPipelineResult(branch, status, pipeline.buildNumber(),
                pipeline.stepName(), pipeline.url(), note);
    }

    /**
     * Resolve independent target branches for SIT/UAT/PROD. SIT is user-selectable,
     * UAT follows release/{fixVersion}, and PROD always follows main.
     */
    public EnvironmentScanResult scanEnvironments(List<RepoRow> repositories, String fixVersion,
                                                   String requestedSitBranch, Set<String> whitelist) {
        // Branches are configuration-driven. Do not call Bitbucket branch listing here:
        // Sonar read-only credentials are sufficient for Quality Gate and coverage.
        log.info("whitelist ->" + whitelist);
        List<String> sitOptions = sitBranchOptions();
        String sit = configuredSitBranch(requestedSitBranch, sitOptions);
        String version = fixVersion == null ? "" : fixVersion.trim();
        String pattern = properties.getEnvironments().getUat().getBranchPattern();
        String uat = version.isBlank() ? "" : (pattern == null ? "release/{fixVersion}" : pattern).replace("{fixVersion}", version);
        String prod = properties.getEnvironments().getProd().getBranch();

        ScanResult sitScan = scanConfiguredBranch(repositories, sit, whitelist);
        ScanResult uatScan = uat.isBlank() ? new ScanResult(List.of(), List.of(), List.of())
                : scanConfiguredBranch(repositories, uat, whitelist);
        ScanResult prodScan = scanConfiguredBranch(repositories, prod, whitelist);
        return new EnvironmentScanResult(sitOptions, sit, uat, prod,
                index(sitScan.rows()), index(uatScan.rows()), index(prodScan.rows()),
                mergeWarnings(sitScan.warnings(), uatScan.warnings(), prodScan.warnings()));
    }

    /** SIT dropdown is intentionally configuration-only; no global Bitbucket branch enumeration. */
    public List<String> sitBranchOptions() {
        var sit = properties.getEnvironments().getSit();
        LinkedHashSet<String> options = new LinkedHashSet<>();
        if (sit.getBranches() != null) {
            sit.getBranches().stream().filter(v -> v != null && !v.isBlank())
                    .map(String::trim).forEach(options::add);
        }
        if (options.isEmpty()) {
            String fallback = sit.getBranch() == null || sit.getBranch().isBlank()
                    ? "develop-support" : sit.getBranch().trim();
            options.add(fallback);
        }
        return List.copyOf(options);
    }

    public String resolveSitBranch(String requested) {
        return configuredSitBranch(requested, sitBranchOptions());
    }

    private String configuredSitBranch(String requested, List<String> options) {
        var sit = properties.getEnvironments().getSit();
        String configuredDefault = sit.getBranch() == null ? "develop-support" : sit.getBranch().trim();
        String allowedDefault = options.stream().filter(v -> v.equalsIgnoreCase(configuredDefault))
                .findFirst().orElse(options.get(0));
        if (!sit.isSelectable() || requested == null || requested.isBlank()) return allowedDefault;
        String selected = requested.trim();
        return options.stream().filter(v -> v.equalsIgnoreCase(selected)).findFirst().orElse(allowedDefault);
    }

    private ScanResult scanConfiguredBranch(List<RepoRow> repositories, String targetBranch, Set<String> whitelist) {
        List<String> warnings = new ArrayList<>();
        if (!properties.isEnabled()) return new ScanResult(List.of(), List.of(), List.of("repository.quality.enabled=true"));
        Set<String> allowed = whitelist == null ? Set.of() : whitelist;
        List<RepoQualityRow> rows = new ArrayList<>();
        boolean hasWhitelistedRepository = repositories.stream().anyMatch(repo -> {
            String slug = slug(repo.name());
            return allowed.contains(slug) || allowed.contains(RepoCoverageWhitelistService.normalize(repo.name()));
        });
        boolean sonarReady = sonar.isConfigured();
        if (hasWhitelistedRepository && !sonarReady) {
            warnings.add("SonarCloud belum dikonfigurasi. Isi repository.quality.sonar.base-url dan token.");
        }
        for (RepoRow repo : repositories) {
            String slug = slug(repo.name());
            String projectKey = sonarProjectKey(slug);
//            boolean whitelisted = allowed.contains(slug)
//                    || allowed.contains(RepoCoverageWhitelistService.normalize(repo.name()));

            // Do not call SonarCloud for repositories outside repository-coverage-whitelist.txt.
            // This also prevents irrelevant 401/403 warnings for projects that are not selected.
//            SonarInfo info = whitelisted && sonarReady
//                    ? sonar.inspect(projectKey, targetBranch, true)
//                    : new SonarInfo(whitelisted ? "SONAR_NOT_CONFIGURED" : "NOT_WHITELISTED", "", "", "",
//                        whitelisted ? "SonarCloud belum dikonfigurasi." : "Dilewati: repository tidak ada di repository coverage whitelist.");
            // Lokasi: src/main/java/com/hito/genai/jiraclaude/RepositoryQualityService.java

            boolean whitelisted = allowed.contains(slug)
                    || allowed.contains(RepoCoverageWhitelistService.normalize(repo.name()));

            // Tidak memanggil SonarCloud untuk repository di luar repository-coverage-whitelist.txt
            SonarInfo info = whitelisted && sonarReady
                    ? sonar.inspect(projectKey, targetBranch, true)
                    : new SonarInfo(
                    whitelisted ? "SONAR_NOT_CONFIGURED" : "NOT_WHITELISTED",
                    "", // Coverage kosong
                    "", "",
                    whitelisted ? "SonarCloud belum dikonfigurasi." : "Dilewati: repository tidak ada di repository coverage whitelist."
            );
            PipelineInfo pipeline = whitelisted && properties.getBitbucket().isPipelineEnabled() && bitbucket.isConfigured()
                    ? loadPipeline(slug, targetBranch)
                    : new PipelineInfo("", whitelisted ? "BITBUCKET_DISABLED" : "NOT_WHITELISTED", "", "", "", "",
                        whitelisted ? "Metadata pipeline dinonaktifkan atau Bitbucket tidak dikonfigurasi." : "Repository belum masuk coverage whitelist.", targetBranch);
            String note = joinNotes(info.note(), pipeline.note());
            // No Bitbucket HEAD lookup: coverage is the SonarCloud analysis for the exact configured branch.
            rows.add(new RepoQualityRow(repo.name(), targetBranch, "", projectKey,
                    info.qualityGateStatus(), info.coveragePercent(), info.analysisDate(), info.analysisRevision(),
                    pipeline.buildNumber(), pipeline.status(), pipeline.stepName(), pipeline.commit(),
                    pipeline.completedOn(), pipeline.url(), note, whitelisted));
        }
        rows.sort(Comparator.comparing(RepoQualityRow::repository, String.CASE_INSENSITIVE_ORDER));
        return new ScanResult(List.of(targetBranch), List.copyOf(rows), List.copyOf(warnings));
    }

    /** Backward-compatible entry point; scans the supplied Sonar branch without Bitbucket enumeration. */
    public ScanResult scan(List<RepoRow> repositories, String targetBranch, Set<String> whitelist) {
        if (targetBranch == null || targetBranch.isBlank()) {
            return new ScanResult(List.of(), List.of(), List.of("Target branch belum ditentukan."));
        }
        return scanConfiguredBranch(repositories, targetBranch, whitelist);
    }

    private static Map<String, RepoQualityRow> index(List<RepoQualityRow> rows) {
        Map<String, RepoQualityRow> result = new LinkedHashMap<>();
        for (RepoQualityRow row : rows) {
            result.put(RepoCoverageWhitelistService.normalize(row.repository()), row);
            result.put(row.repository(), row);
        }
        return Map.copyOf(result);
    }

    @SafeVarargs
    private static List<String> mergeWarnings(List<String>... groups) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (List<String> group : groups) if (group != null) result.addAll(group);
        return List.copyOf(result);
    }

    private PipelineInfo loadPipeline(String repoSlug, String branch) {
        try {
            return bitbucket.latestUnitTestPipeline(repoSlug, branch);
        } catch (RuntimeException ex) {
            String message = rootMessage(ex);
            log.warn("Gagal mengambil Unit Test pipeline repo {} branch {}: {}", repoSlug, branch, message);
            return PipelineInfo.notFound(message);
        }
    }

    private String sonarProjectKey(String repoSlug) {
        for (Map.Entry<String, String> entry : properties.getSonar().getProjectKeys().entrySet()) {
            if (slug(entry.getKey()).equals(repoSlug)
                    && entry.getValue() != null && !entry.getValue().isBlank()) {
                return entry.getValue().trim();
            }
        }
        return repoSlug;
    }

    private static String slug(String repository) {
        String value = RepoCoverageWhitelistService.normalize(repository);
        return value.replaceAll("[^a-zA-Z0-9._-]", "-").toLowerCase(Locale.ROOT);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static String joinNotes(String a, String b) {
        if (a == null || a.isBlank()) return b == null ? "" : b;
        if (b == null || b.isBlank()) return a;
        return a + " | " + b;
    }

}
