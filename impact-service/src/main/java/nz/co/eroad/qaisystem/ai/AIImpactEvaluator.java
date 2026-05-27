package nz.co.eroad.qaisystem.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.config.AIImpactProperties;
import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.RiskLevel;
import nz.co.eroad.qaisystem.model.PullRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Last-resort AI evaluation of PR impact when the deterministic risk score is
 * ambiguous (falls in the configurable gray zone).
 *
 * <h2>When it runs</h2>
 * <ol>
 *   <li>Only if {@code aiqa.ai.enabled=true} AND {@code aiqa.ai.api-key} is set.</li>
 *   <li>Only if the deterministic {@code riskScore} is inside
 *       [{@code confidenceLowerBound}, {@code confidenceUpperBound}].</li>
 * </ol>
 *
 * <h2>What it does</h2>
 * Sends a structured prompt to an OpenAI-compatible chat-completions endpoint
 * containing the PR summary, deterministic findings, and a truncated diff.
 * The LLM responds with JSON carrying:
 * <ul>
 *   <li>A refined {@code riskLevel}</li>
 *   <li>An {@code adjustedRiskScore} (clamped to ±{@code maxScoreAdjustment})</li>
 *   <li>Any {@code additionalChangeTypes} the regex missed</li>
 *   <li>A {@code reasoning} string (1–2 sentences)</li>
 * </ul>
 *
 * <h2>Fail-safe design</h2>
 * Every exception path (network error, parse error, timeout, unexpected JSON shape)
 * is caught, logged as a warning, and returns {@code Optional.empty()} — the caller
 * falls back to the deterministic result with zero disruption to the pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIImpactEvaluator {

    private final AIImpactProperties props;
    private final ObjectMapper        objectMapper;

    // Lazy-initialised to avoid creating threads when AI is disabled
    private volatile HttpClient httpClient;

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Evaluates the PR with an LLM and returns an {@link ImpactEnvelope.AIInsight}.
     *
     * @param pr           the original pull request
     * @param diffs        parsed diffs from the PR
     * @param changeTypes  change types already detected by the deterministic engine
     * @param components   impacted components already detected
     * @param riskScore    deterministic risk score (0.0–1.0)
     * @return {@code Optional.of(insight)} when AI ran and produced a result,
     *         {@code Optional.empty()} when AI is disabled / not configured /
     *         score outside gray zone / any error occurred
     */
    public Optional<ImpactEnvelope.AIInsight> evaluate(
            PullRequest pr,
            List<GitDiff> diffs,
            List<ChangeType> changeTypes,
            List<ImpactedComponent> components,
            double riskScore) {

        if (!props.isConfigured()) {
            log.debug("[AIImpactEvaluator] AI evaluation disabled or not configured — skipping");
            return Optional.empty();
        }

        if (!props.isInGrayZone(riskScore)) {
            log.debug("[AIImpactEvaluator] Risk score {} outside gray zone [{}, {}] - " +
                            "deterministic result is confident, skipping AI",
                    String.format("%.2f", riskScore),
                    props.getConfidenceLowerBound(), props.getConfidenceUpperBound());
            return Optional.empty();
        }

        log.info("[AIImpactEvaluator] Risk score {} is in gray zone - invoking AI (model={})",
                String.format("%.2f", riskScore), props.getModel());

        try {
            String prompt  = buildPrompt(pr, diffs, changeTypes, components, riskScore);
            String payload = buildRequestPayload(prompt);
            String rawJson = callApi(payload);
            return Optional.of(parseResponse(rawJson, riskScore, changeTypes));
        } catch (Exception e) {
            log.warn("[AIImpactEvaluator] AI evaluation failed ({}), " +
                    "falling back to deterministic result: {}", e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Prompt building ───────────────────────────────────────────────────────

    private String buildPrompt(PullRequest pr,
                               List<GitDiff> diffs,
                               List<ChangeType> changeTypes,
                               List<ImpactedComponent> components,
                               double riskScore) {

        String truncatedDiff = buildTruncatedDiff(diffs);
        String componentSummary = components.stream()
                .map(c -> c.getComponentName() + " (" + c.getType() + ")")
                .collect(Collectors.joining(", "));
        int totalAdded   = diffs.stream().mapToInt(GitDiff::getLinesAdded).sum();
        int totalDeleted = diffs.stream().mapToInt(GitDiff::getLinesDeleted).sum();

        return """
                You are a senior software engineer reviewing a code change impact assessment.

                The automated analysis produced an UNCERTAIN risk score of %.2f (range 0.0-1.0), \
                which falls in the ambiguous zone between LOW and HIGH risk. \
                Your role is to refine this assessment based on the actual diff content.

                PR Information:
                - Title: %s
                - Repository: %s
                - Author: %s
                - Files changed: %d (+%d / -%d lines)
                - Automated change types detected: %s
                - Impacted components: %s
                - Automated risk level: %s

                Diff content (may be truncated):
                ---
                %s
                ---

                Based on the above, provide a refined assessment. Rules:
                1. riskLevel must be one of: LOW, MEDIUM, HIGH, CRITICAL
                2. adjustedRiskScore must be a decimal 0.0-1.0, MAX adjustment ±%.2f from %.2f
                3. additionalChangeTypes: only types the regex CLEARLY missed (empty array if none)
                   Valid values: NEW_FEATURE, BUG_FIX, REFACTORING, CONFIGURATION_CHANGE, \
                DEPENDENCY_UPDATE, API_CHANGE, DATABASE_CHANGE, SECURITY_FIX, \
                PERFORMANCE_IMPROVEMENT, BREAKING_CHANGE
                4. reasoning: 1-2 factual sentences explaining your assessment
                5. If the automated assessment looks correct, minimal changes are preferred

                Respond ONLY with valid JSON — no markdown, no extra text:
                {
                  "riskLevel": "HIGH",
                  "adjustedRiskScore": 0.78,
                  "additionalChangeTypes": ["SECURITY_FIX"],
                  "reasoning": "The changes touch authentication token handling..."
                }
                """.formatted(
                riskScore,
                safeStr(pr.getTitle()),
                safeStr(pr.getRepositoryName()),
                safeStr(pr.getAuthor()),
                diffs.size(), totalAdded, totalDeleted,
                changeTypes,
                componentSummary.isEmpty() ? "none detected" : componentSummary,
                toRiskLevel(riskScore),
                truncatedDiff,
                props.getMaxScoreAdjustment(), riskScore);
    }

    private String buildTruncatedDiff(List<GitDiff> diffs) {
        StringBuilder sb = new StringBuilder();
        for (GitDiff diff : diffs) {
            sb.append("--- ").append(diff.getFilePath()).append("\n");
            for (GitDiff.DiffHunk hunk : diff.getHunks()) {
                for (GitDiff.DiffLine line : hunk.getLines()) {
                    if (line.getType() != GitDiff.DiffLine.LineType.CONTEXT) {
                        char prefix = line.getType() == GitDiff.DiffLine.LineType.ADDED ? '+' : '-';
                        sb.append(prefix).append(line.getContent()).append("\n");
                    }
                    if (sb.length() >= props.getMaxDiffChars()) {
                        sb.append("\n... [diff truncated at ")
                          .append(props.getMaxDiffChars()).append(" chars]");
                        return sb.toString();
                    }
                }
            }
        }
        return !sb.isEmpty() ? sb.toString() : "(no diff content)";
    }

    // ─── HTTP call ─────────────────────────────────────────────────────────────

    private String buildRequestPayload(String prompt) throws Exception {
        Map<String, Object> message = Map.of(
                "role", "user",
                "content", prompt);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", List.of(message));
        body.put("temperature", 0.1);        // low temperature = consistent, factual output
        body.put("max_tokens", 300);         // response is always short JSON
        body.put("response_format", Map.of("type", "json_object"));

        return objectMapper.writeValueAsString(body);
    }

    private String callApi(String jsonPayload) throws Exception {
        HttpClient client = getHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("AI API returned HTTP " + response.statusCode()
                    + ": " + response.body().substring(0, Math.min(200, response.body().length())));
        }

        // Extract content from OpenAI-compatible response envelope
        JsonNode root = objectMapper.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    // ─── Response parsing ──────────────────────────────────────────────────────

    private ImpactEnvelope.AIInsight parseResponse(String jsonContent,
                                                   double originalScore,
                                                   List<ChangeType> existingTypes) throws Exception {
        JsonNode node = objectMapper.readTree(jsonContent);

        // Parse adjustedRiskScore with clamping
        double rawAdjusted  = node.path("adjustedRiskScore").asDouble(originalScore);
        double maxDelta     = props.getMaxScoreAdjustment();
        double clampedScore = Math.max(originalScore - maxDelta,
                               Math.min(originalScore + maxDelta, rawAdjusted));
        clampedScore        = Math.max(0.0, Math.min(1.0, clampedScore));

        // Parse additional change types (ignore unknown values)
        List<ChangeType> extra = new ArrayList<>();
        JsonNode typesArray = node.path("additionalChangeTypes");
        if (typesArray.isArray()) {
            for (JsonNode t : typesArray) {
                try {
                    ChangeType ct = ChangeType.valueOf(t.asText().toUpperCase());
                    if (!existingTypes.contains(ct)) extra.add(ct);
                } catch (IllegalArgumentException ignored) {
                    log.debug("[AIImpactEvaluator] Unknown change type in AI response: {}", t.asText());
                }
            }
        }

        String reasoning = node.path("reasoning").asText("No reasoning provided.");
        boolean changed  = Math.abs(clampedScore - originalScore) > 0.001 || !extra.isEmpty();

        log.info("[AIImpactEvaluator] AI result - riskScore: {} -> {} | added types: {} | applied: {} | reason: {}",
                String.format("%.2f", originalScore),
                String.format("%.2f", clampedScore),
                extra, changed, reasoning);

        return ImpactEnvelope.AIInsight.builder()
                .applied(changed)
                .model(props.getModel())
                .originalRiskScore(originalScore)
                .adjustedRiskScore(clampedScore)
                .addedChangeTypes(extra)
                .reasoning(reasoning)
                .build();
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private HttpClient getHttpClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                            .build();
                }
            }
        }
        return httpClient;
    }

    private String safeStr(String s) {
        return s != null ? s : "(unknown)";
    }

    private String toRiskLevel(double score) {
        if (score >= 0.9)  return RiskLevel.CRITICAL.name();
        if (score >= 0.70) return RiskLevel.HIGH.name();
        if (score >= 0.40) return RiskLevel.MEDIUM.name();
        return RiskLevel.LOW.name();
    }
}

