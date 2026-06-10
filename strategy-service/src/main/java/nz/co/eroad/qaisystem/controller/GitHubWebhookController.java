package nz.co.eroad.qaisystem.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.github.PrTracker;
import nz.co.eroad.qaisystem.kafka.FeedbackEventProducer;
import nz.co.eroad.qaisystem.kafka.TestScriptsProducer;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.FeedbackEvent;
import nz.co.eroad.qaisystem.model.PrRecord;
import nz.co.eroad.qaisystem.model.PrType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Receives GitHub pull_request webhook events and routes them:
 *
 * BDD PR merged    → publish BddScenario to TestScriptsQueue  → codegen-service
 * BDD PR rejected  → publish FeedbackEvent to FeedbackQueue   → feedback-service
 * TEST PR merged   → pipeline complete (log only)
 * TEST PR rejected → publish FeedbackEvent to FeedbackQueue   → feedback-service
 */
@Slf4j
@RestController
@RequestMapping("/api/strategy/github-webhook")
@RequiredArgsConstructor
public class GitHubWebhookController {

    private final ObjectMapper         objectMapper;
    private final PrTracker            prTracker;
    private final TestScriptsProducer  testScriptsProducer;
    private final FeedbackEventProducer feedbackEventProducer;

    @Value("${github.webhook-secret:}")
    private String webhookSecret;

    /** When true (default), reject requests that arrive without a configured secret. Disable only for local dev. */
    @Value("${github.webhook.require-secret:true}")
    private boolean requireSecret;

    @PostMapping
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestHeader("X-GitHub-Event")                               String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {

        log.info("[GitHubWebhookController] Received X-GitHub-Event='{}'", event);

        if (!verifySignature(payload, signature)) {
            log.warn("[GitHubWebhookController] Signature mismatch — rejecting request");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid webhook signature"));
        }

        if (!"pull_request".equals(event)) {
            return ResponseEntity.ok(Map.of("status", "IGNORED", "event", event));
        }

        try {
            JsonNode root       = objectMapper.readTree(payload);
            String   action     = root.path("action").asText();
            boolean  merged     = root.path("pull_request").path("merged").asBoolean(false);
            String   headBranch = root.path("pull_request").path("head").path("ref").asText();
            int      prNumber   = root.path("pull_request").path("number").asInt(0);
            String   prUrl      = root.path("pull_request").path("html_url").asText("");

            if (!"closed".equals(action)) {
                return ResponseEntity.ok(Map.of("status", "IGNORED",
                        "reason", "action is not 'closed'", "action", action));
            }

            Optional<PrRecord> recordOpt = prTracker.findByBranch(headBranch);
            if (recordOpt.isEmpty()) {
                log.info("[GitHubWebhookController] Branch '{}' not tracked — not a QA PR", headBranch);
                return ResponseEntity.ok(Map.of("status", "NOT_A_QA_PR", "branch", headBranch));
            }

            PrRecord record = recordOpt.get();
            prTracker.remove(headBranch);

            if (merged) {
                return handleMerged(record, prNumber, prUrl, headBranch);
            } else {
                return handleRejected(record, prNumber, headBranch);
            }

        } catch (Exception e) {
            log.error("[GitHubWebhookController] Error processing webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Webhook processing failed"));
        }
    }

    private ResponseEntity<Map<String, Object>> handleMerged(
            PrRecord record, int prNumber, String prUrl, String headBranch) {

        log.info("[GitHubWebhookController] PR #{} MERGED — type={} branch='{}'",
                prNumber, record.getType(), headBranch);

        if (record.getType() == PrType.BDD) {
            BddScenario scenario = record.getBddScenario();
            testScriptsProducer.publishBddScenario(scenario);
            return ResponseEntity.ok(Map.of(
                    "status",       "CODEGEN_TRIGGERED",
                    "sourcePrId",   scenario.getPrId(),
                    "scenarioId",   scenario.getScenarioId(),
                    "mergedBranch", headBranch,
                    "githubPrUrl",  prUrl));
        } else {
            log.info("[GitHubWebhookController] Test code PR merged for PR '{}' — pipeline complete",
                    record.getTestScript().getPrId());
            return ResponseEntity.ok(Map.of(
                    "status",     "TEST_PR_MERGED",
                    "sourcePrId", record.getTestScript().getPrId(),
                    "branch",     headBranch));
        }
    }

    private ResponseEntity<Map<String, Object>> handleRejected(
            PrRecord record, int prNumber, String headBranch) {

        log.info("[GitHubWebhookController] PR #{} REJECTED — type={} branch='{}'",
                prNumber, record.getType(), headBranch);

        FeedbackEvent event = FeedbackEvent.builder()
                .branchName(headBranch)
                .prNumber(prNumber)
                .prType(record.getType())
                .bddScenario(record.getBddScenario())
                .testScript(record.getTestScript())
                .build();

        feedbackEventProducer.publishFeedbackEvent(event);

        return ResponseEntity.ok(Map.of(
                "status",   "FEEDBACK_TRIGGERED",
                "prType",   record.getType().name(),
                "prNumber",  prNumber,
                "branch",   headBranch,
                "message",  "Feedback event published — feedback-service will re-generate"));
    }

    private boolean verifySignature(String payload, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            if (requireSecret) {
                log.error("[GitHubWebhookController] GITHUB_WEBHOOK_SECRET is not configured " +
                          "and github.webhook.require-secret=true — rejecting all webhook requests. " +
                          "Set GITHUB_WEBHOOK_SECRET or set github.webhook.require-secret=false for local dev.");
                return false;
            }
            log.warn("[GitHubWebhookController] GITHUB_WEBHOOK_SECRET not configured — " +
                     "signature check skipped (require-secret=false). DO NOT use in production.");
            return true;
        }
        if (signature == null || !signature.startsWith("sha256=")) {
            log.warn("[GitHubWebhookController] Missing or malformed X-Hub-Signature-256 header");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash     = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[GitHubWebhookController] Signature verification error", e);
            return false;
        }
    }
}
