package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.DevOpsRow;
import com.hito.genai.jiraclaude.ReportModels.RepoRow;
import com.hito.genai.jiraclaude.ReportModels.Report;
import com.hito.genai.jiraclaude.ReportModels.VersionOption;
import com.hito.genai.jiraclaude.RepositoryQualityModels.DatabasePipelineResult;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class JiraReportPageController {

    private static final Logger log = LoggerFactory.getLogger(JiraReportPageController.class);
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm 'WIB'").withZone(ZoneId.of("Asia/Jakarta"));

    private final JiraReportService service;
    private final ChecklistExcelExporter exporter;
    private final RepositoryQualityService repositoryQualityService;
    private final RepoCoverageWhitelistService whitelistService;

    public JiraReportPageController(JiraReportService service, ChecklistExcelExporter exporter,
                                    RepositoryQualityService repositoryQualityService,
                                    RepoCoverageWhitelistService whitelistService) {
        this.service = service;
        this.exporter = exporter;
        this.repositoryQualityService = repositoryQualityService;
        this.whitelistService = whitelistService;
    }

    /** Ringkasan status DevOps per environment untuk kartu di dashboard. */
    public record EnvStat(String env, int total, long success, long progress, long failed) {
    }

    @GetMapping("/jira-report")
    public String page(@RequestParam(required = false) String fixVersion,
                       @RequestParam(required = false) String sitBranch,
                       @RequestParam(required = false) String targetBranch,
                       @RequestParam(defaultValue = "false") boolean refresh, Model model,
                       HttpServletResponse response) {
        // Report pages are interactive dashboards: do not let browser/proxy caches serve stale HTML.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, 0);

        boolean hasSelectedVersion = fixVersion != null && !fixVersion.isBlank();
        // A selected fixVersion must always be reloaded from Jira, even for the regular Tampilkan button.
        // The explicit refresh button additionally refreshes the fixVersion list itself.
        boolean refreshVersionList = refresh || hasSelectedVersion;
        boolean configured = service.isConfigured();
        model.addAttribute("configured", configured);
        model.addAttribute("hasProjectKeys", service.hasProjectKeys());
        model.addAttribute("selected", fixVersion == null ? "" : fixVersion.trim());

        // The branch selector is populated on the initial page load, even before fixVersion is chosen.
        // Options come from application config only; no global Bitbucket branch listing is performed.
        List<String> sitOptions = repositoryQualityService.sitBranchOptions();
        String requestedSitBranch = (sitBranch == null || sitBranch.isBlank()) ? targetBranch : sitBranch;
        String selectedSitBranch = repositoryQualityService.resolveSitBranch(requestedSitBranch);
        Set<String> coverageWhitelist = whitelistService.load();
        model.addAttribute("sitBranch", selectedSitBranch);
        model.addAttribute("targetBranch", selectedSitBranch); // backward-compatible view attribute
        model.addAttribute("targetBranches", sitOptions);
        model.addAttribute("repositoryQualityRows", List.of());
        model.addAttribute("qualityByRepo", Map.of());
        model.addAttribute("qualitySitByRepo", Map.of());
        model.addAttribute("qualityUatByRepo", Map.of());
        model.addAttribute("qualityProdByRepo", Map.of());
        model.addAttribute("uatBranch", fixVersion == null || fixVersion.isBlank() ? "" : "release/" + fixVersion.trim());
        model.addAttribute("prodBranch", "main");
        model.addAttribute("coverageWhitelist", coverageWhitelist);
        model.addAttribute("whitelistCandidates", List.of());
        model.addAttribute("qualityWarnings", List.of());
        model.addAttribute("databasePipelines", DatabasePipelineResult.empty());

        List<VersionOption> unreleased = new ArrayList<>();
        List<VersionOption> released = new ArrayList<>();
        model.addAttribute("unreleased", unreleased);
        model.addAttribute("released", released);
        if (!configured) {
            return "jira-report";
        }

        if (service.hasProjectKeys()) {
            try {
                for (VersionOption v : service.fixVersions(refreshVersionList)) {
                    (v.released() ? released : unreleased).add(v);
                }
            } catch (JiraException e) {
                model.addAttribute("versionsError", e.getMessage());
            }
        }

        if (fixVersion != null && !fixVersion.isBlank()) {
            try {
                // Never render a selected release from the short-lived in-memory report cache.
                Report sourceReport = service.report(fixVersion, true);
                model.addAttribute("whitelistCandidates", sourceReport.repos().stream()
                        .filter(repo -> isWhitelisted(repo.name(), coverageWhitelist)).toList());
                Report report = filterReportToWhitelist(sourceReport, coverageWhitelist);
                model.addAttribute("report", report);
                model.addAttribute("generatedAtText", TS.format(report.generatedAt()));
                List<EnvStat> envStats = new ArrayList<>();
                report.devops().forEach((env, rows) -> envStats.add(new EnvStat(env, rows.size(),
                        count(rows, "SUCCESS"), count(rows, "ON PROGRESS"), count(rows, "FAILED"))));
                model.addAttribute("envStats", envStats);
                model.addAttribute("devopsTotal", report.devops().values().stream().mapToInt(List::size).sum()
                        + report.devopsUnknownEnv().size());

                RepositoryQualityService.EnvironmentScanResult quality = repositoryQualityService.scanEnvironments(
                        report.repos(), fixVersion, selectedSitBranch, coverageWhitelist);
                model.addAttribute("targetBranches", quality.sitBranchOptions());
                model.addAttribute("sitBranch", quality.sitBranch());
                model.addAttribute("targetBranch", quality.sitBranch());
                model.addAttribute("uatBranch", quality.uatBranch());
                model.addAttribute("prodBranch", quality.prodBranch());
                model.addAttribute("qualitySitByRepo", quality.sit());
                model.addAttribute("qualityUatByRepo", quality.uat());
                model.addAttribute("qualityProdByRepo", quality.prod());
                model.addAttribute("qualityByRepo", quality.sit());
                model.addAttribute("coverageWhitelist", coverageWhitelist);
                model.addAttribute("qualityWarnings", quality.warnings());

                // Dedicated branch-filtered pipelines for prodiadigitalindonesia/backendapi-ddl.
                DatabasePipelineResult databasePipelines = (report.db().isEmpty() && report.ddl().isEmpty())
                        ? DatabasePipelineResult.empty()
                        : repositoryQualityService.scanDatabasePipelines(fixVersion, selectedSitBranch);
                model.addAttribute("databasePipelines", databasePipelines);
            } catch (IllegalArgumentException | JiraException e) {
                model.addAttribute("error", e.getMessage());
            } catch (RuntimeException e) {
                log.error("Gagal menyusun report {}", fixVersion, e);
                model.addAttribute("error", "Terjadi kesalahan saat menyusun report. Lihat log aplikasi.");
            }
        }
        return "jira-report";
    }

    @GetMapping("/jira-report/export")
    public ResponseEntity<byte[]> export(@RequestParam String fixVersion,
                                         @RequestParam(required = false) String sitBranch,
                                         @RequestParam(required = false) String targetBranch) {
        try {
            // Excel must reflect the same latest Jira data as the dashboard, not a stale cached report.
            Report sourceReport = service.report(fixVersion, true);
            Set<String> coverageWhitelist = whitelistService.load();
            Report report = filterReportToWhitelist(sourceReport, coverageWhitelist);
            String requestedSitBranch = (sitBranch == null || sitBranch.isBlank()) ? targetBranch : sitBranch;
            String selectedSitBranch = repositoryQualityService.resolveSitBranch(requestedSitBranch);
            RepositoryQualityService.EnvironmentScanResult quality = repositoryQualityService.scanEnvironments(
                    report.repos(), fixVersion, selectedSitBranch, coverageWhitelist);
            DatabasePipelineResult databasePipelines = (report.db().isEmpty() && report.ddl().isEmpty())
                    ? DatabasePipelineResult.empty()
                    : repositoryQualityService.scanDatabasePipelines(fixVersion, selectedSitBranch);
            byte[] xlsx = exporter.export(report, quality.sit(), quality.uat(), quality.prod(), databasePipelines);
            String name = "Pre_Deployment_Checklist_" + report.fixVersion().replace(' ', '_') + ".xlsx";
            return ResponseEntity.ok()
                    .contentType(XLSX)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                    .body(xlsx);
        } catch (IllegalArgumentException e) {
            return text(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (JiraException e) {
            return text(HttpStatus.BAD_GATEWAY, e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.error("Gagal membuat Excel {}", fixVersion, e);
            return text(HttpStatus.INTERNAL_SERVER_ERROR, "Gagal membuat file Excel. Lihat log aplikasi.");
        }
    }

    /** Keep repository names and DevOps service fields strictly aligned with the whitelist file. */
    private static Report filterReportToWhitelist(Report source, Set<String> whitelist) {
        Set<String> allowed = whitelist == null ? Set.of() : whitelist.stream()
                .map(RepoCoverageWhitelistService::normalize)
                .filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());

        List<RepoRow> filteredRepos = source.repos().stream()
                .filter(repo -> isWhitelisted(repo.name(), allowed)).toList();

        Map<String, List<DevOpsRow>> filteredDevOps = new LinkedHashMap<>();
        source.devops().forEach((env, rows) -> filteredDevOps.put(env, rows.stream()
                .map(row -> withWhitelistedServicesOnly(row, allowed)).toList()));
        List<DevOpsRow> filteredUnknownEnv = source.devopsUnknownEnv().stream()
                .map(row -> withWhitelistedServicesOnly(row, allowed)).toList();

        return new Report(source.fixVersion(), source.generatedAt(), source.sections(), source.tickets(),
                Collections.unmodifiableMap(filteredDevOps), filteredUnknownEnv, source.ddl(), source.db(), source.hotfix(),
                source.bugsDevelopmentUat(), filteredRepos, source.summary(), source.warnings(), source.development(), source.dev());
    }

    private static DevOpsRow withWhitelistedServicesOnly(DevOpsRow row, Set<String> allowed) {
        return new DevOpsRow(row.env(), row.key(), row.link(), row.label(),
                filterServiceNames(row.repository(), allowed), row.epicKey(), row.epicSummary(), row.type(),
                row.note(), row.status(), row.rawStatus());
    }

    private static String filterServiceNames(String services, Set<String> allowed) {
        if (services == null || services.isBlank() || allowed.isEmpty()) return "";
        String trimmed = services.trim();
        if (isWhitelisted(trimmed, allowed)) return trimmed;
        return java.util.Arrays.stream(trimmed.split("[,;|\\n\\r]+"))
                .map(String::trim).filter(value -> !value.isBlank())
                .filter(value -> isWhitelisted(value, allowed))
                .distinct().collect(Collectors.joining(", "));
    }

    private static boolean isWhitelisted(String repository, Set<String> allowed) {
        String normalized = RepoCoverageWhitelistService.normalize(repository);
        if (normalized.isBlank()) return false;
        if (allowed.contains(normalized)) return true;
        String slug = normalized.replaceAll("[^a-zA-Z0-9._-]", "-").toLowerCase(Locale.ROOT);
        return allowed.contains(slug);
    }

    private static long count(List<DevOpsRow> rows, String status) {
        return rows.stream().filter(r -> r.status().equals(status)).count();
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String msg) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN)
                .body((msg == null ? "Error" : msg).getBytes(StandardCharsets.UTF_8));
    }
}
