package com.hito.genai.jira;

import java.util.List;

public record DeploymentReport(
        String fixVersion,
        String jql,
        String generatedAt,
        int totalIssues,
        int repoCount,
        int devOpsCount,
        int databaseCount,
        int ddlCount,
        int hotfixSanityCount,
        List<ChecklistRow> checklistRows,
        List<RepoImpactRow> repoImpactedRows,
        List<DevOpsRow> sitDevOpsRows,
        List<DevOpsRow> uatDevOpsRows,
        List<DevOpsRow> productionDevOpsRows,
        List<DevOpsRow> unclassifiedDevOpsRows,
        List<DatabaseRow> databaseRows,
        List<DdlRow> ddlRows,
        List<AllTicketRow> allTicketRows,
        List<HotfixRow> hotfixSanityRows) {

    public record ChecklistRow(int no, String description, String issueKey, String ticketUrl) { }

    public record RepoImpactRow(
            int no, String repository,
            String deploymentSit, String deploymentUat, String deploymentProd,
            String unitTestingSit, String unitTestingUat, String unitTestingProd,
            String pullRequestToMain) { }

    public record DevOpsRow(
            int no, String issueKey, String ticketUrl, String repository,
            String epic, String type, String note, String needInUat, String status) { }

    public record DatabaseRow(int no, String collectionsRelated, String issueKey, String ticketUrl) { }

    public record DdlRow(
            int no, String issueKey, String ticketUrl, String bitbucketBranch,
            String note, String collections, String collectionNote,
            String statusSit, String statusUat, String alreadyCheckImprove,
            String statusProd, String rollbackDdl, String assetsFolder) { }

    public record AllTicketRow(
            int no, String issueType, String issueKey, String ticketUrl, String summary,
            String usingPrdaApi, String parentKey, String parentSummary,
            String assignee, String assigneeId, String reporter, String reporterId,
            String sprint1, String sprint2, String sprint3, String sprint4, String sprint5,
            String status, String priority, String resolution, String created, String updated,
            String storyPoints) { }

    public record HotfixRow(int no, String issueKey, String ticketUrl) { }
}
