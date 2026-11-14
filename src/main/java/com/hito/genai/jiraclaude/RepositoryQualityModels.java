package com.hito.genai.jiraclaude;

/** Models used by the repository branch quality report and Excel export. */
public final class RepositoryQualityModels {
    private RepositoryQualityModels() {}

    public record BranchRef(String name, String commit, String url) {}

    public record PipelineInfo(String buildNumber, String status, String stepName,
                               String commit, String completedOn, String url, String note, String branch) {
        public static PipelineInfo notFound(String note) {
            return new PipelineInfo("", "NOT_FOUND", "", "", "", "", note, "");
        }
        public String display() {
            if (buildNumber == null || buildNumber.isBlank()) {
                return status == null || status.isBlank() ? "—" : status;
            }
            return "#" + buildNumber + (status == null || status.isBlank() ? "" : " · " + status);
        }
    }

    /** Latest test pipeline for one Database environment branch. Status is normalized to SUCCESS/FAILED. */
    public record EnvironmentPipelineResult(String branch, String status, String buildNumber,
                                            String stepName, String url, String note) {
        public String displayStatus() {
            return status == null || status.isBlank() ? "—" : status;
        }
        public boolean isSuccess() { return "SUCCESS".equalsIgnoreCase(status); }
        /** Compatibility alias retained for older templates/callers. */
        public boolean isDone() { return isSuccess() || "DONE".equalsIgnoreCase(status); }
        public boolean isFailed() { return "FAILED".equalsIgnoreCase(status); }
        public boolean isRunning() { return "IN PROGRESS".equalsIgnoreCase(status); }
        public static EnvironmentPipelineResult empty(String branch, String note) {
            return new EnvironmentPipelineResult(branch == null ? "" : branch, "", "", "", "", note == null ? "" : note);
        }
    }

    /** Pipeline status for the dedicated backendapi-ddl Database Related section. */
    public record DatabasePipelineResult(EnvironmentPipelineResult sit,
                                         EnvironmentPipelineResult uat,
                                         EnvironmentPipelineResult prod) {
        public static DatabasePipelineResult empty() {
            return new DatabasePipelineResult(
                    EnvironmentPipelineResult.empty("", ""),
                    EnvironmentPipelineResult.empty("", ""),
                    EnvironmentPipelineResult.empty("", ""));
        }
    }

    public record SonarInfo(String qualityGateStatus, String coveragePercent,
                            String analysisDate, String analysisRevision, String note) {}

    public record RepoQualityRow(String repository, String branch, String commit,
                                 String sonarProjectKey, String qualityGateStatus,
                                 String coveragePercent, String sonarAnalysisDate, String sonarAnalysisRevision,
                                 String pipelineBuild, String pipelineStatus,
                                 String unitTestStep, String pipelineCommit,
                                 String pipelineCompletedOn, String pipelineUrl,
                                 String note, boolean coverageWhitelisted) {
        /** True only when a numeric coverage value is strictly below 80 percent. */
        public boolean isCoverageBelow80() {
            Double value = coverageValue();
            return value != null && value < 80.0d;
        }

        /** True only when a numeric coverage value is strictly above 80 percent. */
        public boolean isCoverageAbove80() {
            Double value = coverageValue();
            return value != null && value > 80.0d;
        }

        private Double coverageValue() {
            if (coveragePercent == null || coveragePercent.isBlank()) return null;
            try {
                return Double.parseDouble(coveragePercent.trim().replace("%", "").replace(',', '.'));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        public String pipelineLabel() {
            if (pipelineBuild == null || pipelineBuild.isBlank()) {
                return pipelineStatus == null || pipelineStatus.isBlank() ? "—" : pipelineStatus;
            }
            return "#" + pipelineBuild + (pipelineStatus == null || pipelineStatus.isBlank()
                    ? "" : " · " + pipelineStatus);
        }
    }
}
