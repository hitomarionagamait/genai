package com.hito.genai.k6;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/k6-agent")
public class K6AgentController {

    private final K6AgentService service;
    private final K6Tools tools;

    public K6AgentController(K6AgentService service, K6Tools tools) {
        this.service = service;
        this.tools = tools;
    }

    /** Info untuk UI: root workspace dan daftar folder yang bisa dipilih. */
    @GetMapping("/folders")
    public Map<String, Object> folders() {
        return Map.of("workspaceRoot", tools.root().toString(), "folders", tools.listFolders());
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody K6AgentService.K6Request request) {
        try {
            K6AgentService.K6Reply reply = service.run(request);
            return ResponseEntity.ok(Map.of(
                    "conversationId", reply.conversationId(),
                    "answer", reply.answer(),
                    "savedFiles", reply.savedFiles(),
                    "targetFolder", reply.targetFolder()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }
}
