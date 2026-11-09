package com.hito.genai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Web controller for serving Thymeleaf views.
 * Uses client-side JavaScript to call REST APIs.
 */
@Controller
public class WebController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/simple-chat")
    public String simpleChat() {
        return "simple-chat";
    }

    @GetMapping("/reviewer-dashboard")
    public String reviwerDashboard() {
        return "reviewer-dashboard";
    }

    @GetMapping("/k6-agent")
    public String k6Agent() {
        return "k6-agent";
    }

    @GetMapping("/deployment-report")
    public String deploymentReport() {
        return "jira/deployment-report";
    }

//    @GetMapping("/jira-report")
//    public String jiraClaudeReport() {
//        return "jira-report";
//    }
//
//    @GetMapping("/conversation-chat")
//    public String conversationChat() {
//        return "conversation-chat";
//    }
}
