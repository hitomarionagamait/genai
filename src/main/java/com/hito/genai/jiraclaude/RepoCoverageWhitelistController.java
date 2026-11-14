package com.hito.genai.jiraclaude;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** Admin-only HTML action for maintaining the repository coverage whitelist. */
@Controller
public class RepoCoverageWhitelistController {

    private final RepoCoverageWhitelistService whitelistService;

    public RepoCoverageWhitelistController(RepoCoverageWhitelistService whitelistService) {
        this.whitelistService = whitelistService;
    }

    @PostMapping("/jira-report/coverage-whitelist")
    public String save(@RequestParam String fixVersion,
                       @RequestParam(required = false) String sitBranch,
                       @RequestParam(required = false) String targetBranch,
                       @RequestParam(name = "visibleRepositories", required = false) List<String> visibleRepositories,
                       @RequestParam(name = "repositories", required = false) List<String> repositories,
                       RedirectAttributes redirect) {
        var saved = whitelistService.replaceVisible(visibleRepositories, repositories);
        redirect.addAttribute("fixVersion", fixVersion);
        String branch = (sitBranch == null || sitBranch.isBlank()) ? targetBranch : sitBranch;
        if (branch != null && !branch.isBlank()) redirect.addAttribute("sitBranch", branch);
        redirect.addFlashAttribute("whitelistMessage", "Coverage whitelist tersimpan: " + saved.size() + " repository.");
        return "redirect:/jira-report";
    }
}
