package com.hito.genai.controller;

import com.hito.genai.service.BitbucketService;
import com.hito.genai.service.GeminiReviewService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ReviewController {

    private final GeminiReviewService reviewService;
    private final BitbucketService bitbucketService;

    public ReviewController(GeminiReviewService reviewService, BitbucketService bitbucketService) {
        this.reviewService = reviewService;
        this.bitbucketService = bitbucketService;
    }

    @GetMapping("/dashboard/reviewer")
    public String getReviewDashboard() {
        return "reviewer-dashboard";
    }

    @PostMapping("/dashboard/reviewer")
    public String submitReview(@RequestParam("prLink") String prLink, Model model) {
        // 1. Tarik file Diff dari Bitbucket API berdasarkan URL
        String codeDiff = bitbucketService.getPullRequestDiff(prLink);
        String reviewResult;
        
        // 2. Jika sukses menarik kode, teruskan ke Gemini Agent
        if (codeDiff.startsWith("ERROR:")) {
            reviewResult = codeDiff;
        } else {
            reviewResult = reviewService.reviewCode(codeDiff);
        }
        
        model.addAttribute("prLink", prLink);
        model.addAttribute("codeDiffFetched", !codeDiff.startsWith("ERROR:"));
        model.addAttribute("reviewResult", reviewResult);
        
        return "reviewer-dashboard";
    }
}
