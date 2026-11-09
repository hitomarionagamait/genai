package com.hito.genai.jira;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Controller
public class JiraDeploymentReportController {
    private static final Logger log = LoggerFactory.getLogger(JiraDeploymentReportController.class);

    private final JiraDeploymentReportService reportService;
    private final JiraDeploymentReportExcelService excelService;
    private final JiraProperties jiraProperties;

    public JiraDeploymentReportController(JiraDeploymentReportService reportService,
                                          JiraDeploymentReportExcelService excelService,
                                          JiraProperties jiraProperties) {
        this.reportService = reportService;
        this.excelService = excelService;
        this.jiraProperties = jiraProperties;
    }

    @GetMapping("/jira/deployment-report")
    public String deploymentReport(
            @RequestParam(name = "fixVersion", required = false) String requestedFixVersion,
            Model model) {

        List<JiraApiClient.VersionOption> versions = List.of();
        boolean versionsLoaded = false;

        try {
            versions = reportService.getAvailableFixVersions();
            versionsLoaded = true;
            log.info("Jira project {} returned {} fix versions",
                    jiraProperties.getProjectKey(), versions.size());
        } catch (Exception ex) {
            log.error("Failed to load Jira fix versions for project {}",
                    jiraProperties.getProjectKey(), ex);
            model.addAttribute("jiraError",
                    "Gagal mengambil daftar Fix Version dari Jira. Periksa konfigurasi dan log aplikasi.");
        }

        model.addAttribute("versions", versions);

        boolean userSubmittedVersion = hasText(requestedFixVersion);
        String candidate = userSubmittedVersion
                ? requestedFixVersion.trim()
                : trimToNull(jiraProperties.getDefaultFixVersion());

        String selectedVersion = null;
        if (candidate != null && versionsLoaded) {
            Optional<String> matchingVersion = versions.stream()
                    .map(JiraApiClient.VersionOption::name)
                    .filter(name -> name.equals(candidate))
                    .findFirst();
            selectedVersion = matchingVersion.orElse(null);
        }

        model.addAttribute("selectedFixVersion", selectedVersion == null ? "" : selectedVersion);

        if (userSubmittedVersion && versionsLoaded && selectedVersion == null) {
            model.addAttribute("reportError",
                    "Fix Version '" + candidate + "' tidak ditemukan pada project Jira '"
                            + jiraProperties.getProjectKey() + "'. Silakan pilih versi dari dropdown.");
        }

        // Generate report only after the user submits the filter form.
        // On the first page load the dropdown is populated without executing JQL.
        if (userSubmittedVersion && selectedVersion != null) {
            try {
                model.addAttribute("report", reportService.createReport(selectedVersion));
            } catch (Exception ex) {
                log.error("Failed to generate Jira deployment report for fixVersion {}", selectedVersion, ex);
                model.addAttribute("reportError", readableError(ex));
            }
        }

        return "jira/deployment-report";
    }


    @GetMapping("/jira/deployment-report/export")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(name = "fixVersion") String fixVersion) {
        try {
            // Build a fresh snapshot so the downloaded workbook contains the latest Jira data.
            DeploymentReport report = reportService.createReport(fixVersion);
            byte[] excelBytes = excelService.generate(report);
            String safeVersion = fixVersion.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
            String filename = "Pre-Deployment-Checklist-" + safeVersion + ".xlsx";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(filename, StandardCharsets.UTF_8).build().toString())
                    .body(excelBytes);
        } catch (Exception ex) {
            log.error("Failed to export deployment report Excel for fixVersion {}", fixVersion, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Gagal membuat Excel deployment report. Periksa log aplikasi untuk detailnya.", ex);
        }
    }

    private String readableError(Exception ex) {
        Throwable rootCause = ex;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        String message = rootCause.getMessage();
        if (message == null || message.isBlank()) {
            return "Gagal mengambil data dari Jira. Periksa log aplikasi untuk detail error.";
        }
        return rootCause.getClass().getSimpleName() + ": " + message;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
