package nz.co.eroad.qaisystem.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullRequest {

    @JsonProperty("pr_id")
    private String prId;
    private String title;
    private String description;
    private String author;
    private String sourceBranch;
    private String targetBranch;
    private String repositoryUrl;
    private String repositoryName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonProperty("git_diff")
    private List<GitDiff> diffs;
    private PrStatus status;
    private String rawDiffContent;

    @JsonProperty("jira_ids")
    private List<String> jiraIds;

    @JsonProperty("changed_files")
    private List<String> changedFiles;

    public enum PrStatus {
        OPEN, CLOSED, MERGED, DRAFT
    }
}