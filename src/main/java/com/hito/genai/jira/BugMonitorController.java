package com.hito.genai.jira;

import com.hito.genai.jira.BugModels.BugReport;
import com.hito.genai.jiraclaude.JiraException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Controller
public class BugMonitorController {

    private static final Logger log = LoggerFactory.getLogger(BugMonitorController.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm 'WIB'").withZone(ZoneId.of("Asia/Jakarta"));

    private final BugMonitorService service;

    public BugMonitorController(BugMonitorService service) {
        this.service = service;
    }

    @GetMapping("/bug-monitor")
    public String page(@RequestParam(defaultValue = "false") boolean refresh, Model model) {
        boolean configured = service.isConfigured();
        model.addAttribute("configured", configured);
        if (configured) {
            try {
                BugReport report = service.report(refresh);
                model.addAttribute("report", report);
                model.addAttribute("generatedAtText", TS.format(report.generatedAt()));
                // grup yang terbuka otomatis: versi terbaru yang masih punya tiket open
                model.addAttribute("openGroup", report.groups().stream().filter(g -> g.open() > 0)
                        .map(BugModels.Group::shortVersion).findFirst().orElse(""));
            } catch (JiraException e) {
                model.addAttribute("error", e.getMessage());
            } catch (RuntimeException e) {
                log.error("Gagal menyusun dashboard bug", e);
                model.addAttribute("error", "Terjadi kesalahan saat menyusun dashboard. Lihat log aplikasi.");
            }
        }
        return "bug-monitor";
    }

    /** Data yang sama dalam JSON (untuk integrasi lain). */
    @GetMapping("/api/bug-monitor")
    @ResponseBody
    public ResponseEntity<?> api(@RequestParam(defaultValue = "false") boolean refresh) {
        try {
            return ResponseEntity.ok(service.report(refresh));
        } catch (JiraException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Gagal menyusun dashboard bug", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Terjadi kesalahan. Lihat log aplikasi."));
        }
    }
}
