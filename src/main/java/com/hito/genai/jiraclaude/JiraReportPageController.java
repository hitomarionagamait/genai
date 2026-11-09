package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.DevOpsRow;
import com.hito.genai.jiraclaude.ReportModels.Report;
import com.hito.genai.jiraclaude.ReportModels.VersionOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Controller
public class JiraReportPageController {

    private static final Logger log = LoggerFactory.getLogger(JiraReportPageController.class);
    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm 'WIB'").withZone(ZoneId.of("Asia/Jakarta"));

    private final JiraReportService service;
    private final ChecklistExcelExporter exporter;

    public JiraReportPageController(JiraReportService service, ChecklistExcelExporter exporter) {
        this.service = service;
        this.exporter = exporter;
    }

    /** Ringkasan status DevOps per environment untuk kartu di dashboard. */
    public record EnvStat(String env, int total, long success, long progress, long failed) {
    }

    @GetMapping("/jira-report")
    public String page(@RequestParam(required = false) String fixVersion,
                       @RequestParam(defaultValue = "false") boolean refresh, Model model) {
        boolean configured = service.isConfigured();
        model.addAttribute("configured", configured);
        model.addAttribute("hasProjectKeys", service.hasProjectKeys());
        model.addAttribute("selected", fixVersion == null ? "" : fixVersion.trim());
        List<VersionOption> unreleased = new ArrayList<>();
        List<VersionOption> released = new ArrayList<>();
        model.addAttribute("unreleased", unreleased);
        model.addAttribute("released", released);
        if (!configured) {
            return "jira-report";
        }

        if (service.hasProjectKeys()) {
            try {
                for (VersionOption v : service.fixVersions(refresh)) {
                    (v.released() ? released : unreleased).add(v);
                }
            } catch (JiraException e) {
                model.addAttribute("versionsError", e.getMessage());
            }
        }

        if (fixVersion != null && !fixVersion.isBlank()) {
            try {
                Report report = service.report(fixVersion, refresh);
                model.addAttribute("report", report);
                model.addAttribute("generatedAtText", TS.format(report.generatedAt()));
                List<EnvStat> envStats = new ArrayList<>();
                report.devops().forEach((env, rows) -> envStats.add(new EnvStat(env, rows.size(),
                        count(rows, "SUCCESS"), count(rows, "ON PROGRESS"), count(rows, "FAILED"))));
                model.addAttribute("envStats", envStats);
                model.addAttribute("devopsTotal", report.devops().values().stream().mapToInt(List::size).sum()
                        + report.devopsUnknownEnv().size());
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
    public ResponseEntity<byte[]> export(@RequestParam String fixVersion) {
        try {
            Report report = service.report(fixVersion, false);
            byte[] xlsx = exporter.export(report);
            String name = "Pre_Deployment_Checklist_" + report.fixVersion().replace(' ', '_') + ".xlsx";
            return ResponseEntity.ok()
                    .contentType(XLSX)
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

    private static long count(List<DevOpsRow> rows, String status) {
        return rows.stream().filter(r -> r.status().equals(status)).count();
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String msg) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN)
                .body((msg == null ? "Error" : msg).getBytes(StandardCharsets.UTF_8));
    }
}
