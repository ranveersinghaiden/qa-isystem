package nz.co.eroad.qaisystem.controller;

import nz.co.eroad.qaisystem.engine.*;
import nz.co.eroad.qaisystem.model.*;
import nz.co.eroad.qaisystem.service.TestCoverageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/impact")
@RequiredArgsConstructor
public class ImpactController {

    private final GitDiffParser      diffParser;
    private final ChangeTypeDetector changeTypeDetector;
    private final DependencyGraph    dependencyGraph;
    private final RiskScorer         riskScorer;
    private final TestCoverageService testCoverageService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "service", "impact-service", "status", "OPERATIONAL",
                "timestamp", LocalDateTime.now().toString()));
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> req) {
        String rawDiff = req.getOrDefault("diff", "");
        List<GitDiff> diffs           = diffParser.parse(rawDiff);
        List<ImpactEnvelope.ChangeType> types = changeTypeDetector.detect(diffs);
        List<ImpactEnvelope.ImpactedComponent> comps = dependencyGraph.buildAndAnalyse(diffs);
        double riskScore              = riskScorer.score(diffs, types, comps);
        CoverageReport coverage       = testCoverageService.assess(comps, diffs);

        return ResponseEntity.ok(Map.of(
                "filesFound",    diffs.size(),
                "changeTypes",   types,
                "riskScore",     String.format("%.2f", riskScore),
                "riskLevel",     riskScorer.toLevel(riskScore).name(),
                "coverage",      Map.of(
                        "level",        coverage.getLevel(),
                        "ratio",        String.format("%.2f", coverage.getCoverageRatio()),
                        "missingTests", coverage.getMissingCoverageAreas())));
    }
}

