package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.Report;
import com.hito.genai.jiraclaude.ReportModels.VersionOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jira-report")
public class JiraReportApiController {

    private static final Logger log = LoggerFactory.getLogger(JiraReportApiController.class);

    private final JiraReportService service;
    private final JiraReportAgentService agent;

    public JiraReportApiController(JiraReportService service, JiraReportAgentService agent) {
        this.service = service;
        this.agent = agent;
    }

    public record AgentRequest(String fixVersion, String message, String conversationId) {
    }

    @GetMapping("/versions")
    public ResponseEntity<?> versions(@RequestParam(defaultValue = "false") boolean refresh) {
        try {
            List<VersionOption> v = service.fixVersions(refresh);
            return ResponseEntity.ok(v);
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @GetMapping("/report")
    public ResponseEntity<?> report(@RequestParam String fixVersion,
                                    @RequestParam(defaultValue = "false") boolean refresh) {
        try {
            Report r = service.report(fixVersion, refresh);
            return ResponseEntity.ok(r);
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    @PostMapping("/agent")
    public ResponseEntity<?> agent(@RequestBody AgentRequest request) {
        try {
            if (request.fixVersion() != null && !request.fixVersion().isBlank()) {
                service.validateVersion(request.fixVersion());
            }
            JiraReportAgentService.Reply reply = agent.ask(request.conversationId(), request.fixVersion(), request.message());
            return ResponseEntity.ok(Map.of("conversationId", reply.conversationId(), "answer", reply.answer()));
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    private ResponseEntity<Map<String, String>> error(RuntimeException e) {
        if (e instanceof IllegalArgumentException) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if (e instanceof JiraException) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        }
        log.error("Kesalahan pada jira-report API", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
    }
}
