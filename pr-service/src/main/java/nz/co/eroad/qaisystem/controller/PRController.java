package nz.co.eroad.qaisystem.controller;

import nz.co.eroad.qaisystem.model.PullRequest;
import nz.co.eroad.qaisystem.service.PRService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Webhook / API endpoint for incoming Pull Request events.
 * Publishes to FeatureUpdatesQueue — no AI, no strategy logic here.
 */
@Slf4j
@RestController
@RequestMapping("/api/pr")
@RequiredArgsConstructor
public class PRController {

    private final PRService pullRequestService;

    /**
     * Accepts a full PullRequest payload from a Git webhook.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestBody PullRequest pullRequest) {

        log.info("[PullRequestController] Webhook received for PR: '{}'",
                pullRequest.getTitle());

        PullRequest processed = pullRequestService.processPullRequest(pullRequest);

        return ResponseEntity.accepted().body(Map.of(
                "status",  "ACCEPTED",
                "prId",    processed.getPrId(),
                "message", "PR accepted and queued for AI analysis"
        ));
    }

    /**
     * Manually submit a PR for analysis.
     * Supply {@code raw_diff} in the payload and the service will parse it into
     * structured diffs before publishing to Kafka.
     */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @Valid @RequestBody PullRequest pullRequest) {

        log.info("[PullRequestController] Manual PR submission: '{}'",
                pullRequest.getTitle());

        PullRequest processed = pullRequestService.processPullRequest(pullRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status",     "QUEUED",
                "prId",       processed.getPrId(),
                "message",    "PR queued for AI-driven QA analysis",
                "diffsCount", processed.getDiffs() != null ? processed.getDiffs().size() : 0,
                "details",    Map.of(
                        "sourceBranch", processed.getSourceBranch() != null
                                ? processed.getSourceBranch() : "unknown",
                        "targetBranch", processed.getTargetBranch(),
                        "author",       processed.getAuthor()
                )
        ));
    }

    /**
     * Triggers a full demo run using built-in sample data.
     * Useful for verifying the pipeline without a real Git repo.
     * The sample payload contains a {@code raw_diff} string that is parsed into
     * structured diffs by the service before publishing.
     */
    @PostMapping("/demo")
    public ResponseEntity<Map<String, Object>> demo() {
        log.info("[PullRequestController] Demo run triggered");

        PullRequest sample    = pullRequestService.createSamplePullRequest();
        PullRequest processed = pullRequestService.processPullRequest(sample);

        return ResponseEntity.accepted().body(Map.of(
                "status",     "DEMO_TRIGGERED",
                "prId",       processed.getPrId(),
                "prTitle",    processed.getTitle(),
                "message",    "Demo PR published — watch logs for full pipeline execution",
                "diffsCount", processed.getDiffs() != null ? processed.getDiffs().size() : 0
        ));
    }

    /**
     * Health check for the PR endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status",  "UP",
                "service", "PullRequestController"
        ));
    }
}
