package nz.co.eroad.qaisystem.controller;

import nz.co.eroad.qaisystem.ai.AIImpactEvaluator;
import nz.co.eroad.qaisystem.config.AIImpactProperties;
import nz.co.eroad.qaisystem.engine.ChangeTypeDetector;
import nz.co.eroad.qaisystem.engine.DependencyGraph;
import nz.co.eroad.qaisystem.engine.RiskScorer;
import nz.co.eroad.qaisystem.model.*;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.parser.GitDiffParser;
import nz.co.eroad.qaisystem.service.TestCoverageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/impact")
@RequiredArgsConstructor
public class ImpactController {

    private final GitDiffParser       diffParser;
    private final ChangeTypeDetector  changeTypeDetector;
    private final DependencyGraph     dependencyGraph;
    private final RiskScorer          riskScorer;
    private final TestCoverageService testCoverageService;
    private final AIImpactEvaluator   aiImpactEvaluator;
    private final AIImpactProperties  aiProps;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "service",         "impact-service",
                "status",          "OPERATIONAL",
                "timestamp",       LocalDateTime.now().toString(),
                "aiEnabled",       aiProps.isConfigured(),
                "aiModel",         aiProps.isConfigured() ? aiProps.getModel() : "disabled",
                "aiGrayZone",      "[" + aiProps.getConfidenceLowerBound()
                                       + ", " + aiProps.getConfidenceUpperBound() + "]"));
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> req) {
        String rawDiff = req.getOrDefault("diff", "");

        List<GitDiff> diffs                 = diffParser.parse(rawDiff);
        List<ChangeType> types              = changeTypeDetector.detect(diffs);
        List<ImpactedComponent> comps       = dependencyGraph.buildAndAnalyse(diffs);
        double riskScore                    = riskScorer.score(diffs, types, comps);
        CoverageReport coverage             = testCoverageService.assess(comps, diffs);

        // AI last-resort refinement (may be empty if disabled/not in gray zone)
        // Uses a minimal PullRequest facade since controller doesn't have the full PR
        PullRequest facade = PullRequest.builder()
                .prId("analyze-endpoint")
                .title(req.getOrDefault("title", "(manual analyze call)"))
                .author("api")
                .repositoryName(req.getOrDefault("repository", "unknown"))
                .rawDiffContent(rawDiff)
                .build();

        var aiResult = aiImpactEvaluator.evaluate(facade, diffs, types, comps, riskScore);
        ImpactEnvelope.AIInsight aiInsight = aiResult.orElse(null);

        double finalScore = aiInsight != null && aiInsight.isApplied()
                ? aiInsight.getAdjustedRiskScore() : riskScore;
        List<ChangeType> finalTypes = aiInsight != null && aiInsight.isApplied()
                ? mergeTypes(types, aiInsight.getAddedChangeTypes()) : types;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filesFound",  diffs.size());
        response.put("changeTypes", finalTypes);
        response.put("riskScore",   String.format("%.2f", finalScore));
        response.put("riskLevel",   riskScorer.toLevel(finalScore).name());
        response.put("coverage",    Map.of(
                "source",             coverage.getSource(),
                "level",              coverage.getLevel(),
                "untestedComponents", coverage.getUntestedComponents(),
                "requiredTestTypes",  coverage.getRequiredTestTypes(),
                "note",               "Full E2E/integration coverage assessed by strategy-service after test-repo scan"));

        if (aiInsight != null) {
            response.put("aiInsight", Map.of(
                    "applied",              aiInsight.isApplied(),
                    "model",                aiInsight.getModel(),
                    "originalRiskScore",    String.format("%.2f", aiInsight.getOriginalRiskScore()),
                    "adjustedRiskScore",    String.format("%.2f", aiInsight.getAdjustedRiskScore()),
                    "additionalChangeTypes", aiInsight.getAddedChangeTypes(),
                    "reasoning",            aiInsight.getReasoning()));
        } else {
            response.put("aiInsight", Map.of(
                    "applied", false,
                    "reason",  aiProps.isConfigured()
                            ? "Risk score outside gray zone — deterministic result is confident"
                            : "AI evaluation disabled (set aiqa.ai.enabled=true)"));
        }

        return ResponseEntity.ok(response);
    }

    private List<ChangeType> mergeTypes(List<ChangeType> base, List<ChangeType> extra) {
        if (extra == null || extra.isEmpty()) return base;
        List<ChangeType> merged = new ArrayList<>(base);
        extra.stream().filter(t -> !merged.contains(t)).forEach(merged::add);
        return merged;
    }
}


