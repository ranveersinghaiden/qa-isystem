package nz.co.eroad.qaisystem.model;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    /**
     * GitHub organisation or user that owns the repository.
     * Used by the GitHub diff fetcher to construct the API URL.
     * Accepts "repo_owner", "owner", "organization", or "org" in webhook JSON.
     * If not provided, falls back to the configured {@code github.default-org}.
     */
    @JsonProperty("repo_owner")
    @JsonAlias({"owner", "organization", "org"})
    private String repoOwner;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonProperty("git_diff")
    private List<GitDiff> diffs;
    private PrStatus status;

    /**
     * Raw unified-diff string (output of {@code git diff}).
     * Accepted as {@code raw_diff} in the JSON payload (consistent with other
     * snake_case fields).  Parsed into {@link GitDiff} objects by {@code GitDiffParser}
     * in {@code pr-service} before the message is forwarded to Kafka.
     */
    @JsonProperty("raw_diff")
    private String rawDiffContent;

    @JsonProperty("jira_ids")
    private List<String> jiraIds;

    @JsonProperty("changed_files")
    private List<String> changedFiles;

    public enum PrStatus {
        OPEN, CLOSED, MERGED, DRAFT
    }
}

